package chatfloat

// api.go is the public Go API for the ChatFloat backend.
//
// All state is held in a package-level Session singleton. Functions exposed
// to Go callers use the `...API` suffix; the C exports (exports.go) are
// thin wrappers around these.

import (
        "crypto/rand"
        "errors"
        "fmt"
        "os"
        "path/filepath"
        "sync"
)

// Session holds the in-memory state of the current user session.
type Session struct {
        mu          sync.Mutex
        account     *Account
        supabase    *SupabaseClient
        dataDir     string

        // Cache: messages and account lookups to avoid redundant HTTP requests
        cachedMessages   []ChatMessage
        accountCache     map[string]*Account // token -> Account (includes avatar URL)
        lastMessageID    int64
        messagesLoaded   bool
}

var globalSession = &Session{
        accountCache: make(map[string]*Account),
}

// Config holds the Supabase configuration.
type Config struct {
        SupabaseURL string // e.g. "https://dbvmkochemjmeyookgsu.supabase.co"
        SupabaseKey string // anon key
        DataDir     string // directory for persistent storage (account.json)
}

// ErrNotLoggedIn is returned when an operation requires a logged-in account.
var ErrNotLoggedIn = errors.New("not logged in")

// ConfigureAPI sets up the Supabase client. Must be called once at app
// startup. If a persisted account exists in <dataDir>/account.json, it is
// loaded automatically.
func ConfigureAPI(cfg Config) error {
        globalSession.mu.Lock()
        defer globalSession.mu.Unlock()

        if cfg.SupabaseURL == "" || cfg.SupabaseKey == "" {
                return errors.New("supabase URL and key are required")
        }
        globalSession.supabase = NewSupabaseClient(cfg.SupabaseURL, cfg.SupabaseKey)
        globalSession.dataDir = cfg.DataDir

        // Try to load persisted account
        if cfg.DataDir != "" {
                acc, err := LoadAccount(cfg.DataDir)
                if err == nil && acc != nil {
                        globalSession.account = acc
                }
        }
        return nil
}

// CreateAccountAPI generates a new 8-char token, registers it in Supabase,
// persists it locally, and returns the token.
func CreateAccountAPI(username string) (string, error) {
        globalSession.mu.Lock()
        defer globalSession.mu.Unlock()

        if globalSession.supabase == nil {
                return "", errors.New("not configured; call ConfigureAPI first")
        }
        if username == "" {
                return "", errors.New("username is required")
        }
        if len(username) > 30 {
                return "", errors.New("username too long (max 30 chars)")
        }

        token := generateToken()
        acc := &Account{
                Token:    token,
                Username: username,
        }

        // Insert into Supabase
        if err := globalSession.supabase.InsertAccount(acc); err != nil {
                return "", fmt.Errorf("register account: %w", err)
        }

        // Persist locally
        if globalSession.dataDir != "" {
                if err := SaveAccount(globalSession.dataDir, acc); err != nil {
                        return "", fmt.Errorf("save account: %w", err)
                }
        }
        globalSession.account = acc
        return token, nil
}

// LoginWithTokenAPI validates the given token against Supabase and restores
// the account locally.
func LoginWithTokenAPI(token string) error {
        globalSession.mu.Lock()
        defer globalSession.mu.Unlock()

        if globalSession.supabase == nil {
                return errors.New("not configured; call ConfigureAPI first")
        }
        if len(token) != 8 {
                return errors.New("token must be 8 characters")
        }

        resolved, err := globalSession.supabase.GetAccountByToken(token)
        if err != nil {
                return fmt.Errorf("lookup token: %w", err)
        }
        if resolved == nil {
                return errors.New("token not found")
        }

        // Persist locally
        if globalSession.dataDir != "" {
                if err := SaveAccount(globalSession.dataDir, resolved); err != nil {
                        return fmt.Errorf("save account: %w", err)
                }
        }
        globalSession.account = resolved
        return nil
}

// IsLoggedInAPI returns whether there is a current account.
func IsLoggedInAPI() bool {
        globalSession.mu.Lock()
        defer globalSession.mu.Unlock()
        return globalSession.account != nil
}

