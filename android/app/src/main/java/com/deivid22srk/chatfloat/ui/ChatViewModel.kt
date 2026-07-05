package com.deivid22srk.chatfloat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deivid22srk.chatfloat.data.ChatMessage
import com.deivid22srk.chatfloat.data.GoBridge
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var pollingJob: Job? = null

    /**
     * Polls Go for the local message cache. The Go side maintains a cache
     * that is updated via Supabase Realtime (WebSocket) — so this poll
     * does NOT make any HTTP requests, it just reads the local cache.
     * We poll every 500ms to reflect cache changes in the UI quickly.
     */
    fun startPolling() {
        if (pollingJob != null) return
        pollingJob = viewModelScope.launch {
            while (true) {
                runCatching {
                    _messages.value = GoBridge.getMessages()
                }
                delay(500)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun send(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            _sending.value = true
            GoBridge.sendMessage(text.trim())
                .onFailure { _error.value = it.message }
            _sending.value = false
        }
    }

    fun clearError() { _error.value = null }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }
}
