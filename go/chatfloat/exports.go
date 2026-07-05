package chatfloat

// exports.go defines the C ABI that Kotlin calls via JNI.
//
// Build with:
//   GOOS=android GOARCH=arm64 CGO_ENABLED=1 \
//   CC=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang \
//   go build -buildmode=c-shared -o libchatfloat.so
//
// This produces:
//   - libchatfloat.so       (the shared library, packaged in jniLibs/arm64-v8a/)
//   - libchatfloat.h        (C header — used as reference; JNI signatures
//                             are written by hand in Kotlin)
//
// All exported functions return strings as *C.char (allocated by Go, freed
// by the caller via FreeString). Functions returning JSON have the form:
//   {"ok":true,"result":...} or {"ok":false,"error":"..."}
//
// All functions are blocking and safe to call from any thread. The poller
// runs in its own goroutine once StartPolling is called.

/*
#include <stdint.h>
#include <stdlib.h>
*/
import "C"

import (
	"encoding/json"
	"unsafe"
)

// APIResponse is the standard JSON envelope returned by all exports.
type APIResponse struct {
	OK     bool        `json:"ok"`
	Result interface{} `json:"result,omitempty"`
	Error  string      `json:"error,omitempty"`
}

// Configure must be called once at app startup with the Telegram bot token,
// group ID, and a writable directory for persistent storage.
//
//export Configure
func Configure(botToken, groupID, dataDir *C.char) *C.char {
	cfg := Config{
		BotToken: C.GoString(botToken),
		GroupID:  C.GoString(groupID),
		DataDir:  C.GoString(dataDir),
	}
	err := ConfigureAPI(cfg)
	return jsonResp(APIResponse{OK: err == nil, Error: errString(err)})
}

// CreateAccount generates a new token and registers it. Returns {"token":"..."}.
//
//export CreateAccount
func CreateAccount(username *C.char) *C.char {
	token, err := CreateAccountAPI(C.GoString(username))
	if err != nil {
		return jsonResp(APIResponse{OK: false, Error: errString(err)})
	}
	return jsonResp(APIResponse{OK: true, Result: map[string]string{"token": token}})
}

// LoginWithToken validates a token against the group history. Returns {"ok":true}
// on success.
//
//export LoginWithToken
func LoginWithToken(token *C.char) *C.char {
	err := LoginWithTokenAPI(C.GoString(token))
	if err != nil {
		return jsonResp(APIResponse{OK: false, Error: errString(err)})
	}
	return jsonResp(APIResponse{OK: true})
}

// IsLoggedIn returns {"logged_in":true/false}.
//
//export IsLoggedIn
func IsLoggedIn() *C.char {
	return jsonResp(APIResponse{
		OK:     true,
		Result: map[string]bool{"logged_in": IsLoggedInAPI()},
	})
}

// GetAccount returns the current account, or {"account":null}.
//
//export GetAccount
func GetAccount() *C.char {
	acc := GetAccountAPI()
	return jsonResp(APIResponse{OK: true, Result: acc})
}

// UpdateUsername changes the username locally and re-publishes the registration.
//
//export UpdateUsername
func UpdateUsername(newUsername *C.char) *C.char {
	err := UpdateUsernameAPI(C.GoString(newUsername))
	return jsonResp(APIResponse{OK: err == nil, Error: errString(err)})
}

// UpdateAvatar changes the avatar (base64 PNG). Pass empty string to remove.
//
//export UpdateAvatar
func UpdateAvatar(avatarBase64 *C.char) *C.char {
	err := UpdateAvatarAPI(C.GoString(avatarBase64))
	return jsonResp(APIResponse{OK: err == nil, Error: errString(err)})
}

// SendMessage sends a chat message to the group as the bot.
//
//export SendMessage
func SendMessage(text *C.char) *C.char {
	err := SendMessageAPI(C.GoString(text))
	return jsonResp(APIResponse{OK: err == nil, Error: errString(err)})
}

// GetMessages returns {"messages":[...]} with all cached messages.
//
//export GetMessages
func GetMessages() *C.char {
	msgs := GetMessagesAPI()
	return jsonResp(APIResponse{OK: true, Result: map[string]interface{}{"messages": msgs}})
}

// Logout clears the current account and persisted data.
//
//export Logout
func Logout() *C.char {
	err := LogoutAPI()
	return jsonResp(APIResponse{OK: err == nil, Error: errString(err)})
}

// FreeString frees a string previously returned by any of the exported
// functions. Must be called from C/JNI to avoid memory leaks.
//
//export FreeString
func FreeString(s *C.char) {
	C.free(unsafe.Pointer(s))
}

// --- helpers ---

func jsonResp(r APIResponse) *C.char {
	data, _ := json.Marshal(r)
	// C.CString allocates with malloc — caller must free with FreeString.
	return C.CString(string(data))
}

func errString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}
