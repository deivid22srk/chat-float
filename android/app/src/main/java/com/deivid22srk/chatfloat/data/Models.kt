package com.deivid22srk.chatfloat.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Profile(
    val id: String,
    val username: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("is_online") val isOnline: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class Room(
    val id: String,
    val name: String,
    val description: String? = null,
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class Message(
    val id: String,
    @SerialName("room_id") val roomId: String? = null,
    @SerialName("user_id") val userId: String? = null,
    val username: String,
    val content: String,
    @SerialName("created_at") val createdAt: String? = null
)

/** Payload for inserting a new message */
@Serializable
data class NewMessage(
    val content: String,
    val username: String,
    @SerialName("user_id") val userId: String,
    @SerialName("room_id") val roomId: String? = null
)
