package chatfloat

// This file is the public Go API for the ChatFloat backend.
// It is consumed by:
//   1. The C-shared library exports (exports.go) — called from Android via JNI
//   2. The Go test suite (tests_test.go) — exercises every operation
//   3. Any future Go client (CLI, server, etc.)
//
// All state is held in a package-level Session singleton. Functions exposed
// to Go callers use the `...API` suffix; the C exports (exports.go) are
// thin wrappers around these.

import (
	"errors"
	"fmt"
	"sync"
)

// Session holds the in-memory state of the current user session.
// All methods are safe for concurrent use.
type Session struct {
	mu          sync.Mutex
	account     *Account
	telegram    *TelegramClient
	messageRepo *MessageRepo
	poller      *Poller
}

// Account represents a user identity (token + username + optional avatar).
type Account struct {
	Token        string `json:"token"`
	Username     string `json:"username"`
	AvatarBase64 string `json:"avatar_base64,omitempty"`
}

// ChatMessage is a single chat message, either outgoing or incoming.
type ChatMessage struct {
	ID           int64  `json:"id"`
	Text         string `json:"text"`
	SenderName   string `json:"sender_name"`
	SenderToken  string `json:"sender_token,omitempty"`
	SenderAvatar string `json:"sender_avatar,omitempty"`
	Timestamp    int64  `json:"timestamp"`
	IsOutgoing   bool   `json:"is_outgoing"`
}

var globalSession = &Session{}

// Config holds the Telegram bot configuration.
type Config struct {
	BotToken string
	GroupID  string
	DataDir  string // directory for persistent storage (account.json, messages.json)
}

// ErrNotLoggedIn is returned when an operation requires a logged-in account.
var ErrNotLoggedIn = errors.New("not logged in")

// ConfigureAPI sets up the Telegram client with the given config.
// Must be called once at app startup before any other operation.
func ConfigureAPI(cfg Config) error {
	globalSession.mu.Lock()
	defer globalSession.mu.Unlock()

	tc, err := NewTelegramClient(cfg.BotToken, cfg.GroupID)
	if err != nil {
		return fmt.Errorf("telegram client: %w", err)
	}

	globalSession.telegram = tc
	globalSession.messageRepo = NewMessageRepo(cfg.DataDir)

	// Try to load persisted account
	acc, err := LoadAccount(cfg.DataDir)
	if err == nil && acc != nil {
		globalSession.account = acc
		globalSession.ensurePoller()
	}

	return nil
}

