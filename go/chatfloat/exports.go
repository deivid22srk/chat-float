package main

// exports.go defines the C ABI that Kotlin calls via JNI.
//
// Build with:
//   GOOS=android GOARCH=arm64 CGO_ENABLED=1 \
//   CC=$NDK/aarch64-linux-android24-clang \
//   go build -buildmode=c-shared -o libchatfloat.so

/*
#include <stdint.h>
#include <stdlib.h>
*/
import "C"

import (
	"encoding/json"
	"unsafe"

	"chatfloat/chatfloat/internal"
)

type APIResponse struct {
	OK     bool        `json:"ok"`
	Result interface{} `json:"result,omitempty"`
	Error  string      `json:"error,omitempty"`
}

//export Configure
func Configure(supabaseURL, supabaseKey, dataDir *C.char) *C.char {
	cfg := chatfloat.Config{
		SupabaseURL: C.GoString(supabaseURL),
		SupabaseKey: C.GoString(supabaseKey),
		DataDir:     C.GoString(dataDir),
	}
	err := chatfloat.ConfigureAPI(cfg)
	return jsonResp(APIResponse{OK: err == nil, Error: errString(err)})
}

//export CreateAccount
func CreateAccount(username *C.char) *C.char {
	token, err := chatfloat.CreateAccountAPI(C.GoString(username))
	if err != nil {
		return jsonResp(APIResponse{OK: false, Error: errString(err)})
	}
	return jsonResp(APIResponse{OK: true, Result: map[string]string{"token": token}})
}

//export LoginWithToken
func LoginWithToken(token *C.char) *C.char {
	err := chatfloat.LoginWithTokenAPI(C.GoString(token))
	if err != nil {
		return jsonResp(APIResponse{OK: false, Error: errString(err)})
	}
	return jsonResp(APIResponse{OK: true})
}

//export IsLoggedIn
func IsLoggedIn() *C.char {
	return jsonResp(APIResponse{
		OK:     true,
		Result: map[string]bool{"logged_in": chatfloat.IsLoggedInAPI()},
	})
}

//export GetAccount
func GetAccount() *C.char {
	acc := chatfloat.GetAccountAPI()
	return jsonResp(APIResponse{OK: true, Result: acc})
}

//export UpdateUsername
func UpdateUsername(newUsername *C.char) *C.char {
	err := chatfloat.UpdateUsernameAPI(C.GoString(newUsername))
	return jsonResp(APIResponse{OK: err == nil, Error: errString(err)})
}

//export UpdateAvatar
func UpdateAvatar(avatarBase64 *C.char) *C.char {
	err := chatfloat.UpdateAvatarAPI(C.GoString(avatarBase64))
	return jsonResp(APIResponse{OK: err == nil, Error: errString(err)})
}

//export SendMessage
func SendMessage(text *C.char) *C.char {
	err := chatfloat.SendMessageAPI(C.GoString(text))
	return jsonResp(APIResponse{OK: err == nil, Error: errString(err)})
}

//export GetMessages
func GetMessages() *C.char {
	msgs, err := chatfloat.GetMessagesAPI()
	if err != nil {
		return jsonResp(APIResponse{OK: false, Error: errString(err)})
	}
	return jsonResp(APIResponse{OK: true, Result: map[string]interface{}{"messages": msgs}})
}

//export Logout
func Logout() *C.char {
	err := chatfloat.LogoutAPI()
	return jsonResp(APIResponse{OK: err == nil, Error: errString(err)})
}

//export FreeString
func FreeString(s *C.char) {
	C.free(unsafe.Pointer(s))
}

func main() {}

func jsonResp(r APIResponse) *C.char {
	data, _ := json.Marshal(r)
	return C.CString(string(data))
}

func errString(err error) string {
	if err == nil {
		return ""
	}
	return err.Error()
}
