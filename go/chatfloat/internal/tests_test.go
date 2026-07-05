//go:build integration

package chatfloat

// tests_test.go exercises the full Go API against the real Supabase project.
// Run with: go test -v -tags integration -timeout 60s ./...

import (
	"os"
	"strings"
	"testing"
)

const (
	testSupabaseURL = "https://dbvmkochemjmeyookgsu.supabase.co"
	testSupabaseKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImRidm1rb2NoZW1qbWV5b29rZ3N1Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3Nzk1OTA3MjIsImV4cCI6MjA5NTE2NjcyMn0.oAYv4hqQfnltl5sDmSTRwlkBfBeapCfxj7xaXyDqt78"
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
		SupabaseURL: testSupabaseURL,
		SupabaseKey: testSupabaseKey,
		DataDir:     dir,
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
		SupabaseURL: testSupabaseURL,
		SupabaseKey: testSupabaseKey,
		DataDir:     dir,
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

	// Logout
	if err := LogoutAPI(); err != nil {
		t.Fatal(err)
	}
	if IsLoggedInAPI() {
		t.Error("expected logged out after Logout")
	}

	// Login with the token — should resolve from Supabase
	if err := LoginWithTokenAPI(token); err != nil {
		t.Fatalf("LoginWithTokenAPI(%s): %v", token, err)
	}
	acc = GetAccountAPI()
	if acc == nil || acc.Token != token {
		t.Errorf("after login, account = %v, expected token %s", acc, token)
	}
	if acc != nil && acc.Username != username {
		t.Errorf("after login, username = %q, expected %q", acc.Username, username)
	}
	t.Logf("Login OK: %+v", acc)

	_ = LogoutAPI()
}

func TestIntegration_SendMessageAndGetMessages(t *testing.T) {
	dir := tempDataDir(t)
	if err := ConfigureAPI(Config{
		SupabaseURL: testSupabaseURL,
		SupabaseKey: testSupabaseKey,
		DataDir:     dir,
	}); err != nil {
		t.Fatal(err)
	}

	token, err := CreateAccountAPI("sender_" + strings.ToLower(generateToken()))
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("Sender token: %s", token)

	// Send a message
	msgText := "Hello from Go test"
	if err := SendMessageAPI(msgText); err != nil {
		t.Fatalf("SendMessageAPI: %v", err)
	}

	// Fetch messages — should include our sent message
	msgs, err := GetMessagesAPI()
	if err != nil {
		t.Fatalf("GetMessagesAPI: %v", err)
	}
	if len(msgs) == 0 {
		t.Fatal("expected at least 1 message")
	}

	// Find our message
	var found *ChatMessage
	for i := range msgs {
		if msgs[i].Text == msgText && msgs[i].SenderToken == token {
			found = &msgs[i]
			break
		}
	}
	if found == nil {
		t.Errorf("sent message not found in results. Messages: %+v", msgs)
	} else {
		t.Logf("Found sent message: id=%d text=%q outgoing=%v",
			found.ID, found.Text, found.IsOutgoing)
		if !found.IsOutgoing {
			t.Error("expected our message to be marked outgoing")
		}
	}

	_ = LogoutAPI()
}

func TestIntegration_UpdateUsernameAndAvatar(t *testing.T) {
	dir := tempDataDir(t)
	if err := ConfigureAPI(Config{
		SupabaseURL: testSupabaseURL,
		SupabaseKey: testSupabaseKey,
		DataDir:     dir,
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

	// Update avatar (tiny 1x1 PNG, base64)
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

func TestIntegration_PersistenceAcrossConfigure(t *testing.T) {
	dir := tempDataDir(t)

	// First session: create account
	if err := ConfigureAPI(Config{
		SupabaseURL: testSupabaseURL,
		SupabaseKey: testSupabaseKey,
		DataDir:     dir,
	}); err != nil {
		t.Fatal(err)
	}
	token, err := CreateAccountAPI("persist_" + strings.ToLower(generateToken()))
	if err != nil {
		t.Fatal(err)
	}
	originalUsername := GetAccountAPI().Username

	// Second session: reconfigure with same dataDir — should auto-login
	if err := ConfigureAPI(Config{
		SupabaseURL: testSupabaseURL,
		SupabaseKey: testSupabaseKey,
		DataDir:     dir,
	}); err != nil {
		t.Fatal(err)
	}
	if !IsLoggedInAPI() {
		t.Fatal("expected auto-login after reconfigure with persisted account")
	}
	acc := GetAccountAPI()
	if acc.Token != token {
		t.Errorf("after reconfigure, token = %s, expected %s", acc.Token, token)
	}
	if acc.Username != originalUsername {
		t.Errorf("after reconfigure, username = %q, expected %q", acc.Username, originalUsername)
	}
	t.Logf("Persistence OK: %+v", acc)

	_ = LogoutAPI()
}
