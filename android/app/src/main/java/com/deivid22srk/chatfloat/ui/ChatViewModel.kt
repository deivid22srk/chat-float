package com.deivid22srk.chatfloat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deivid22srk.chatfloat.data.ChatRepository
import com.deivid22srk.chatfloat.data.Message
import com.deivid22srk.chatfloat.data.Profile
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive

class ChatViewModel : ViewModel() {

    private val repo = ChatRepository()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var realtimeJob: Job? = null
    private var channel: RealtimeChannel? = null

    fun loadInitial() {
        viewModelScope.launch {
            runCatching { repo.fetchRecentMessages() }
                .onSuccess { _messages.value = it }
                .onFailure { _error.value = it.message }
        }
    }

    fun startRealtime() {
        if (realtimeJob != null) return
        realtimeJob = viewModelScope.launch {
            runCatching {
                repo.startRealtime()
                val ch = repo.messagesChannel()
                channel = ch
                ch.subscribe()
                ch.postgresChangeFlow<PostgresAction.Insert>("public") {
                    table = "messages"
                }.collect { action ->
                    val record = action.record
                    val id = record["id"]?.jsonPrimitive?.content ?: return@collect
                    val msg = Message(
                        id = id,
                        roomId = record["room_id"]?.jsonPrimitive?.content,
                        userId = record["user_id"]?.jsonPrimitive?.content,
                        username = record["username"]?.jsonPrimitive?.content ?: "",
                        content = record["content"]?.jsonPrimitive?.content ?: "",
                        createdAt = record["created_at"]?.jsonPrimitive?.content
                    )
                    // Avoid duplicates
                    if (_messages.value.none { it.id == msg.id }) {
                        _messages.value = _messages.value + msg
                    }
                }
            }.onFailure { _error.value = it.message }
        }
    }

    fun stopRealtime() {
        realtimeJob?.cancel()
        realtimeJob = null
        channel?.let { ch ->
            // unsubscribe() is suspend — fire-and-forget on viewModelScope
            viewModelScope.launch {
                runCatching { ch.unsubscribe() }
            }
        }
        channel = null
    }

    fun send(content: String, profile: Profile) {
        if (content.isBlank()) return
        viewModelScope.launch {
            _sending.value = true
            runCatching {
                repo.sendMessage(content.trim(), profile.username, profile.id)
            }.onFailure { _error.value = it.message }
            _sending.value = false
        }
    }

    fun clearError() { _error.value = null }

    override fun onCleared() {
        super.onCleared()
        stopRealtime()
    }
}
