package chatfloat

import (
	"crypto/rand"
	"fmt"
	"os"
	"path/filepath"
)

// generateToken returns a random 8-character token using the unambiguous
// alphabet (no I, O, 0, 1). Example: "AB12CD34".
func generateToken() string {
	const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
	buf := make([]byte, 8)
	if _, err := rand.Read(buf); err != nil {
		// Should never happen in practice
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
