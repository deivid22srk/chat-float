package chatfloat

// realtime.go — Supabase Realtime client via WebSocket.
//
// Connects to the Supabase Realtime endpoint and listens for INSERT
// events on the 'messages' table. When a new message arrives, it's
// pushed to a Go channel that the API layer reads.
//
// This eliminates the need for polling — the app only makes one HTTP
// request to fetch initial messages, then receives updates via WebSocket.

import (
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/websocket"
)

// RealtimeClient connects to Supabase Realtime via WebSocket.
type RealtimeClient struct {
	baseURL   string // e.g. "wss://dbvmkochemjmeyookgsu.supabase.co/realtime/v1/websocket"
	apiKey    string
	conn      *websocket.Conn
	mu        sync.Mutex
	stopCh    chan struct{}
	onMessage func(ChatMessage)
	running   bool
}

// realtimePayload represents the relevant parts of a Supabase Realtime
// Postgres Changes payload.
type realtimePayload struct {
	Type   string          `json:"type"`
	Event  string          `json:"event"` // "INSERT", "UPDATE", etc.
	Table  string          `json:"table"`
	Schema string          `json:"schema"`
	New    json.RawMessage `json:"new_record,omitempty"`
	Old    json.RawMessage `json:"old_record,omitempty"`
}

type realtimeMessageRow struct {
	ID          *int64     `json:"id"`
	Text        string     `json:"text"`
	SenderToken *string    `json:"sender_token"`
	SenderName  string     `json:"sender_name"`
	CreatedAt   *time.Time `json:"created_at"`
}

func NewRealtimeClient(supabaseURL, apiKey string) *RealtimeClient {
	// Convert https:// to wss://
	wsURL := strings.Replace(supabaseURL, "https://", "wss://", 1)
	wsURL = strings.Replace(wsURL, "http://", "ws://", 1)
	wsURL = strings.TrimRight(wsURL, "/") + "/realtime/v1/websocket"
	return &RealtimeClient{
		baseURL: wsURL + "?apikey=" + apiKey + "&vsn=1.0.0",
		apiKey:  apiKey,
		stopCh:  make(chan struct{}),
	}
}

// Start connects to the WebSocket and begins listening for message INSERTs.
// [onMessage] is called (on the goroutine that receives the WS message)
// whenever a new message is inserted into the messages table.
func (c *RealtimeClient) Start(onMessage func(ChatMessage)) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.running {
		return nil
	}
	c.onMessage = onMessage

	// Connect with a custom config (Supabase requires the apikey header)
	config, err := websocket.NewConfig(c.baseURL, "https://localhost/")
	if err != nil {
		return err
	}
	config.Header.Set("apikey", c.apiKey)
	config.Header.Set("Authorization", "Bearer "+c.apiKey)

	conn, err := websocket.DialConfig(config)
	if err != nil {
		return fmt.Errorf("websocket dial: %w", err)
	}
	c.conn = conn
	c.running = true

	// Send join message to subscribe to the messages table
	joinMsg := map[string]interface{}{
		"topic": "realtime:public:messages",
		"event": "phx_join",
		"payload": map[string]interface{}{
			"config": map[string]interface{}{
				"broadcast": map[string]interface{}{"self": false},
				"presence":  map[string]interface{}{"key": ""},
				"postgres_changes": []map[string]interface{}{
					{
						"event":  "INSERT",
						"schema": "public",
						"table":  "messages",
					},
				},
			},
		},
		"ref": "1",
	}
	if err := websocket.JSON.Send(conn, joinMsg); err != nil {
		conn.Close()
		c.running = false
		return fmt.Errorf("send join: %w", err)
	}

	go c.readLoop()
	return nil
}

func (c *RealtimeClient) readLoop() {
	defer func() {
		c.mu.Lock()
		c.running = false
		c.mu.Unlock()
	}()

	for {
		select {
		case <-c.stopCh:
			return
		default:
		}

		c.mu.Lock()
		conn := c.conn
		c.mu.Unlock()
		if conn == nil {
			return
		}

		// Set a read deadline so we can periodically check stopCh
		conn.SetReadDeadline(time.Now().Add(30 * time.Second))

		var msg map[string]interface{}
		if err := websocket.JSON.Receive(conn, &msg); err != nil {
			// Timeout or error — try to reconnect
			if !isClosed(c.stopCh) {
				time.Sleep(3 * time.Second)
				c.reconnect()
			}
			return
		}

		// Check if this is a postgres_changes event
		event, _ := msg["event"].(string)
		if event != "INSERT" {
			continue
		}
		payloadRaw, ok := msg["payload"]
		if !ok {
			continue
		}
		payloadBytes, _ := json.Marshal(payloadRaw)
		var payload realtimePayload
		if err := json.Unmarshal(payloadBytes, &payload); err != nil {
			continue
		}
		if payload.Table != "messages" || payload.Event != "INSERT" {
			continue
		}

		// Parse the new record into a ChatMessage
		var row realtimeMessageRow
		if err := json.Unmarshal(payload.New, &row); err != nil {
			continue
		}
		m := ChatMessage{
			Text:       row.Text,
			SenderName: row.SenderName,
		}
		if row.ID != nil {
			m.ID = *row.ID
		}
		if row.CreatedAt != nil {
			m.Timestamp = row.CreatedAt.UnixMilli()
		}
		if row.SenderToken != nil {
			m.SenderToken = *row.SenderToken
		}
		if c.onMessage != nil {
			c.onMessage(m)
		}
	}
}

// reconnect attempts to re-establish the WebSocket connection.
func (c *RealtimeClient) reconnect() {
	c.mu.Lock()
	if c.conn != nil {
		c.conn.Close()
		c.conn = nil
	}
	onMsg := c.onMessage
	c.mu.Unlock()

	if onMsg == nil {
		return
	}

	// Try to restart
	_ = c.Start(onMsg)
}

// Stop closes the WebSocket connection.
func (c *RealtimeClient) Stop() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.running {
		return
	}
	close(c.stopCh)
	c.running = false
	if c.conn != nil {
		c.conn.Close()
		c.conn = nil
	}
}

func isClosed(ch chan struct{}) bool {
	select {
	case <-ch:
		return true
	default:
		return false
	}
}
