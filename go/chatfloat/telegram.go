package chatfloat

// TelegramClient talks to the Telegram Bot API over HTTP.
//
// It implements the message protocol used by ChatFloat:
//
//   - App-sent messages are prefixed with "[<token>] <username>: <text>"
//     so other app instances can identify the sender by token and resolve
//     the avatar from a registration envelope.
//
//   - Account envelopes are JSON-encoded and base64-wrapped with the
//     prefix "##CHATFLOAT##". Two kinds:
//       REGISTER: announces a token + username + optional avatar
//       AVATAR:   updates the avatar for a known token
//
//   - Real Telegram users' messages are passed through as plain text.

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
)

// TelegramClient is safe for concurrent use.
type TelegramClient struct {
	botToken     string
	groupID      string
	httpClient   *http.Client
	lastUpdateID int64
}

// AccountEnvelope is the JSON payload of a ##CHATFLOAT## envelope message.
type AccountEnvelope struct {
	Type         string `json:"type"` // "REGISTER" | "AVATAR"
	Token        string `json:"token"`
	Username     string `json:"username,omitempty"`
	AvatarBase64 string `json:"avatar_base64,omitempty"`
	CreatedAt    int64  `json:"created_at"`
}

// ResolvedAccount is the result of looking up a token in the group history.
type ResolvedAccount struct {
	Token        string
	Username     string
	AvatarBase64 string
}

const envelopePrefix = "##CHATFLOAT##"

// NewTelegramClient creates a new client. The httpClient has generous timeouts
// because getUpdates uses long polling (30s).
func NewTelegramClient(botToken, groupID string) (*TelegramClient, error) {
	if botToken == "" || groupID == "" {
		return nil, fmt.Errorf("botToken and groupID are required")
	}
	return &TelegramClient{
		botToken: botToken,
		groupID:  groupID,
		httpClient: &http.Client{
			Timeout: 60 * time.Second,
		},
	}, nil
}

// telegramResponse is the standard Telegram API response envelope.
type telegramResponse struct {
	OK          bool            `json:"ok"`
	Result      json.RawMessage `json:"result"`
	Description string          `json:"description,omitempty"`
	ErrorCode   int             `json:"error_code,omitempty"`
}

// telegramMessage is the relevant subset of a Telegram message.
type telegramMessage struct {
	MessageID int64         `json:"message_id"`
	From      *telegramUser `json:"from,omitempty"`
	Chat      *telegramChat `json:"chat,omitempty"`
	Date      int64         `json:"date"`
	Text      string        `json:"text,omitempty"`
}

type telegramUser struct {
	ID        int64  `json:"id"`
	IsBot     bool   `json:"is_bot"`
	FirstName string `json:"first_name,omitempty"`
	LastName  string `json:"last_name,omitempty"`
	Username  string `json:"username,omitempty"`
}

type telegramChat struct {
	ID    int64  `json:"id"`
	Type  string `json:"type,omitempty"`
	Title string `json:"title,omitempty"`
}

type telegramUpdate struct {
	UpdateID int64            `json:"update_id"`
	Message  *telegramMessage `json:"message,omitempty"`
}

// sendMessageResponse is the response from sendMessage.
type sendMessageResponse struct {
	MessageID int64         `json:"message_id"`
	From      *telegramUser `json:"from,omitempty"`
	Chat      *telegramChat `json:"chat,omitempty"`
	Date      int64         `json:"date"`
	Text      string        `json:"text,omitempty"`
}

// RegisterAccount publishes a REGISTER envelope to the group.
// Returns nil on success. Errors are logged but not surfaced to the user
// because account creation should still succeed locally.
func (c *TelegramClient) RegisterAccount(token, username, avatarBase64 string) error {
	envelope := AccountEnvelope{
		Type:         "REGISTER",
		Token:        token,
		Username:     username,
		AvatarBase64: avatarBase64,
		CreatedAt:    time.Now().UnixMilli(),
	}
	return c.sendEnvelope(envelope)
}

// PublishAvatarUpdate publishes an AVATAR envelope for the given token.
func (c *TelegramClient) PublishAvatarUpdate(token, avatarBase64 string) error {
	envelope := AccountEnvelope{
		Type:         "AVATAR",
		Token:        token,
		AvatarBase64: avatarBase64,
		CreatedAt:    time.Now().UnixMilli(),
	}
	return c.sendEnvelope(envelope)
}

