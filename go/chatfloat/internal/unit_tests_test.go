package chatfloat

// unit_tests_test.go contains pure-logic tests that don't need network access.
// Run with: go test -v ./...

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestParseAppMessage(t *testing.T) {
	cases := []struct {
		text           string
		expectOk       bool
		expectToken    string
		expectUsername string
		expectText     string
	}{
		{"[AB12CD34] alice: hello", true, "AB12CD34", "alice", "hello"},
		{"[AB12CD34] alice: hello: with colon", true, "AB12CD34", "alice", "hello: with colon"},
		{"[AB12CD34] alice:", false, "", "", ""},
		{"[TOOLONG] alice: hi", false, "", "", ""},
		{"[] alice: hi", false, "", "", ""},
		{"plain text message", false, "", "", ""},
		{"", false, "", "", ""},
	}
	for _, c := range cases {
		token, username, text, ok := parseAppMessage(c.text)
		if ok != c.expectOk {
			t.Errorf("parseAppMessage(%q): ok=%v, expected %v", c.text, ok, c.expectOk)
			continue
		}
		if ok && (token != c.expectToken || username != c.expectUsername || text != c.expectText) {
			t.Errorf("parseAppMessage(%q) = (%q,%q,%q,%v), expected (%q,%q,%q,%v)",
				c.text, token, username, text, ok,
				c.expectToken, c.expectUsername, c.expectText, c.expectOk)
		}
	}
}

func TestParseEnvelope(t *testing.T) {
	env := AccountEnvelope{
		Type:     "REGISTER",
		Token:    "TEST1234",
		Username: "tester",
	}
	jsonBytes, err := json.Marshal(env)
	if err != nil {
		t.Fatal(err)
	}
	encoded := stdBase64Encode(jsonBytes)
	full := envelopePrefix + encoded

	parsed, ok := parseEnvelope(full)
	if !ok {
		t.Fatalf("parseEnvelope failed for %q", full)
	}
	if parsed.Token != env.Token || parsed.Username != env.Username || parsed.Type != env.Type {
		t.Errorf("parsed = %+v, expected %+v", parsed, env)
	}

	_, ok = parseEnvelope("just some text")
	if ok {
		t.Error("expected parseEnvelope to fail for plain text")
	}
}

func TestGenerateToken(t *testing.T) {
	token := generateToken()
	if len(token) != 8 {
		t.Errorf("token length = %d, expected 8", len(token))
	}
	const valid = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	for _, c := range token {
		if !strings.ContainsRune(valid, c) {
			t.Errorf("token %q contains invalid char %q", token, c)
		}
	}
	// Tokens should be unique (with high probability)
	seen := make(map[string]bool, 100)
	for i := 0; i < 100; i++ {
		tk := generateToken()
		if seen[tk] {
			t.Errorf("token %q generated twice (collision after %d)", tk, i)
		}
		seen[tk] = true
	}
}

func TestMessageRepoAddDedup(t *testing.T) {
	repo := NewMessageRepo("") // no persistence
	repo.Add(ChatMessage{ID: 1, Text: "first"})
	repo.Add(ChatMessage{ID: 2, Text: "second"})
	repo.Add(ChatMessage{ID: 1, Text: "first duplicate"}) // should be ignored
	msgs := repo.All()
	if len(msgs) != 2 {
		t.Fatalf("expected 2 messages, got %d", len(msgs))
	}
	if msgs[0].Text != "first" || msgs[1].Text != "second" {
		t.Errorf("messages = %v", msgs)
	}
}

func TestMessageRepoAddAllDedup(t *testing.T) {
	repo := NewMessageRepo("")
	repo.AddAll([]ChatMessage{
		{ID: 1, Text: "a"},
		{ID: 2, Text: "b"},
	})
	repo.AddAll([]ChatMessage{
		{ID: 2, Text: "b duplicate"}, // ignored
		{ID: 3, Text: "c"},
	})
	msgs := repo.All()
	if len(msgs) != 3 {
		t.Fatalf("expected 3 messages, got %d", len(msgs))
	}
}
