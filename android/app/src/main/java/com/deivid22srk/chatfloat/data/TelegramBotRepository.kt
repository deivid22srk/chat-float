package com.deivid22srk.chatfloat.data

import android.util.Base64
import com.deivid22srk.chatfloat.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.Parameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Repository that talks to the Telegram Bot API.
 *
 * ## Message protocol
 *
 * There are two kinds of messages stored in the group:
 *
 * 1. **Account envelopes** — JSON-encoded account registrations (and avatar
 *    updates) used to sync user identity across devices. Format:
 *
 *    `##CHATFLOAT##<base64-json>`
 *
 *    where the JSON is [AccountEnvelope]. These messages are NEVER shown to
 *    the user — they are silently consumed and used to populate the local
 *    `known_accounts` cache.
 *
 * 2. **Chat messages** — plain text messages from real Telegram users in the
 *    group. App-sent messages are prefixed with `[<token>]` so other app
 *    instances can identify the sender by token and look up the avatar.
 *
 * ## Privacy Mode requirement
 *
 * For the bot to see ALL messages in the group (not just commands/mentions),
 * Privacy Mode must be disabled via @BotFather and the bot re-added to the
 * group.
 */
class TelegramBotRepository {

    private val baseUrl = "https://api.telegram.org/bot${BuildConfig.TELEGRAM_BOT_TOKEN}"
    private val groupId: String = BuildConfig.TELEGRAM_GROUP_ID

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val httpClient by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 60_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 60_000
            }
        }
    }

    /** Persisted across app launches so we don't re-receive old updates. */
    private var lastUpdateId: Long = 0L

    fun setLastUpdateId(value: Long) { lastUpdateId = value }
    fun getLastUpdateId(): Long = lastUpdateId

    // ============================================================
    // Account registration
    // ============================================================

    /**
     * Publishes an account registration envelope to the group.
     * Returns true on success.
     */
    suspend fun registerAccount(token: String, username: String, avatarBase64: String?): Boolean =
        withContext(Dispatchers.IO) {
            val envelope = AccountEnvelope(
                type = "REGISTER",
                token = token,
                username = username,
                avatarBase64 = avatarBase64,
                createdAt = System.currentTimeMillis()
            )
            sendEnvelope(envelope)
        }

    /**
     * Publishes an avatar update envelope for an existing token.
     */
    suspend fun publishAvatarUpdate(token: String, avatarBase64: String?): Boolean =
        withContext(Dispatchers.IO) {
            val envelope = AccountEnvelope(
                type = "AVATAR",
                token = token,
                avatarBase64 = avatarBase64,
                createdAt = System.currentTimeMillis()
            )
            sendEnvelope(envelope)
        }

    private suspend fun sendEnvelope(envelope: AccountEnvelope): Boolean {
        val jsonStr = json.encodeToString(AccountEnvelope.serializer(), envelope)
        val encoded = Base64.encodeToString(jsonStr.toByteArray(), Base64.NO_WRAP)
        val text = "$ENVELOPE_PREFIX$encoded"
        return runCatching {
            val params = Parameters.build {
                append("chat_id", groupId)
                append("text", text)
            }
            val response = httpClient.post("$baseUrl/sendMessage") {
                setBody(FormDataContent(params))
            }
            val raw = response.bodyAsText()
            val parsed: TelegramResponse<SendMessageResponse> = json.decodeFromString(raw)
            parsed.ok
        }.getOrDefault(false)
    }

    /**
     * Searches the recent history of the group for a REGISTER envelope whose
     * token matches [token]. Returns the resolved account on success, or null.
     *
     * Used by the "login with token" flow.
     */
    suspend fun resolveAccountByToken(token: String): ResolvedAccount? =
        withContext(Dispatchers.IO) {
            // Walk back through recent messages (Telegram limits to ~100 per call).
            var offset: Long = 0
            repeat(5) { // scan up to 500 recent messages
                val url = "$baseUrl/getUpdates" +
                    "?timeout=0" +
                    "&offset=${-100 - offset}" +
                    "&allowed_updates=%5B%22message%22%5D"
                val response = httpClient.get(url)
                val raw = response.bodyAsText()
                val parsed: TelegramResponse<List<TelegramUpdate>> =
                    json.decodeFromString(raw)
                if (!parsed.ok) return@withContext null
                val updates = parsed.result ?: return@withContext null
                if (updates.isEmpty()) return@withContext null

                for (update in updates) {
                    val msg = update.message ?: continue
                    val text = msg.text ?: continue
                    val envelope = parseEnvelope(text) ?: continue
                    if (envelope.token == token && envelope.type == "REGISTER") {
                        return@withContext ResolvedAccount(
                            token = token,
                            username = envelope.username ?: "user-${token.take(4)}",
                            avatarBase64 = envelope.avatarBase64
                        )
                    }
                }
                offset += 100
            }
            null
        }

    /**
     * Tries to parse [text] as an account envelope. Returns null if not an envelope.
     */
    fun parseEnvelope(text: String): AccountEnvelope? {
        if (!text.startsWith(ENVELOPE_PREFIX)) return null
        val encoded = text.removePrefix(ENVELOPE_PREFIX).trim()
        return runCatching {
            val jsonStr = String(Base64.decode(encoded, Base64.NO_WRAP))
            json.decodeFromString(AccountEnvelope.serializer(), jsonStr)
        }.getOrNull()
    }

    // ============================================================
    // Sending chat messages
    // ============================================================

    /**
     * Sends a chat message to the group as the bot.
     * The text is prefixed with `[<token>]` so other app instances can
     * identify the sender and resolve avatar/username.
     */
    suspend fun sendMessage(
        text: String,
        token: String,
        username: String
    ): ChatMessage = withContext(Dispatchers.IO) {
        val formatted = "[$token] $username: $text"
        val params = Parameters.build {
            append("chat_id", groupId)
            append("text", formatted)
        }
        val response = httpClient.post("$baseUrl/sendMessage") {
            setBody(FormDataContent(params))
        }
        val raw = response.bodyAsText()
        val parsed: TelegramResponse<SendMessageResponse> = json.decodeFromString(raw)
        if (parsed.ok && parsed.result != null) {
            val r = parsed.result
            ChatMessage(
                id = r.message_id,
                text = text,
                senderName = username,
                senderToken = token,
                senderAvatar = null,
                timestamp = r.date * 1000L,
                isOutgoing = true
            )
        } else {
            throw RuntimeException(parsed.description ?: "Failed to send message")
        }
    }

    // ============================================================
    // Receiving updates
    // ============================================================

    /**
     * Long-polls Telegram for new updates. Returns:
     *   - A list of [ChatMessage]s received from real Telegram users OR
     *     from other app instances.
     *   - A list of [AccountEnvelope]s found in the same batch (used to
     *     update the known_accounts cache).
     *
     * Envelopes are NOT included in the chat message list.
     */
    suspend fun getUpdates(): UpdateBatch = withContext(Dispatchers.IO) {
        val url = "$baseUrl/getUpdates" +
            "?timeout=30" +
            "&offset=${lastUpdateId + 1}" +
            "&allowed_updates=%5B%22message%22%5D"
        val response = httpClient.get(url)
        val raw = response.bodyAsText()
        val parsed: TelegramResponse<List<TelegramUpdate>> = json.decodeFromString(raw)
        if (!parsed.ok) return@withContext UpdateBatch(emptyList(), emptyList())

        val updates = parsed.result ?: return@withContext UpdateBatch(emptyList(), emptyList())
        if (updates.isEmpty()) return@withContext UpdateBatch(emptyList(), emptyList())

        lastUpdateId = updates.maxOf { it.update_id }

        val chatMessages = mutableListOf<ChatMessage>()
        val envelopes = mutableListOf<AccountEnvelope>()

        for (update in updates) {
            val msg = update.message ?: update.edited_message ?: continue
            val text = msg.text ?: continue

            // 1. Try parsing as an envelope first
            val envelope = parseEnvelope(text)
            if (envelope != null) {
                envelopes.add(envelope)
                continue
            }

            // 2. Skip the bot's own messages
            if (msg.from?.is_bot == true) continue

            // 3. Try parsing as app-sent "[<token>] <username>: <text>"
            val parsedApp = parseAppMessage(text)
            val senderToken = parsedApp?.token
            val senderName = parsedApp?.username
                ?: msg.from?.first_name
                ?: msg.from?.username
                ?: msg.from?.id?.toString()
                ?: "unknown"
            val displayText = parsedApp?.text ?: text

            chatMessages.add(
                ChatMessage(
                    id = msg.message_id,
                    text = displayText,
                    senderName = senderName,
                    senderToken = senderToken,
                    senderAvatar = null, // resolved later from known_accounts
                    timestamp = msg.date * 1000L,
                    isOutgoing = false
                )
            )
        }

        UpdateBatch(chatMessages, envelopes)
    }

    /**
     * Parses a message of the form "[<token>] <username>: <text>".
     * Returns null if the message doesn't match this pattern.
     */
    private fun parseAppMessage(text: String): AppMessage? {
        if (!text.startsWith("[")) return null
        val closeIdx = text.indexOf("]")
        if (closeIdx < 2 || closeIdx > 12) return null
        val token = text.substring(1, closeIdx)
        if (token.length != 8) return null
        val rest = text.substring(closeIdx + 1).trim()
        val colonIdx = rest.indexOf(": ")
        if (colonIdx <= 0) return null
        val username = rest.substring(0, colonIdx)
        val msgText = rest.substring(colonIdx + 2)
        return AppMessage(token = token, username = username, text = msgText)
    }

    private data class AppMessage(val token: String, val username: String, val text: String)

    data class UpdateBatch(
        val messages: List<ChatMessage>,
        val envelopes: List<AccountEnvelope>
    )

    data class ResolvedAccount(
        val token: String,
        val username: String,
        val avatarBase64: String?
    )

    companion object {
        const val ENVELOPE_PREFIX = "##CHATFLOAT##"
    }
}
