package com.deivid22srk.chatfloat.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
            data = buildJsonObject {
                put("username", username)
            }
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

    suspend fun startRealtime() {
        client.realtime.connect()
    }

    /** Returns a [RealtimeChannel] that must be subscribed + collected by the caller. */
    fun messagesChannel(): RealtimeChannel =
        client.channel("public:messages") { }
}
