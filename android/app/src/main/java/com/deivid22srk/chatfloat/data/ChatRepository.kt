package com.deivid22srk.chatfloat.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.createChannel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Repository that exposes chat operations and realtime stream.
 */
class ChatRepository {

    private val client = SupabaseClient.client
    private val auth get() = client.auth

    /** Sign up a new user. Also creates a profile row via DB trigger. */
    suspend fun signUp(email: String, password: String, username: String) {
        auth.signUpWith(Email) {
            this.email = email
            this.password = password
            data = mapOf("username" to username)
        }
    }

    suspend fun signIn(email: String, password: String) {
        auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signOut() {
        auth.signOut()
    }

    fun currentUser() = auth.currentSessionOrNull()?.user

    /** Fetches the most recent N messages from the general room. */
    suspend fun fetchRecentMessages(limit: Long = 100): List<Message> {
        val generalRoomId = resolveGeneralRoomId() ?: return emptyList()
        return client.from("messages").select {
            filter {
                eq("room_id", generalRoomId)
            }
            order("created_at", Order.DESCENDING)
            limit(limit)
        }.decodeList<Message>().reversed()
    }

    suspend fun sendMessage(content: String, username: String, userId: String) {
        val generalRoomId = resolveGeneralRoomId() ?: return
        val payload = NewMessage(
            content = content,
            username = username,
            userId = userId,
            roomId = generalRoomId
        )
        client.from("messages").insert(payload)
    }

    suspend fun fetchProfile(userId: String): Profile? {
        return client.from("profiles").select {
            filter { eq("id", userId) }
            limit(1)
        }.decodeList<Profile>().firstOrNull()
    }

    /**
     * Lazily resolves and caches the UUID of the "general" room.
     */
    private var cachedGeneralRoomId: String? = null
    private suspend fun resolveGeneralRoomId(): String? {
        cachedGeneralRoomId?.let { return it }
        val rooms = client.from("rooms").select {
            filter { eq("name", "general") }
            limit(1)
        }.decodeList<Room>()
        cachedGeneralRoomId = rooms.firstOrNull()?.id
        return cachedGeneralRoomId
    }

    /**
     * Subscribes to the realtime channel and emits new messages as they arrive.
     * Caller must call [startRealtime] before collecting the returned flow.
     */
    fun observeMessages(): Flow<Message> {
        val channel: RealtimeChannel = client.realtime.createChannel("public:messages")

        return channel.postgresChangeFlow<PostgresAction.Insert>("public") {
            table = "messages"
        }.map { action ->
            val record = action.record
            Message(
                id = record["id"]?.jsonPrimitive?.content ?: "",
                roomId = record["room_id"]?.jsonPrimitive?.content,
                userId = record["user_id"]?.jsonPrimitive?.content,
                username = record["username"]?.jsonPrimitive?.content ?: "",
                content = record["content"]?.jsonPrimitive?.content ?: "",
                createdAt = record["created_at"]?.jsonPrimitive?.content
            )
        }
    }

    suspend fun startRealtime() {
        client.realtime.connect()
    }

    /** Returns a [RealtimeChannel] that must be subscribed + collected by the caller. */
    fun messagesChannel(): RealtimeChannel =
        client.realtime.createChannel("public:messages")
}