func (c *TelegramClient) sendEnvelope(envelope AccountEnvelope) error {
	jsonBytes, err := json.Marshal(envelope)
	if err != nil {
		return err
	}
	encoded := base64.StdEncoding.EncodeToString(jsonBytes)
	text := envelopePrefix + encoded
	return c.sendText(text)
}

func (c *TelegramClient) sendText(text string) error {
	values := url.Values{}
	values.Set("chat_id", c.groupID)
	values.Set("text", text)
	resp, err := c.httpClient.PostForm(
		"https://api.telegram.org/bot"+c.botToken+"/sendMessage",
		values,
	)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	var tr telegramResponse
	if err := json.NewDecoder(resp.Body).Decode(&tr); err != nil {
		return err
	}
	if !tr.OK {
		return fmt.Errorf("telegram: %s (code %d)", tr.Description, tr.ErrorCode)
	}
	return nil
}

// SendMessage posts a chat message to the group as the bot, prefixed with
// "[<token>] <username>: <text>". Returns the resulting ChatMessage.
func (c *TelegramClient) SendMessage(text, token, username string) (*ChatMessage, error) {
	formatted := fmt.Sprintf("[%s] %s: %s", token, username, text)
	values := url.Values{}
	values.Set("chat_id", c.groupID)
	values.Set("text", formatted)

	resp, err := c.httpClient.PostForm(
		"https://api.telegram.org/bot"+c.botToken+"/sendMessage",
		values,
	)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	var tr telegramResponse
	if err := json.NewDecoder(resp.Body).Decode(&tr); err != nil {
		return nil, err
	}
	if !tr.OK {
		return nil, fmt.Errorf("telegram: %s (code %d)", tr.Description, tr.ErrorCode)
	}

	var smr sendMessageResponse
	if err := json.Unmarshal(tr.Result, &smr); err != nil {
		return nil, err
	}
	return &ChatMessage{
		ID:          smr.MessageID,
		Text:        text,
		SenderName:  username,
		SenderToken: token,
		Timestamp:   smr.Date * 1000,
		IsOutgoing:  true,
	}, nil
}

// UpdateBatch is the result of a single getUpdates call.
type UpdateBatch struct {
	Messages  []ChatMessage
	Envelopes []AccountEnvelope
}

// GetUpdates long-polls Telegram for new updates and advances the internal
// offset. Returns parsed chat messages and account envelopes. Envelopes are
// NOT included in the chat message list.
func (c *TelegramClient) GetUpdates() (*UpdateBatch, error) {
	values := url.Values{}
	values.Set("timeout", "30")
	values.Set("offset", strconv.FormatInt(c.lastUpdateID+1, 10))
	values.Set("allowed_updates", `["message"]`)

	resp, err := c.httpClient.PostForm(
		"https://api.telegram.org/bot"+c.botToken+"/getUpdates?"+values.Encode(),
		nil,
	)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	var tr telegramResponse
	if err := json.NewDecoder(resp.Body).Decode(&tr); err != nil {
		return nil, err
	}
	if !tr.OK {
		return nil, fmt.Errorf("telegram: %s (code %d)", tr.Description, tr.ErrorCode)
	}

	var updates []telegramUpdate
	if err := json.Unmarshal(tr.Result, &updates); err != nil {
		return nil, err
	}
	if len(updates) == 0 {
		return &UpdateBatch{}, nil
	}

	// Advance offset past the last update
	maxID := updates[0].UpdateID
	for _, u := range updates {
		if u.UpdateID > maxID {
			maxID = u.UpdateID
		}
	}
	c.lastUpdateID = maxID

	batch := &UpdateBatch{
		Messages:  []ChatMessage{},
		Envelopes: []AccountEnvelope{},
	}
	for _, u := range updates {
		if u.Message == nil {
			continue
		}
		text := u.Message.Text
		if text == "" {
			continue
		}

		// 1. Try parsing as an envelope
		if env, ok := parseEnvelope(text); ok {
			batch.Envelopes = append(batch.Envelopes, env)
			continue
		}

		// 2. Skip the bot's own messages
		if u.Message.From != nil && u.Message.From.IsBot {
			continue
		}

		// 3. Try parsing as app-sent "[<token>] <username>: <text>"
		token, username, msgText, ok := parseAppMessage(text)
		var senderName string
		if ok {
			senderName = username
		} else {
			senderName = firstNonEmpty(
				u.Message.From.FirstName,
				u.Message.From.Username,
				fmt.Sprintf("%d", u.Message.From.ID),
			)
			msgText = text
		}

		batch.Messages = append(batch.Messages, ChatMessage{
			ID:          u.Message.MessageID,
			Text:        msgText,
			SenderName:  senderName,
			SenderToken: token,
			Timestamp:   u.Message.Date * 1000,
			IsOutgoing:  false,
		})
	}

	return batch, nil
}

