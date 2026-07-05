//go:build integration

package chatfloat

// avatar_test.go — tests avatar upload with a real image URL.
// Run with: go test -v -tags integration -run TestAvatarUpload ./...

import (
	"io"
	"net/http"
	"testing"
)

// TestAvatarUploadFromURL downloads an image from a URL and uploads it
// as the avatar for a test account. Verifies the avatar URL is set and
// the image is publicly accessible.
func TestAvatarUploadFromURL(t *testing.T) {
	dir := tempDataDir(t)
	if err := ConfigureAPI(Config{
		SupabaseURL: testSupabaseURL,
		SupabaseKey: testSupabaseKey,
		DataDir:     dir,
	}); err != nil {
		t.Fatal(err)
	}

	// Create a test account
	token, err := CreateAccountAPI("avatar_test_" + generateToken()[:4])
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("Created account with token: %s", token)

	// Download the image from the URL
	imageURL := "https://i0.statig.com.br/bancodeimagens/9d/k5/71/9dk571qkftg4fcoyt4e75tw6d.jpg"
	resp, err := http.Get(imageURL)
	if err != nil {
		t.Fatalf("Failed to download image: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("Image download returned status %d", resp.StatusCode)
	}
	imageBytes, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("Failed to read image body: %v", err)
	}
	t.Logf("Downloaded %d bytes from %s", len(imageBytes), imageURL)

	// Upload as avatar
	if err := UpdateAvatarAPI(imageBytes); err != nil {
		t.Fatalf("UpdateAvatarAPI failed: %v", err)
	}

	// Verify the account has an avatar URL
	acc := GetAccountAPI()
	if acc == nil {
		t.Fatal("GetAccountAPI returned nil")
	}
	if acc.AvatarURL == "" {
		t.Fatal("AvatarURL is empty after upload")
	}
	t.Logf("✓ Avatar uploaded successfully!")
	t.Logf("  Token: %s", acc.Token)
	t.Logf("  Username: %s", acc.Username)
	t.Logf("  Avatar URL: %s", acc.AvatarURL)

	// Verify the avatar URL is publicly accessible
	verifyResp, err := http.Get(acc.AvatarURL)
	if err != nil {
		t.Logf("Could not verify avatar URL (non-fatal): %v", err)
	} else {
		defer verifyResp.Body.Close()
		if verifyResp.StatusCode == 200 {
			t.Logf("✓ Avatar URL is publicly accessible (HTTP 200)")
		} else {
			t.Errorf("Avatar URL returned status %d", verifyResp.StatusCode)
		}
	}

	_ = LogoutAPI()
}
