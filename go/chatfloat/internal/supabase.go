package chatfloat

// supabase.go — HTTP client for Supabase REST API (PostgREST) + Storage.
//
// The Go library talks to Supabase directly via REST. No auth flow —
// the anon key is used with RLS policies that allow public read/insert
// on accounts and messages tables, and public read/upload on the
// 'avatars' storage bucket.
//
// PostgREST endpoints used:
//   GET  /rest/v1/accounts?token=eq.X        -> resolve token to account
//   POST /rest/v1/accounts                   -> register new account (upsert)
//   PATCH /rest/v1/accounts?token=eq.X       -> update username/avatar
//   GET  /rest/v1/messages?order=created_at  -> fetch all messages
//   POST /rest/v1/messages                   -> insert new message
//
// Storage endpoints used:
//   POST   /storage/v1/object/avatars/{token}.png  -> upload avatar
//   GET    /storage/v1/object/public/avatars/{token}.png -> public URL
//   DELETE /storage/v1/object/avatars/{token}.png  -> remove avatar

import (
        "bytes"
        "encoding/json"
        "fmt"
        "io"
        "net/http"
        "net/url"
        "strings"
        "time"
)

// SupabaseClient is safe for concurrent use.
type SupabaseClient struct {
        baseURL  string // e.g. "https://dbvmkochemjmeyookgsu.supabase.co"
        apiKey   string // anon key
        httpCli  *http.Client
}

func NewSupabaseClient(baseURL, apiKey string) *SupabaseClient {
        return &SupabaseClient{
                baseURL: strings.TrimRight(baseURL, "/"),
                apiKey:  apiKey,
                httpCli: &http.Client{Timeout: 30 * time.Second},
        }
}

// restURL builds a /rest/v1/<table> URL with optional query params.
func (c *SupabaseClient) restURL(table string, params ...string) string {
        u := c.baseURL + "/rest/v1/" + table
        if len(params) > 0 {
                u += "?" + strings.Join(params, "&")
        }
        return u
}

// do performs an HTTP request with the standard Supabase headers.
func (c *SupabaseClient) do(method, url string, body interface{}) (*http.Response, error) {
        var reqBody io.Reader
        if body != nil {
                data, err := json.Marshal(body)
                if err != nil {
                        return nil, err
                }
                reqBody = bytes.NewReader(data)
        }
        req, err := http.NewRequest(method, url, reqBody)
        if err != nil {
                return nil, err
        }
        req.Header.Set("apikey", c.apiKey)
        req.Header.Set("Authorization", "Bearer "+c.apiKey)
        if body != nil {
                req.Header.Set("Content-Type", "application/json")
        }
        return c.httpCli.Do(req)
}

// ============================================================
// Accounts
// ============================================================

// dbAccount matches the Supabase accounts table schema.
type dbAccount struct {
        Token        string     `json:"token"`
        Username     string     `json:"username"`
        AvatarBase64 string     `json:"avatar_base64,omitempty"`
        AvatarURL    string     `json:"avatar_url,omitempty"`
        CreatedAt    *time.Time `json:"created_at,omitempty"`
}

// GetAccountByToken fetches an account by token. Returns nil if not found.
func (c *SupabaseClient) GetAccountByToken(token string) (*Account, error) {
        u := c.restURL("accounts", "token=eq."+url.QueryEscape(token), "limit=1")
        resp, err := c.do("GET", u, nil)
        if err != nil {
                return nil, err
        }
        defer resp.Body.Close()
        if resp.StatusCode != 200 {
                body, _ := io.ReadAll(resp.Body)
                return nil, fmt.Errorf("supabase: %d %s", resp.StatusCode, string(body))
        }
        var rows []dbAccount
        if err := json.NewDecoder(resp.Body).Decode(&rows); err != nil {
                return nil, err
        }
        if len(rows) == 0 {
                return nil, nil
        }
        return &Account{
                Token:        rows[0].Token,
                Username:     rows[0].Username,
                AvatarBase64: rows[0].AvatarBase64,
                AvatarURL:    rows[0].AvatarURL,
        }, nil
}

// InsertAccount creates a new account row (upsert on conflict).
func (c *SupabaseClient) InsertAccount(acc *Account) error {
        u := c.restURL("accounts")
        body, err := json.Marshal(acc)
        if err != nil {
                return err
        }
        req, err := http.NewRequest("POST", u, bytes.NewReader(body))
        if err != nil {
                return err
        }
        c.setHeaders(req)
        req.Header.Set("Prefer", "resolution=merge-duplicates,return=minimal")
        resp, err := c.httpCli.Do(req)
        if err != nil {
                return err
        }
        defer resp.Body.Close()
        if resp.StatusCode >= 300 {
                b, _ := io.ReadAll(resp.Body)
                return fmt.Errorf("supabase insert account: %d %s", resp.StatusCode, string(b))
        }
        return nil
}

