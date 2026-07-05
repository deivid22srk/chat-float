package com.deivid22srk.chatfloat.data

import com.deivid22srk.chatfloat.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Repository that talks to the Telegram Bot API.
 *
 * - sendMessage posts a message to the configured group as the bot.
 * - getUpdates uses long polling (timeout=30s) to receive new messages from
 *   real Telegram users in the group.
 *
 * IMPORTANT: For the bot to see ALL messages in the group (not just commands,
 * mentions and replies), Privacy Mode must be disabled via @BotFather:
 *   1. Open @BotFather in Telegram
 *   2. Send /setprivacy
 *   3. Select @ChatFloat5_bot
 *   4. Choose "Disable"
 *   5. Remove the bot from the group and re-add it (changes only take effect
 *      after re-adding).
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

    private var lastUpdateId: Long = 0L

    /**
     * Sends a message to the group as the bot.
     * The text is prefixed with the local username so other Telegram users
     * know who is talking: "<username>: <text>".
     *
     * Returns the created [ChatMessage] on success.
     */
    suspend fun sendMessage(text: String, localUsername: String): ChatMessage =
        withContext(Dispatchers.IO) {
            val formatted = "$localUsername: $text"
            val response = httpClient.post("$baseUrl/sendMessage") {
                body = FormDataContent(Parameters.build {
                    append("chat_id", groupId)
                    append("text", formatted)
                })
            }
            val raw = response.bodyAsText()
            val parsed: TelegramResponse<SendMessageResponse> =
                json.decodeFromString(raw)
            if (parsed.ok && parsed.result != null) {
                val r = parsed.result
                ChatMessage(
                    id = r.message_id,
                    text = text,
                    senderName = localUsername,
                    fromBot = true,
                    timestamp = r.date * 1000L,
                    isOutgoing = true
                )
            } else {
                throw RuntimeException(parsed.description ?: "Failed to send message")
            }
        }

    /**
     * Long-polls Telegram for new updates.
     * Returns a list of [ChatMessage]s received since the last call.
     *
     * Only messages from REAL Telegram users in the group will appear here.
     * The bot does NOT receive its own outgoing messages.
     */
    suspend fun getUpdates(): List<ChatMessage> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/getUpdates" +
            "?timeout=30" +
            "&offset=${lastUpdateId + 1}" +
            "&allowed_updates=%5B%22message%22%5D"
        val response = httpClient.get(url)
        val raw = response.bodyAsText()
        val parsed: TelegramResponse<List<TelegramUpdate>> =
            json.decodeFromString(raw)
        if (!parsed.ok) return@withContext emptyList()

        val updates = parsed.result ?: return@withContext emptyList()
        if (updates.isEmpty()) return@withContext emptyList()

        // Advance the offset past the last received update
        lastUpdateId = updates.maxOf { it.update_id }

        updates.mapNotNull { update ->
            val msg = update.message ?: update.edited_message ?: return@mapNotNull null
            val text = msg.text ?: return@mapNotNull null
            // Skip the bot's own messages (shouldn't happen, but be safe)
            if (msg.from?.is_bot == true) return@mapNotNull null
            // Strip the "<username>: " prefix that this app adds to outgoing messages,
            // so messages from other app instances also display cleanly.
            val senderName = msg.from?.first_name
                ?: msg.from?.username
                ?: msg.from?.id?.toString()
                ?: "unknown"
            val displayText = stripSenderPrefix(text)
            ChatMessage(
                id = msg.message_id,
                text = displayText,
                senderName = senderName,
                fromBot = false,
                timestamp = msg.date * 1000L,
                isOutgoing = false
            )
        }
    }

    /** If the message text matches the "<username>: <text>" pattern, strip the prefix. */
    private fun stripSenderPrefix(text: String): String {
        val idx = text.indexOf(": ")
        return if (idx > 0 && idx < 30) {
            text.substring(idx + 2)
        } else {
            text
        }
    }

    /**
     * Lightweight call to verify the bot token is valid.
     */
    suspend fun verifyBot(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val response = httpClient.get("$baseUrl/getMe")
            val raw = response.bodyAsText()
            val parsed: TelegramResponse<TelegramUser> = json.decodeFromString(raw)
            parsed.ok
        }.getOrDefault(false)
    }
}