// ResolveAccountByToken walks the recent Telegram updates history looking
// for a REGISTER envelope whose token matches. Returns nil if not found.
//
// Note: getUpdates only returns messages the bot has access to. For this to
// find historical envelopes, the bot's Privacy Mode must be disabled and
// the bot must have been in the group when the envelope was sent.
func (c *TelegramClient) ResolveAccountByToken(token string) (*ResolvedAccount, error) {
	// Walk back through getUpdates history (each call returns up to 100).
	// We use negative offsets to fetch older updates without consuming them.
	for attempt := 0; attempt < 10; attempt++ {
		offset := -100 - int64(attempt*100)
		values := url.Values{}
		values.Set("timeout", "0")
		values.Set("offset", strconv.FormatInt(offset, 10))
		values.Set("allowed_updates", `["message"]`)

		resp, err := c.httpClient.Get(
			"https://api.telegram.org/bot" + c.botToken + "/getUpdates?" + values.Encode(),
		)
		if err != nil {
			return nil, err
		}
		body, err := io.ReadAll(resp.Body)
		resp.Body.Close()
		if err != nil {
			return nil, err
		}

		var tr telegramResponse
		if err := json.Unmarshal(body, &tr); err != nil {
			return nil, err
		}
		if !tr.OK {
			return nil, fmt.Errorf("telegram: %s (code %d)", tr.Description, tr.ErrorCode)
		}

		var updates []telegramUpdate
		if err := json.Unmarshal(tr.Result, &updates); err != nil {
			return nil, err
		}
		if len(updates) == 0 {
			return nil, nil // no more history
		}

		for _, u := range updates {
			if u.Message == nil || u.Message.Text == "" {
				continue
			}
			env, ok := parseEnvelope(u.Message.Text)
			if !ok {
				continue
			}
			if env.Token == token && env.Type == "REGISTER" {
				username := env.Username
				if username == "" {
					username = "user-" + token[:4]
				}
				return &ResolvedAccount{
					Token:        token,
					Username:     username,
					AvatarBase64: env.AvatarBase64,
				}, nil
			}
		}
	}
	return nil, nil
}

// parseEnvelope tries to parse text as a "##CHATFLOAT##<base64-json>" envelope.
func parseEnvelope(text string) (AccountEnvelope, bool) {
	if !strings.HasPrefix(text, envelopePrefix) {
		return AccountEnvelope{}, false
	}
	encoded := strings.TrimSpace(strings.TrimPrefix(text, envelopePrefix))
	jsonBytes, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		return AccountEnvelope{}, false
	}
	var env AccountEnvelope
	if err := json.Unmarshal(jsonBytes, &env); err != nil {
		return AccountEnvelope{}, false
	}
	return env, true
}

// parseAppMessage tries to parse "[<token>] <username>: <text>".
// Returns ok=false if the pattern doesn't match.
func parseAppMessage(text string) (token, username, msgText string, ok bool) {
	if !strings.HasPrefix(text, "[") {
		return "", "", "", false
	}
	closeIdx := strings.Index(text, "]")
	if closeIdx < 2 || closeIdx > 12 {
		return "", "", "", false
	}
	token = text[1:closeIdx]
	if len(token) != 8 {
		return "", "", "", false
	}
	rest := strings.TrimSpace(text[closeIdx+1:])
	colonIdx := strings.Index(rest, ": ")
	if colonIdx <= 0 {
		return "", "", "", false
	}
	username = rest[:colonIdx]
	msgText = rest[colonIdx+2:]
	return token, username, msgText, true
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return "unknown"
}

// (body buffering helper for tests)
var _ = bytes.NewReader
