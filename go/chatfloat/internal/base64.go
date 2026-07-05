package chatfloat

import "encoding/base64"

// stdBase64Encode wraps base64.StdEncoding.EncodeToString so tests can use it
// without importing encoding/base64 directly (keeps tests_test.go clean).
func stdBase64Encode(data []byte) string {
	return base64.StdEncoding.EncodeToString(data)
}
