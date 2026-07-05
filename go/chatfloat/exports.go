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
        "encoding/base64"
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
        // Legacy: base64 string. Decode to bytes, then call UpdateAvatarAPI.
        encoded := C.GoString(avatarBase64)
        var pngBytes []byte
        if encoded != "" {
                decoded, err := base64.StdEncoding.DecodeString(encoded)
                if err != nil {
                        return jsonResp(APIResponse{OK: false, Error: "invalid base64: " + err.Error()})
                }
                pngBytes = decoded
        }
        err := chatfloat.UpdateAvatarAPI(pngBytes)
        return jsonResp(APIResponse{OK: err == nil, Error: errString(err)})
}

//export UpdateAvatarBytes
func UpdateAvatarBytes(data *C.char, length C.int) *C.char {
        // New API: raw PNG bytes + length. More efficient than base64.
        if data == nil || length <= 0 {
                err := chatfloat.UpdateAvatarAPI(nil)
                return jsonResp(APIResponse{OK: err == nil, Error: errString(err)})
        }
        // Copy the bytes from C memory into a Go slice
        pngBytes := C.GoBytes(unsafe.Pointer(data), length)
        err := chatfloat.UpdateAvatarAPI(pngBytes)
        return jsonResp(APIResponse{OK: err == nil, Error: errString(err)})
}

//export SendMessage
func SendMessage(text *C.char) *C.char {
        err := chatfloat.SendMessageAPI(C.GoString(text))
        return jsonResp(APIResponse{OK: err == nil, Error: errString(err)})
}

//export SendMediaMessage
func SendMediaMessage(data *C.char, length C.int, mediaType *C.char, contentType *C.char, caption *C.char) *C.char {
        if data == nil || length <= 0 {
                return jsonResp(APIResponse{OK: false, Error: "no media data"})
        }
        mediaBytes := C.GoBytes(unsafe.Pointer(data), length)
        mt := C.GoString(mediaType)
        ct := C.GoString(contentType)
        cap := C.GoString(caption)
        err := chatfloat.SendMediaMessageAPI(mediaBytes, mt, ct, cap)
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