// CreateAccountAPI generates a new 8-char token, registers it with the
// Telegram group (via an envelope), persists it locally, and returns the token.
func CreateAccountAPI(username string) (string, error) {
	globalSession.mu.Lock()
	defer globalSession.mu.Unlock()

	if globalSession.telegram == nil {
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

	// Persist locally first
	if err := SaveAccount(globalSession.messageRepo.dataDir, acc); err != nil {
		return "", fmt.Errorf("save account: %w", err)
	}
	globalSession.account = acc

	// Publish registration envelope (best-effort)
	_ = globalSession.telegram.RegisterAccount(token, username, "")

	// Start the poller if not running
	globalSession.ensurePoller()

	return token, nil
}

// LoginWithTokenAPI validates the given token against the Telegram group
// history and restores the account locally.
//
// If the bot's Privacy Mode is enabled (or the envelope is too old to be in
// the recent history), this falls back to an "optimistic" login: the token
// is accepted locally with a placeholder username, and the poller will
// resolve the real username + avatar when it sees the next REGISTER envelope
// for this token.
func LoginWithTokenAPI(token string) error {
	globalSession.mu.Lock()
	defer globalSession.mu.Unlock()

	if globalSession.telegram == nil {
		return errors.New("not configured; call ConfigureAPI first")
	}
	if len(token) != 8 {
		return errors.New("token must be 8 characters")
	}

	resolved, err := globalSession.telegram.ResolveAccountByToken(token)
	if err != nil {
		return fmt.Errorf("resolve token: %w", err)
	}

	var acc *Account
	if resolved != nil {
		acc = &Account{
			Token:        resolved.Token,
			Username:     resolved.Username,
			AvatarBase64: resolved.AvatarBase64,
		}
	} else {
		// Fallback: optimistic login. The poller will fill in the real
		// username + avatar when it sees the next REGISTER envelope.
		acc = &Account{
			Token:    token,
			Username: "user-" + token[:4],
		}
	}

	if err := SaveAccount(globalSession.messageRepo.dataDir, acc); err != nil {
		return fmt.Errorf("save account: %w", err)
	}
	globalSession.account = acc
	globalSession.ensurePoller()
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
	// Return a copy
	cp := *globalSession.account
	return &cp
}

// UpdateUsernameAPI changes the username locally and re-publishes the registration.
func UpdateUsernameAPI(newUsername string) error {
	globalSession.mu.Lock()
	defer globalSession.mu.Unlock()

	if globalSession.account == nil {
		return ErrNotLoggedIn
	}
	if newUsername == "" {
		return errors.New("username is required")
	}

	globalSession.account.Username = newUsername
	if err := SaveAccount(globalSession.messageRepo.dataDir, globalSession.account); err != nil {
		return err
	}
	_ = globalSession.telegram.RegisterAccount(
		globalSession.account.Token,
		newUsername,
		globalSession.account.AvatarBase64,
	)
	return nil
}

// UpdateAvatarAPI changes the avatar (base64 PNG) locally and publishes an update.
func UpdateAvatarAPI(avatarBase64 string) error {
	globalSession.mu.Lock()
	defer globalSession.mu.Unlock()

	if globalSession.account == nil {
		return ErrNotLoggedIn
	}

	globalSession.account.AvatarBase64 = avatarBase64
	if err := SaveAccount(globalSession.messageRepo.dataDir, globalSession.account); err != nil {
		return err
	}
	_ = globalSession.telegram.PublishAvatarUpdate(globalSession.account.Token, avatarBase64)
	return nil
}

// SendMessageAPI sends a chat message to the group as the bot, prefixed with
// the sender's token + username. The message is added to the local cache.
func SendMessageAPI(text string) error {
	globalSession.mu.Lock()
	defer globalSession.mu.Unlock()

	if globalSession.account == nil {
		return ErrNotLoggedIn
	}
	if text == "" {
		return errors.New("text is required")
	}

	msg, err := globalSession.telegram.SendMessage(
		text,
		globalSession.account.Token,
		globalSession.account.Username,
	)
	if err != nil {
		return err
	}
	if msg != nil {
		globalSession.messageRepo.Add(*msg)
	}
	return nil
}

// GetMessagesAPI returns all cached messages, oldest first.
func GetMessagesAPI() []ChatMessage {
	globalSession.mu.Lock()
	defer globalSession.mu.Unlock()
	if globalSession.messageRepo == nil {
		return nil
	}
	return globalSession.messageRepo.All()
}

// LogoutAPI clears the current account and persisted data.
func LogoutAPI() error {
	globalSession.mu.Lock()
	defer globalSession.mu.Unlock()

	if globalSession.poller != nil {
		globalSession.poller.Stop()
		globalSession.poller = nil
	}
	globalSession.account = nil
	if globalSession.messageRepo != nil {
		globalSession.messageRepo.Clear()
	}
	if globalSession.messageRepo != nil {
		return ClearAccount(globalSession.messageRepo.dataDir)
	}
	return nil
}

// ensurePoller starts the long-polling loop if not already running.
// Caller must hold globalSession.mu.
func (s *Session) ensurePoller() {
	if s.poller != nil {
		return
	}
	if s.telegram == nil || s.account == nil {
		return
	}
	s.poller = NewPoller(s.telegram, s.messageRepo, s.account.Token)
	go s.poller.Run()
}
