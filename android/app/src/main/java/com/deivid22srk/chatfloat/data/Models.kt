package com.deivid22srk.chatfloat.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
 * Local representation of a chat message.
 * - Sent from this device: fromBot = true, senderName = local username
 * - Received from a real Telegram user: fromBot = false, senderName = user's first name
 */
data class ChatMessage(
    val id: Long,
    val text: String,
    val senderName: String,
    val fromBot: Boolean,        // true if message was sent by this bot
    val timestamp: Long,
    val isOutgoing: Boolean      // true if message was sent from this app instance
)
