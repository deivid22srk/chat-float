package com.deivid22srk.chatfloat.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Local representation of a user account.
 * Mirrors the Go struct Account.
 */
@Serializable
data class Account(
    val token: String,
    val username: String,
    @SerialName("avatar_base64") val avatarBase64: String? = null
)

/**
 * Local representation of a chat message shown in the UI.
 * Mirrors the Go struct ChatMessage.
 */
@Serializable
data class ChatMessage(
    val id: Long,
    val text: String,
    @SerialName("sender_name") val senderName: String,
    @SerialName("sender_token") val senderToken: String? = null,
    @SerialName("sender_avatar") val senderAvatar: String? = null,
    val timestamp: Long,
    @SerialName("is_outgoing") val isOutgoing: Boolean
)
