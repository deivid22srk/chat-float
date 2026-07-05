//go:build cgo && jni

package main

// jni.go provides the JNI bridge between Kotlin (GoBridge.kt) and the Go
// exports (exports.go).
//
// This file is only compiled when the `jni` build tag is set, because it
// depends on <jni.h> which is only available in the Android NDK.
//
// Problem: `go build -buildmode=c-shared` with `//export Foo` produces a C
// symbol named `Foo`, but the JVM's JNI looks for symbols named
// `Java_<package>_<Class>_<method>` (e.g. `Java_com_deivid22srk_chatfloat_data_GoBridge_CreateAccount`).
//
// Solution: this file declares C wrapper functions with the JNI-mangled names
// that:
//   1. Convert each `jstring` argument to a `char*` (via GetStringUTFChars + strdup)
//   2. Call the corresponding Go export
//   3. Convert the returned `char*` to a `jstring` (via NewStringUTF)
//   4. Free the Go-allocated string (via FreeString) and the strdup'd copies

/*
#include <jni.h>
#include <stdlib.h>
#include <string.h>

// ---- Forward declarations of Go exports (defined in exports.go via //export) ----
extern char* Configure(char* botToken, char* groupID, char* dataDir);
extern char* CreateAccount(char* username);
extern char* LoginWithToken(char* token);
extern char* IsLoggedIn(void);
extern char* GetAccount(void);
extern char* UpdateUsername(char* newUsername);
extern char* UpdateAvatar(char* avatarBase64);
extern char* SendMessage(char* text);
extern char* GetMessages(void);
extern char* Logout(void);
extern void  FreeString(char* s);

// ---- JNI helpers ----

// Convert a Go-allocated char* (result) to a jstring, then free the Go string.
// The JVM copies the UTF-8 bytes when NewStringUTF is called, so it's safe
// to free the original immediately after.
static jstring cstr_to_jstr(JNIEnv* env, char* cstr) {
    if (cstr == NULL) {
        return (*env)->NewStringUTF(env, "");
    }
    jstring js = (*env)->NewStringUTF(env, cstr);
    FreeString(cstr);  // free the Go-allocated string
    return js;
}

// Convert a jstring argument to a malloc'd char* copy.
// Caller must free_cstr() the result after use.
// Returns a non-NULL empty string if js is NULL (defensive).
static char* jstr_to_cstr(JNIEnv* env, jstring js) {
    if (js == NULL) {
        char* empty = (char*)malloc(1);
        empty[0] = '\0';
        return empty;
    }
    const char* utf = (*env)->GetStringUTFChars(env, js, NULL);
    if (utf == NULL) {
        char* empty = (char*)malloc(1);
        empty[0] = '\0';
        return empty;
    }
    char* copy = strdup(utf);
    (*env)->ReleaseStringUTFChars(env, js, utf);
    return copy;
}

static void free_cstr(char* s) {
    if (s != NULL) free(s);
}

// ---- JNI method wrappers ----
// Each wrapper matches the signature declared in GoBridge.kt:
//   private external fun Configure(botToken: String, groupID: String, dataDir: String): String
//   private external fun CreateAccount(username: String): String
//   etc.
//
// Kotlin `object` methods compile to instance methods, so the second
// parameter is `jobject thiz` (the singleton instance).

JNIEXPORT jstring JNICALL
Java_com_deivid22srk_chatfloat_data_GoBridge_Configure(
    JNIEnv* env, jobject thiz, jstring p0, jstring p1, jstring p2
) {
    char* a0 = jstr_to_cstr(env, p0);
    char* a1 = jstr_to_cstr(env, p1);
    char* a2 = jstr_to_cstr(env, p2);
    char* r = Configure(a0, a1, a2);
    free_cstr(a0); free_cstr(a1); free_cstr(a2);
    return cstr_to_jstr(env, r);
}

JNIEXPORT jstring JNICALL
Java_com_deivid22srk_chatfloat_data_GoBridge_CreateAccount(
    JNIEnv* env, jobject thiz, jstring p0
) {
    char* a0 = jstr_to_cstr(env, p0);
    char* r = CreateAccount(a0);
    free_cstr(a0);
    return cstr_to_jstr(env, r);
}

JNIEXPORT jstring JNICALL
Java_com_deivid22srk_chatfloat_data_GoBridge_LoginWithToken(
    JNIEnv* env, jobject thiz, jstring p0
) {
    char* a0 = jstr_to_cstr(env, p0);
    char* r = LoginWithToken(a0);
    free_cstr(a0);
    return cstr_to_jstr(env, r);
}

JNIEXPORT jstring JNICALL
Java_com_deivid22srk_chatfloat_data_GoBridge_IsLoggedIn(
    JNIEnv* env, jobject thiz
) {
    char* r = IsLoggedIn();
    return cstr_to_jstr(env, r);
}

JNIEXPORT jstring JNICALL
Java_com_deivid22srk_chatfloat_data_GoBridge_GetAccount(
    JNIEnv* env, jobject thiz
) {
    char* r = GetAccount();
    return cstr_to_jstr(env, r);
}

JNIEXPORT jstring JNICALL
Java_com_deivid22srk_chatfloat_data_GoBridge_UpdateUsername(
    JNIEnv* env, jobject thiz, jstring p0
) {
    char* a0 = jstr_to_cstr(env, p0);
    char* r = UpdateUsername(a0);
    free_cstr(a0);
    return cstr_to_jstr(env, r);
}

JNIEXPORT jstring JNICALL
Java_com_deivid22srk_chatfloat_data_GoBridge_UpdateAvatar(
    JNIEnv* env, jobject thiz, jstring p0
) {
    char* a0 = jstr_to_cstr(env, p0);
    char* r = UpdateAvatar(a0);
    free_cstr(a0);
    return cstr_to_jstr(env, r);
}

JNIEXPORT jstring JNICALL
Java_com_deivid22srk_chatfloat_data_GoBridge_SendMessage(
    JNIEnv* env, jobject thiz, jstring p0
) {
    char* a0 = jstr_to_cstr(env, p0);
    char* r = SendMessage(a0);
    free_cstr(a0);
    return cstr_to_jstr(env, r);
}

JNIEXPORT jstring JNICALL
Java_com_deivid22srk_chatfloat_data_GoBridge_GetMessages(
    JNIEnv* env, jobject thiz
) {
    char* r = GetMessages();
    return cstr_to_jstr(env, r);
}

JNIEXPORT jstring JNICALL
Java_com_deivid22srk_chatfloat_data_GoBridge_Logout(
    JNIEnv* env, jobject thiz
) {
    char* r = Logout();
    return cstr_to_jstr(env, r);
}

*/
import "C"