// UpdateAccount patches an existing account (token, username, avatar).
func (c *SupabaseClient) UpdateAccount(token string, fields map[string]interface{}) error {
        body, err := json.Marshal(fields)
        if err != nil {
                return err
        }
        u := c.restURL("accounts", "token=eq."+url.QueryEscape(token))
        req, err := http.NewRequest("PATCH", u, bytes.NewReader(body))
        if err != nil {
                return err
        }
        c.setHeaders(req)
        req.Header.Set("Prefer", "return=minimal")
        resp, err := c.httpCli.Do(req)
        if err != nil {
                return err
        }
        defer resp.Body.Close()
        if resp.StatusCode >= 300 {
                b, _ := io.ReadAll(resp.Body)
                return fmt.Errorf("supabase update account: %d %s", resp.StatusCode, string(b))
        }
        return nil
}

// ============================================================
// Messages
// ============================================================

// dbMessage matches the Supabase messages table schema.
type dbMessage struct {
        ID          *int64     `json:"id,omitempty"`
        Text        string     `json:"text"`
        SenderToken *string    `json:"sender_token,omitempty"`
        SenderName  string     `json:"sender_name"`
        CreatedAt   *time.Time `json:"created_at,omitempty"`
        MediaURL    *string    `json:"media_url,omitempty"`
        MediaType   *string    `json:"media_type,omitempty"`
}

// InsertMessage creates a new message row and returns the inserted row.
func (c *SupabaseClient) InsertMessage(text, senderToken, senderName string) (*ChatMessage, error) {
        body := dbMessage{
                Text:        text,
                SenderToken: &senderToken,
                SenderName:  senderName,
        }
        bodyBytes, err := json.Marshal(body)
        if err != nil {
                return nil, err
        }
        u := c.restURL("messages")
        req, err := http.NewRequest("POST", u, bytes.NewReader(bodyBytes))
        if err != nil {
                return nil, err
        }
        c.setHeaders(req)
        req.Header.Set("Prefer", "return=representation")
        resp, err := c.httpCli.Do(req)
        if err != nil {
                return nil, err
        }
        defer resp.Body.Close()
        if resp.StatusCode >= 300 {
                b, _ := io.ReadAll(resp.Body)
                return nil, fmt.Errorf("supabase insert message: %d %s", resp.StatusCode, string(b))
        }
        var rows []dbMessage
        if err := json.NewDecoder(resp.Body).Decode(&rows); err != nil {
                return nil, err
        }
        if len(rows) == 0 {
                return nil, fmt.Errorf("supabase: no row returned")
        }
        r := rows[0]
        msg := &ChatMessage{
                Text:        r.Text,
                SenderName:  r.SenderName,
                SenderToken: senderToken,
        }
        if r.ID != nil {
                msg.ID = *r.ID
        }
        if r.CreatedAt != nil {
                msg.Timestamp = r.CreatedAt.UnixMilli()
        }
        return msg, nil
}

// InsertMediaMessage creates a new message with media (image/audio) and
// returns the inserted row.
func (c *SupabaseClient) InsertMediaMessage(text, senderToken, senderName, mediaURL, mediaType string) (*ChatMessage, error) {
        body := dbMessage{
                Text:        text,
                SenderToken: &senderToken,
                SenderName:  senderName,
                MediaURL:    &mediaURL,
                MediaType:   &mediaType,
        }
        bodyBytes, err := json.Marshal(body)
        if err != nil {
                return nil, err
        }
        u := c.restURL("messages")
        req, err := http.NewRequest("POST", u, bytes.NewReader(bodyBytes))
        if err != nil {
                return nil, err
        }
        c.setHeaders(req)
        req.Header.Set("Prefer", "return=representation")
        resp, err := c.httpCli.Do(req)
        if err != nil {
                return nil, err
        }
        defer resp.Body.Close()
        if resp.StatusCode >= 300 {
                b, _ := io.ReadAll(resp.Body)
                return nil, fmt.Errorf("supabase insert media message: %d %s", resp.StatusCode, string(b))
        }
        var rows []dbMessage
        if err := json.NewDecoder(resp.Body).Decode(&rows); err != nil {
                return nil, err
        }
        if len(rows) == 0 {
                return nil, fmt.Errorf("supabase: no row returned")
        }
        r := rows[0]
        msg := &ChatMessage{
                Text:        r.Text,
                SenderName:  r.SenderName,
                SenderToken: senderToken,
                MediaURL:    mediaURL,
                MediaType:   mediaType,
        }
        if r.ID != nil {
                msg.ID = *r.ID
        }
        if r.CreatedAt != nil {
                msg.Timestamp = r.CreatedAt.UnixMilli()
        }
        return msg, nil
}