// GetAccountAPI returns the current account, or nil if not logged in.
func GetAccountAPI() *Account {
        globalSession.mu.Lock()
        defer globalSession.mu.Unlock()
        if globalSession.account == nil {
                return nil
        }
        cp := *globalSession.account
        return &cp
}

// UpdateUsernameAPI changes the username locally and in Supabase.
func UpdateUsernameAPI(newUsername string) error {
        globalSession.mu.Lock()
        defer globalSession.mu.Unlock()

        if globalSession.account == nil {
                return ErrNotLoggedIn
        }
        if newUsername == "" {
                return errors.New("username is required")
        }
        if globalSession.supabase == nil {
                return errors.New("not configured")
        }

        if err := globalSession.supabase.UpdateAccount(globalSession.account.Token, map[string]interface{}{
                "username": newUsername,
        }); err != nil {
                return err
        }
        globalSession.account.Username = newUsername
        if globalSession.dataDir != "" {
                _ = SaveAccount(globalSession.dataDir, globalSession.account)
        }
        return nil
}

// UpdateAvatarAPI receives raw PNG bytes, uploads them to Supabase Storage
// (avatars bucket, public), and stores the resulting URL in the accounts
// table. Pass nil/empty bytes to remove the avatar.
func UpdateAvatarAPI(avatarPNG []byte) error {
        globalSession.mu.Lock()
        defer globalSession.mu.Unlock()

        if globalSession.account == nil {
                return ErrNotLoggedIn
        }
        if globalSession.supabase == nil {
                return errors.New("not configured")
        }

        token := globalSession.account.Token

        // Empty input -> remove avatar
        if len(avatarPNG) == 0 {
                _ = globalSession.supabase.DeleteAvatar(token)
                if err := globalSession.supabase.UpdateAccount(token, map[string]interface{}{
                        "avatar_url": "",
                }); err != nil {
                        return err
                }
                globalSession.account.AvatarURL = ""
                if globalSession.dataDir != "" {
                        _ = SaveAccount(globalSession.dataDir, globalSession.account)
                }
                return nil
        }

        // Upload to storage
        publicURL, err := globalSession.supabase.UploadAvatar(token, avatarPNG)
        if err != nil {
                return fmt.Errorf("upload avatar: %w", err)
        }

        // Update account row with the URL
        if err := globalSession.supabase.UpdateAccount(token, map[string]interface{}{
                "avatar_url": publicURL,
        }); err != nil {
                return err
        }
        globalSession.account.AvatarURL = publicURL
        if globalSession.dataDir != "" {
                _ = SaveAccount(globalSession.dataDir, globalSession.account)
        }
        return nil
}

// SendMessageAPI inserts a new message into Supabase and adds it to the
// local cache immediately (so the sender sees it without waiting for the
// Realtime listener to echo it back).
func SendMessageAPI(text string) error {
        globalSession.mu.Lock()
        defer globalSession.mu.Unlock()

        if globalSession.account == nil {
                return ErrNotLoggedIn
        }
        if globalSession.supabase == nil {
                return errors.New("not configured")
        }
        if text == "" {
                return errors.New("text is required")
        }

        token := globalSession.account.Token
        msg, err := globalSession.supabase.InsertMessage(text, token, globalSession.account.Username)
        if err != nil {
                return err
        }
        msg.IsOutgoing = true

        // Add to local cache immediately
        globalSession.cachedMessages = append(globalSession.cachedMessages, *msg)
        if msg.ID > globalSession.lastMessageID {
                globalSession.lastMessageID = msg.ID
        }
        globalSession.messagesLoaded = true
        return nil
}

