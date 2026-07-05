package com.deivid22srk.chatfloat.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deivid22srk.chatfloat.data.AccountEnvelope
import com.deivid22srk.chatfloat.data.AccountManager
import com.deivid22srk.chatfloat.data.ChatFloatDatabase
import com.deivid22srk.chatfloat.data.ChatMessage
import com.deivid22srk.chatfloat.data.KnownAccountEntity
import com.deivid22srk.chatfloat.data.MessageEntity
import com.deivid22srk.chatfloat.data.TelegramBotRepository
import com.deivid22srk.chatfloat.data.toDomain
import com.deivid22srk.chatfloat.data.toEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ChatViewModel : ViewModel() {

    private lateinit var repo: TelegramBotRepository
    private lateinit var db: ChatFloatDatabase
    private lateinit var accountManager: AccountManager

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var pollingJob: Job? = null
    private var observerJob: Job? = null

    fun init(context: Context, repository: TelegramBotRepository) {
        repo = repository
        db = ChatFloatDatabase.get(context)
        accountManager = AccountManager(context)

        // Start observing the Room database for changes
        observerJob = viewModelScope.launch {
            db.messageDao().observeAll().collect { entities ->
                // Enrich each message with the avatar from known_accounts cache
                val enriched = entities.map { e ->
                    val avatar = e.senderToken?.let { token ->
                        db.knownAccountDao().findByToken(token)?.avatarBase64
                    } ?: e.senderAvatar
                    e.copy(senderAvatar = avatar).toDomain()
                }
                _messages.value = enriched
            }
        }
    }

    fun send(text: String) {
        if (text.isBlank()) return
        val token = accountManager.getToken() ?: run {
            _error.value = "Conta não configurada"
            return
        }
        val username = accountManager.getUsername() ?: run {
            _error.value = "Conta não configurada"
            return
        }
        viewModelScope.launch {
            _sending.value = true
            runCatching {
                val msg = repo.sendMessage(text.trim(), token, username)
                // Persist the outgoing message locally
                db.messageDao().insert(msg.toEntity())
            }.onFailure { _error.value = it.message }
            _sending.value = false
        }
    }

    fun startPolling() {
        if (pollingJob != null) return
        pollingJob = viewModelScope.launch {
            while (true) {
                runCatching {
                    val batch = repo.getUpdates()
                    // 1. Persist envelopes (account registrations / avatar updates)
                    for (env in batch.envelopes) {
                        applyEnvelope(env)
                    }
                    // 2. Persist incoming chat messages
                    if (batch.messages.isNotEmpty()) {
                        val existingIds = db.messageDao().getAll().map { it.id }.toHashSet()
                        val newOnes = batch.messages.filter { it.id !in existingIds }
                        if (newOnes.isNotEmpty()) {
                            db.messageDao().insertAll(newOnes.map { it.toEntity() })
                        }
                    }
                }.onFailure { _error.value = it.message }
                delay(1_000)
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private suspend fun applyEnvelope(env: AccountEnvelope) {
        val now = System.currentTimeMillis()
        when (env.type) {
            "REGISTER" -> {
                val existing = db.knownAccountDao().findByToken(env.token)
                val entity = KnownAccountEntity(
                    token = env.token,
                    username = env.username ?: existing?.username ?: "user-${env.token.take(4)}",
                    avatarBase64 = env.avatarBase64 ?: existing?.avatarBase64,
                    firstSeen = existing?.firstSeen ?: now
                )
                db.knownAccountDao().upsert(entity)
            }
            "AVATAR" -> {
                val existing = db.knownAccountDao().findByToken(env.token) ?: return
                db.knownAccountDao().upsert(
                    existing.copy(avatarBase64 = env.avatarBase64)
                )
            }
        }
    }

    fun clearError() { _error.value = null }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
        observerJob?.cancel()
    }
}
