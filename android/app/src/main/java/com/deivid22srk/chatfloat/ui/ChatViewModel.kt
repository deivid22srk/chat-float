package com.deivid22srk.chatfloat.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deivid22srk.chatfloat.data.ChatMessage
import com.deivid22srk.chatfloat.data.TelegramBotRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {

    private val repo = TelegramBotRepository()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _username = MutableStateFlow("")
    val username: StateFlow<String> = _username.asStateFlow()

    private var prefs: SharedPreferences? = null
    private var pollingJob: Job? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _username.value = prefs?.getString(KEY_USERNAME, "") ?: ""
    }

    fun setUsername(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        prefs?.edit()?.putString(KEY_USERNAME, trimmed)?.apply()
        _username.value = trimmed
    }

    fun send(text: String) {
        if (text.isBlank()) return
        val uname = _username.value
        if (uname.isEmpty()) {
            _error.value = "Configure seu nome de usuário primeiro"
            return
        }
        viewModelScope.launch {
            _sending.value = true
            runCatching {
                val msg = repo.sendMessage(text.trim(), uname)
                // Add the sent message to the local list immediately.
                // (The bot does NOT receive its own outgoing messages via getUpdates.)
                _messages.value = _messages.value + msg
            }.onFailure { _error.value = it.message }
            _sending.value = false
        }
    }

    fun startPolling() {
        if (pollingJob != null) return
        pollingJob = viewModelScope.launch {
            while (true) {
                runCatching {
                    val incoming = repo.getUpdates()
                    if (incoming.isNotEmpty()) {
                        // Append new messages, avoiding duplicates by id
                        val existingIds = _messages.value.map { it.id }.toHashSet()
                        val newOnes = incoming.filter { it.id !in existingIds }
                        if (newOnes.isNotEmpty()) {
                            _messages.value = _messages.value + newOnes
                        }
                    }
                }.onFailure { _error.value = it.message }
                // Small pause between polls to avoid hammering the API
                delay(1_000)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun clearError() { _error.value = null }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }

    companion object {
        private const val PREFS_NAME = "chatfloat_prefs"
        private const val KEY_USERNAME = "local_username"
    }
}
