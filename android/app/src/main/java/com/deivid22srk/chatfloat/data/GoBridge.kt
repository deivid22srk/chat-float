package com.deivid22srk.chatfloat.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * JNI bridge to the Go backend (libchatfloat.so).
 *
 * The Go side is built with `go build -buildmode=c-shared` and exposes
 * C functions (see exports.go). We declare them as `external` here and
 * let the JVM's JNI machinery resolve them at runtime via System.loadLibrary.
 *
 * All functions return JSON strings of the form:
 *   {"ok":true,"result":...} or {"ok":false,"error":"..."}
 *
 * Strings returned from Go are allocated with C.malloc and must be freed
 * by calling FreeString — the Go side exposes this for exactly this purpose.
 */
object GoBridge {

    private const val TAG = "GoBridge"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    init {
        try {
            System.loadLibrary("chatfloat")
            Log.i(TAG, "Loaded libchatfloat.so")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libchatfloat.so", e)
            throw e
        }
    }

    // ============================================================
    // Native function declarations (must match exports.go signatures)
    // ============================================================

    /** Configure(botToken, groupID, dataDir) -> json */
    private external fun Configure(botToken: String, groupID: String, dataDir: String): String

    /** CreateAccount(username) -> json */
    private external fun CreateAccount(username: String): String

    /** LoginWithToken(token) -> json */
    private external fun LoginWithToken(token: String): String

    /** IsLoggedIn() -> json */
    private external fun IsLoggedIn(): String

    /** GetAccount() -> json */
    private external fun GetAccount(): String

    /** UpdateUsername(newUsername) -> json */
    private external fun UpdateUsername(newUsername: String): String

    /** UpdateAvatar(avatarBase64) -> json */
    private external fun UpdateAvatar(avatarBase64: String): String

    /** SendMessage(text) -> json */
    private external fun SendMessage(text: String): String

    /** GetMessages() -> json */
    private external fun GetMessages(): String

    /** Logout() -> json */
    private external fun Logout(): String

    /** FreeString(s) — frees a string allocated by Go */
    private external fun FreeString(s: String)

    // ============================================================
    // Kotlin-friendly wrappers
    // ============================================================

    /** True if the Go library loaded successfully. */
    val isLoaded: Boolean = try {
        System.loadLibrary("chatfloat"); true
    } catch (e: Throwable) {
        false
    }

    suspend fun configure(botToken: String, groupID: String, dataDir: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = call { Configure(botToken, groupID, dataDir) }
                if (!resp.ok) throw RuntimeException(resp.error)
            }
        }

    suspend fun createAccount(username: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = call { CreateAccount(username) }
                if (!resp.ok) throw RuntimeException(resp.error)
                resp.result?.get("token")?.jsonPrimitive?.contentOrNull
                    ?: throw RuntimeException("missing token in response")
            }
        }

    suspend fun loginWithToken(token: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = call { LoginWithToken(token) }
                if (!resp.ok) throw RuntimeException(resp.error)
            }
        }

    suspend fun isLoggedIn(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val resp = call { IsLoggedIn() }
            resp.result?.get("logged_in")?.jsonPrimitive?.contentOrNull == "true"
        }.getOrDefault(false)
    }

    suspend fun getAccount(): Account? = withContext(Dispatchers.IO) {
        runCatching {
            val resp = call { GetAccount() }
            val result = resp.result ?: return@runCatching null
            val token = result["token"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            Account(
                token = token,
                username = result["username"]?.jsonPrimitive?.contentOrNull ?: "",
                avatarBase64 = result["avatar_base64"]?.jsonPrimitive?.contentOrNull
            )
        }.getOrNull()
    }

    suspend fun updateUsername(newUsername: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = call { UpdateUsername(newUsername) }
                if (!resp.ok) throw RuntimeException(resp.error)
            }
        }

    suspend fun updateAvatar(avatarBase64: String?): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = call { UpdateAvatar(avatarBase64 ?: "") }
                if (!resp.ok) throw RuntimeException(resp.error)
            }
        }

    suspend fun sendMessage(text: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = call { SendMessage(text) }
                if (!resp.ok) throw RuntimeException(resp.error)
            }
        }

    suspend fun getMessages(): List<ChatMessage> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = call { GetMessages() }
            val msgsElement = resp.result?.get("messages") ?: return@runCatching emptyList()
            // Re-serialize the JSON element to a string and parse as List<ChatMessage>
            val msgsJson = json.encodeToString(
                kotlinx.serialization.json.JsonElement.serializer(),
                msgsElement
            )
            json.decodeFromString<List<ChatMessage>>(msgsJson)
        }.getOrDefault(emptyList())
    }

    suspend fun logout(): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resp = call { Logout() }
                if (!resp.ok) throw RuntimeException(resp.error)
            }
        }

    // ============================================================
    // Helpers
    // ============================================================

    @kotlinx.serialization.Serializable
    private data class GoResponse(
        val ok: Boolean,
        val result: JsonObject? = null,
        val error: String? = null
    )

    /** Calls a Go function, parses the JSON response, and frees the string. */
    private inline fun call(block: () -> String): GoResponse {
        val raw = block()
        return try {
            json.decodeFromString(GoResponse.serializer(), raw)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to parse Go response: $raw", e)
            GoResponse(ok = false, error = "Invalid response: ${e.message}")
        }
    }
}