// GetMessages fetches all messages ordered by created_at ascending.
// Enriches each message with the sender's avatar URL from the accounts table.
func (c *SupabaseClient) GetMessages(sinceID int64) ([]ChatMessage, error) {
        params := []string{"order=created_at.asc", "select=id,text,sender_token,sender_name,created_at,media_url,media_type"}
        if sinceID > 0 {
                params = append(params, fmt.Sprintf("id=gt.%d", sinceID))
        }
        u := c.restURL("messages", params...)
        resp, err := c.do("GET", u, nil)
        if err != nil {
                return nil, err
        }
        defer resp.Body.Close()
        if resp.StatusCode != 200 {
                b, _ := io.ReadAll(resp.Body)
                return nil, fmt.Errorf("supabase get messages: %d %s", resp.StatusCode, string(b))
        }
        var rows []dbMessage
        if err := json.NewDecoder(resp.Body).Decode(&rows); err != nil {
                return nil, err
        }

        // Batch-fetch all sender accounts to enrich with avatars
        tokens := make(map[string]bool)
        for _, r := range rows {
                if r.SenderToken != nil {
                        tokens[*r.SenderToken] = true
                }
        }
        avatars := make(map[string]string)
        usernames := make(map[string]string)
        for token := range tokens {
                acc, err := c.GetAccountByToken(token)
                if err == nil && acc != nil {
                        avatars[token] = acc.AvatarURL
                        usernames[token] = acc.Username
                }
        }

        msgs := make([]ChatMessage, 0, len(rows))
        for _, r := range rows {
                m := ChatMessage{
                        Text:       r.Text,
                        SenderName: r.SenderName,
                }
                if r.ID != nil {
                        m.ID = *r.ID
                }
                if r.CreatedAt != nil {
                        m.Timestamp = r.CreatedAt.UnixMilli()
                }
                if r.SenderToken != nil {
                        m.SenderToken = *r.SenderToken
                        if avatar, ok := avatars[*r.SenderToken]; ok {
                                m.SenderAvatar = avatar
                        }
                        if uname, ok := usernames[*r.SenderToken]; ok && uname != "" {
                                m.SenderName = uname
                        }
                }
                if r.MediaURL != nil {
                        m.MediaURL = *r.MediaURL
                }
                if r.MediaType != nil {
                        m.MediaType = *r.MediaType
                }
                msgs = append(msgs, m)
        }
        return msgs, nil
}

// ============================================================
// Storage (media bucket — images + audio)
// ============================================================

// UploadMedia uploads raw bytes to the 'media' bucket and returns the
// public URL. contentType should be "image/jpeg", "audio/aac", etc.
func (c *SupabaseClient) UploadMedia(filename, contentType string, data []byte) (string, error) {
        u := c.baseURL + "/storage/v1/object/media/" + filename
        req, err := http.NewRequest("POST", u, bytes.NewReader(data))
        if err != nil {
                return "", err
        }
        c.setHeaders(req)
        req.Header.Set("Content-Type", contentType)
        req.Header.Set("x-upsert", "true")
        resp, err := c.httpCli.Do(req)
        if err != nil {
                return "", err
        }
        defer resp.Body.Close()
        if resp.StatusCode >= 300 {
                b, _ := io.ReadAll(resp.Body)
                return "", fmt.Errorf("supabase upload media: %d %s", resp.StatusCode, string(b))
        }
        return c.baseURL + "/storage/v1/object/public/media/" + filename, nil
}

// ============================================================
// Storage (avatars bucket)
// ============================================================

// UploadAvatar uploads raw PNG bytes to avatars/{token}.png and returns the
// public URL.
func (c *SupabaseClient) UploadAvatar(token string, pngBytes []byte) (string, error) {
        objectPath := token + ".png"
        u := c.baseURL + "/storage/v1/object/avatars/" + objectPath
        req, err := http.NewRequest("POST", u, bytes.NewReader(pngBytes))
        if err != nil {
                return "", err
        }
        c.setHeaders(req)
        req.Header.Set("Content-Type", "image/png")
        req.Header.Set("x-upsert", "true") // overwrite if exists
        resp, err := c.httpCli.Do(req)
        if err != nil {
                return "", err
        }
        defer resp.Body.Close()
        if resp.StatusCode >= 300 {
                b, _ := io.ReadAll(resp.Body)
                return "", fmt.Errorf("supabase upload avatar: %d %s", resp.StatusCode, string(b))
        }
        // Public URL is deterministic
        return c.baseURL + "/storage/v1/object/public/avatars/" + objectPath, nil
}

// DeleteAvatar removes the avatar object from storage.
func (c *SupabaseClient) DeleteAvatar(token string) error {
        objectPath := token + ".png"
        u := c.baseURL + "/storage/v1/object/avatars/" + objectPath
        req, err := http.NewRequest("DELETE", u, nil)
        if err != nil {
                return err
        }
        c.setHeaders(req)
        resp, err := c.httpCli.Do(req)
        if err != nil {
                return err
        }
        resp.Body.Close()
        // 404 is OK (avatar didn't exist)
        if resp.StatusCode >= 300 && resp.StatusCode != 404 {
                return fmt.Errorf("supabase delete avatar: %d", resp.StatusCode)
        }
        return nil
}

// setHeaders applies the standard Supabase headers to req.
func (c *SupabaseClient) setHeaders(req *http.Request) {
        req.Header.Set("apikey", c.apiKey)
        req.Header.Set("Authorization", "Bearer "+c.apiKey)
        req.Header.Set("Content-Type", "application/json")
}
