//go:build cgo && jni

package main

// jni.go provides the JNI bridge between Kotlin (GoBridge.kt) and the Go
// exports (exports.go).
//
// Only compiled when the `jni` build tag is set, because it depends on
// <jni.h> which is only available in the Android NDK.

/*
#include <jni.h>
#include <stdlib.h>
#include <string.h>

extern char* Configure(char* supabaseURL, char* supabaseKey, char* dataDir);
extern char* CreateAccount(char* username);
extern char* LoginWithToken(char* token);
extern char* IsLoggedIn(void);
extern char* GetAccount(void);
extern char* UpdateUsername(char* newUsername);
extern char* UpdateAvatar(char* avatarBase64);
extern char* UpdateAvatarBytes(char* data, int length);
extern char* SendMessage(char* text);
extern char* GetMessages(void);
extern char* Logout(void);
extern void  FreeString(char* s);

static jstring cstr_to_jstr(JNIEnv* env, char* cstr) {
    if (cstr == NULL) return (*env)->NewStringUTF(env, "");
    jstring js = (*env)->NewStringUTF(env, cstr);
    FreeString(cstr);
    return js;
}

static char* jstr_to_cstr(JNIEnv* env, jstring js) {
    if (js == NULL) {
        char* empty = (char*)malloc(1); empty[0] = '\0'; return empty;
    }
    const char* utf = (*env)->GetStringUTFChars(env, js, NULL);
    if (utf == NULL) {
        char* empty = (char*)malloc(1); empty[0] = '\0'; return empty;
    }
    char* copy = strdup(utf);
    (*env)->ReleaseStringUTFChars(env, js, utf);
    return copy;
}

static void free_cstr(char* s) { if (s != NULL) free(s); }

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

// UpdateAvatarBytes takes a jbyteArray (raw PNG bytes) and passes them
// to the Go UpdateAvatarBytes export.
JNIEXPORT jstring JNICALL
Java_com_deivid22srk_chatfloat_data_GoBridge_UpdateAvatarBytes(
    JNIEnv* env, jobject thiz, jbyteArray bytes
) {
    if (bytes == NULL || (*env)->GetArrayLength(env, bytes) == 0) {
        char* r = UpdateAvatarBytes(NULL, 0);
        return cstr_to_jstr(env, r);
    }
    jsize len = (*env)->GetArrayLength(env, bytes);
    jbyte* data = (*env)->GetByteArrayElements(env, bytes, NULL);
    if (data == NULL) {
        char* r = UpdateAvatarBytes(NULL, 0);
        return cstr_to_jstr(env, r);
    }
    char* r = UpdateAvatarBytes((char*)data, (int)len);
    (*env)->ReleaseByteArrayElements(env, bytes, data, JNI_ABORT);
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
