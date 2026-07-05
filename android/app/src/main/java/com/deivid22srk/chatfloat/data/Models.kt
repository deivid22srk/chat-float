package com.deivid22srk.chatfloat.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class TelegramResponse<T>(
    val ok: Boolean,
    val result: T? = null,
    val description: String? = null,
    val error_code: Int? = null
)

@Serializable
data class TelegramMessage(
    val message_id: Long,
    val from: TelegramUser? = null,
    val chat: TelegramChat? = null,
    val date: Long,
    val text: String? = null,
    val reply_to_message: TelegramMessage? = null
)

@Serializable
data class TelegramUser(
    val id: Long,
    val is_bot: Boolean = false,
    val first_name: String? = null,
    val last_name: String? = null,
    val username: String? = null
)

@Serializable
data class TelegramChat(
    val id: Long,
    val type: String? = null,
    val title: String? = null
)

@Serializable
data class TelegramUpdate(
    val update_id: Long,
    val message: TelegramMessage? = null,
    val edited_message: TelegramMessage? = null
)

@Serializable
data class SendMessageResponse(
    val message_id: Long,
    val from: TelegramUser? = null,
    val chat: TelegramChat? = null,
    val date: Long,
    val text: String? = null
)

/**
 * Local representation of a chat message shown in the UI.
 */
data class ChatMessage(
    val id: Long,
    val text: String,
    val senderName: String,
    val senderToken: String?,    // 8-char token identifying the sender
    val senderAvatar: String?,   // base64 PNG (may be null)
    val timestamp: Long,
    val isOutgoing: Boolean
)

/**
 * Structured message envelope used for registration/system messages stored
 * in the Telegram group as JSON-encoded text.
 *
 * Format of the text: "##CHATFLOAT##<base64-json>"
 *
 * Two kinds:
 *   - REGISTER: announces a token + username + optional avatar
 *   - AVATAR:   updates the avatar for a known token
 */
@Serializable
data class AccountEnvelope(
    val type: String,            // "REGISTER" | "AVATAR"
    val token: String,
    val username: String? = null,
    val avatarBase64: String? = null,
    val createdAt: Long = 0
)
