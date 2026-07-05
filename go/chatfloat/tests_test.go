//go:build integration

package chatfloat

// tests_test.go exercises the full Go API against the real Telegram Bot API.
// Run with:
//   go test -v -tags integration -timeout 120s ./...
//
// These tests require network access and use the configured bot token / group.
// They are tagged with `// +build integration` so they don't run by default
// during CI unit-test phases, but DO run when invoked explicitly.

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

const (
	testBotToken = "7594927232:AAFh5T_zZvPtqvoGpyGVO-Kd8uGVDKdp3LE"
	testGroupID  = "-1004384994615"
)

func tempDataDir(t *testing.T) string {
	dir, err := os.MkdirTemp("", "chatfloat-test-*")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = os.RemoveAll(dir) })
	return dir
}

func TestIntegration_Configure(t *testing.T) {
	dir := tempDataDir(t)
	if err := ConfigureAPI(Config{
		BotToken: testBotToken,
		GroupID:  testGroupID,
		DataDir:  dir,
	}); err != nil {
		t.Fatalf("ConfigureAPI failed: %v", err)
	}
	if IsLoggedInAPI() {
		t.Error("expected not logged in after configure")
	}
	_ = LogoutAPI()
}

func TestIntegration_CreateAccountAndLogin(t *testing.T) {
	dir := tempDataDir(t)
	if err := ConfigureAPI(Config{
		BotToken: testBotToken,
		GroupID:  testGroupID,
		DataDir:  dir,
	}); err != nil {
		t.Fatal(err)
	}

	// Create account
	username := "tester_" + strings.ToLower(generateToken())
	token, err := CreateAccountAPI(username)
	if err != nil {
		t.Fatalf("CreateAccountAPI: %v", err)
	}
	if len(token) != 8 {
		t.Errorf("expected 8-char token, got %q", token)
	}
	t.Logf("Created account: username=%s token=%s", username, token)

	// Verify account is current
	acc := GetAccountAPI()
	if acc == nil || acc.Token != token {
		t.Errorf("GetAccountAPI returned %v, expected token %s", acc, token)
	}

	// Persisted?
	if _, err := os.Stat(filepath.Join(dir, "account.json")); err != nil {
		t.Errorf("account.json not created: %v", err)
	}

	// Logout
	if err := LogoutAPI(); err != nil {
		t.Fatal(err)
	}
	if IsLoggedInAPI() {
		t.Error("expected logged out after Logout")
	}

	// Reconfigure (should NOT auto-login because we cleared)
	if err := ConfigureAPI(Config{
		BotToken: testBotToken,
		GroupID:  testGroupID,
		DataDir:  dir,
	}); err != nil {
		t.Fatal(err)
	}
	if IsLoggedInAPI() {
		t.Error("expected not logged in after reconfigure + clear")
	}

	// Wait for the registration envelope to propagate through Telegram
	t.Log("Waiting 3s for Telegram to propagate the registration envelope...")
	time.Sleep(3 * time.Second)

	// Login with the token we just created.
	// If the bot's Privacy Mode is disabled, ResolveAccountByToken will find
	// the envelope and restore the exact username. If Privacy Mode is enabled
	// (default), the token is accepted optimistically with a placeholder
	// username, and the poller will resolve the real one later.
	if err := LoginWithTokenAPI(token); err != nil {
		t.Fatalf("LoginWithTokenAPI(%s): %v", token, err)
	}

	// Verify the account is restored
	acc = GetAccountAPI()
	if acc == nil || acc.Token != token {
		t.Errorf("after login, account = %v, expected token %s", acc, token)
	}
	t.Logf("Login OK: %+v", acc)

	_ = LogoutAPI()
}

func TestIntegration_SendMessageAndGetMessages(t *testing.T) {
	dir := tempDataDir(t)
	if err := ConfigureAPI(Config{
		BotToken: testBotToken,
		GroupID:  testGroupID,
		DataDir:  dir,
	}); err != nil {
		t.Fatal(err)
	}

	token, err := CreateAccountAPI("sender_" + strings.ToLower(generateToken()))
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("Sender token: %s", token)

	// Send a message
	msgText := "Hello from Go test at " + time.Now().Format(time.RFC3339)
	if err := SendMessageAPI(msgText); err != nil {
		t.Fatalf("SendMessageAPI: %v", err)
	}

	// Verify it's in the cache
	msgs := GetMessagesAPI()
	if len(msgs) == 0 {
		t.Fatal("expected at least 1 message in cache")
	}
	last := msgs[len(msgs)-1]
	if last.Text != msgText {
		t.Errorf("last message text = %q, expected %q", last.Text, msgText)
	}
	if !last.IsOutgoing {
		t.Error("expected last message to be outgoing")
	}
	if last.SenderToken != token {
		t.Errorf("last message sender token = %q, expected %q", last.SenderToken, token)
	}
	t.Logf("Message sent: id=%d text=%q", last.ID, last.Text)

	// Persisted?
	if _, err := os.Stat(filepath.Join(dir, "messages.json")); err != nil {
		t.Errorf("messages.json not created: %v", err)
	}

	// Verify JSON serialization shape
	data, _ := json.Marshal(msgs)
	t.Logf("Messages JSON: %s", string(data))

	_ = LogoutAPI()
}

func TestIntegration_UpdateUsernameAndAvatar(t *testing.T) {
	dir := tempDataDir(t)
	if err := ConfigureAPI(Config{
		BotToken: testBotToken,
		GroupID:  testGroupID,
		DataDir:  dir,
	}); err != nil {
		t.Fatal(err)
	}

	_, err := CreateAccountAPI("original_" + strings.ToLower(generateToken()))
	if err != nil {
		t.Fatal(err)
	}

	// Update username
	newName := "newname_" + strings.ToLower(generateToken())
	if err := UpdateUsernameAPI(newName); err != nil {
		t.Fatalf("UpdateUsernameAPI: %v", err)
	}
	acc := GetAccountAPI()
	if acc == nil || acc.Username != newName {
		t.Errorf("username not updated: %+v", acc)
	}
	t.Logf("Username updated to: %s", acc.Username)

	// Update avatar (tiny 1x1 PNG, base64-encoded)
	png := "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="
	if err := UpdateAvatarAPI(png); err != nil {
		t.Fatalf("UpdateAvatarAPI: %v", err)
	}
	acc = GetAccountAPI()
	if acc == nil || acc.AvatarBase64 != png {
		t.Errorf("avatar not updated: %+v", acc)
	}
	t.Log("Avatar updated OK")

	_ = LogoutAPI()
}
