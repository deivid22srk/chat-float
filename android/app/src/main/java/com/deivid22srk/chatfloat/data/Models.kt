package com.deivid22srk.chatfloat.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Local representation of a user account.
 * Mirrors the Go struct Account.
 * avatarUrl is now a public Supabase Storage URL (preferred over avatarBase64).
 */
@Serializable
data class Account(
    val token: String,
    val username: String,
    @SerialName("avatar_base64") val avatarBase64: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null
)

/**
 * Local representation of a chat message shown in the UI.
 * Mirrors the Go struct ChatMessage.
 * senderAvatar is now a URL (loaded asynchronously by the UI).
 */
@Serializable
data class ChatMessage(
    val id: Long,
    val text: String,
    @SerialName("sender_name") val senderName: String,
    @SerialName("sender_token") val senderToken: String? = null,
    @SerialName("sender_avatar") val senderAvatar: String? = null,
    val timestamp: Long,
    @SerialName("is_outgoing") val isOutgoing: Boolean,
    @SerialName("media_url") val mediaUrl: String? = null,
    @SerialName("media_type") val mediaType: String = ""
)