// GetMessagesAPI returns cached messages. On each call, also fetches any
// new messages (id > lastMessageID) from Supabase and appends them to the
// cache. This is an efficient polling approach — each call makes at most
// 1 HTTP request that returns only NEW messages (usually 0 or 1 row).
//
// The Supabase Realtime WebSocket approach was attempted but the message
// format didn't work reliably with Supabase v2. Polling with id > last
// is simple, efficient, and works everywhere.
func GetMessagesAPI() ([]ChatMessage, error) {
        globalSession.mu.Lock()
        defer globalSession.mu.Unlock()

        if globalSession.supabase == nil {
                return nil, errors.New("not configured")
        }

        // Fetch new messages since lastMessageID
        newMsgs, err := globalSession.supabase.GetMessages(globalSession.lastMessageID)
        if err != nil {
                // On error, return what we have in cache
                if globalSession.messagesLoaded {
                        out := make([]ChatMessage, len(globalSession.cachedMessages))
                        copy(out, globalSession.cachedMessages)
                        return out, nil
                }
                return nil, err
        }

        if len(newMsgs) > 0 {
                // Enrich new messages
                enrichMessages(newMsgs)

                // Fetch account info for any new senders not in cache
                for _, m := range newMsgs {
                        if m.SenderToken != "" {
                                if _, ok := globalSession.accountCache[m.SenderToken]; !ok {
                                        if acc, err := globalSession.supabase.GetAccountByToken(m.SenderToken); err == nil && acc != nil {
                                                globalSession.accountCache[m.SenderToken] = acc
                                        }
                                }
                        }
                }

                // Re-enrich with updated cache
                enrichMessages(newMsgs)

                // Append to cache
                globalSession.cachedMessages = append(globalSession.cachedMessages, newMsgs...)
                globalSession.lastMessageID = newMsgs[len(newMsgs)-1].ID
                globalSession.messagesLoaded = true
        }

        // Return a copy of the cache
        out := make([]ChatMessage, len(globalSession.cachedMessages))
        copy(out, globalSession.cachedMessages)
        return out, nil
}

// enrichMessages fills in the avatar URL and isOutgoing flag for each message
// using the account cache. Caller must hold globalSession.mu.
func enrichMessages(msgs []ChatMessage) {
        if globalSession.account == nil {
                return
        }
        for i := range msgs {
                if msgs[i].SenderToken == globalSession.account.Token {
                        msgs[i].IsOutgoing = true
                }
                // Enrich with avatar from cache
                if msgs[i].SenderToken != "" {
                        if acc, ok := globalSession.accountCache[msgs[i].SenderToken]; ok {
                                msgs[i].SenderAvatar = acc.AvatarURL
                                if acc.Username != "" {
                                        msgs[i].SenderName = acc.Username
                                }
                        }
                }
        }
}

// LogoutAPI clears the current account and removes the persisted file.
func LogoutAPI() error {
        globalSession.mu.Lock()
        defer globalSession.mu.Unlock()

        globalSession.account = nil
        globalSession.cachedMessages = nil
        globalSession.messagesLoaded = false
        globalSession.lastMessageID = 0
        globalSession.accountCache = make(map[string]*Account)

        if globalSession.dataDir != "" {
                _ = ClearAccount(globalSession.dataDir)
        }
        return nil
}

// ============================================================
// Helpers
// ============================================================

// generateToken returns a random 8-character token using the unambiguous
// alphabet (no I, O, 0, 1).
func generateToken() string {
        const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        buf := make([]byte, 8)
        if _, err := rand.Read(buf); err != nil {
                panic(fmt.Sprintf("crypto/rand failed: %v", err))
        }
        for i, b := range buf {
                buf[i] = alphabet[int(b)%len(alphabet)]
        }
        return string(buf)
}

// accountFile returns the path to account.json inside dataDir.
func accountFile(dataDir string) string {
        return filepath.Join(dataDir, "account.json")
}

// SaveAccount persists the account to <dataDir>/account.json.
func SaveAccount(dataDir string, acc *Account) error {
        if err := os.MkdirAll(dataDir, 0o755); err != nil {
                return err
        }
        return writeJSON(accountFile(dataDir), acc)
}

// LoadAccount reads the persisted account, or returns nil if none exists.
func LoadAccount(dataDir string) (*Account, error) {
        var acc Account
        if err := readJSON(accountFile(dataDir), &acc); err != nil {
                if os.IsNotExist(err) {
                        return nil, nil
                }
                return nil, err
        }
        if acc.Token == "" {
                return nil, nil
        }
        return &acc, nil
}

// ClearAccount removes the persisted account file.
func ClearAccount(dataDir string) error {
        err := os.Remove(accountFile(dataDir))
        if os.IsNotExist(err) {
                return nil
        }
        return err
}
