package com.deivid22srk.chatfloat.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deivid22srk.chatfloat.BuildConfig
import com.deivid22srk.chatfloat.data.Account
import com.deivid22srk.chatfloat.data.GoBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthViewModel : ViewModel() {

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _newlyCreatedToken = MutableStateFlow<String?>(null)
    val newlyCreatedToken: StateFlow<String?> = _newlyCreatedToken.asStateFlow()

    fun init(context: Context) {
        viewModelScope.launch {
            // Configure the Go backend with the bot token, group ID, and
            // a writable directory for persistent storage.
            val dataDir = context.filesDir.absolutePath + "/chatfloat"
            GoBridge.configure(
                botToken = BuildConfig.TELEGRAM_BOT_TOKEN,
                groupID = BuildConfig.TELEGRAM_GROUP_ID,
                dataDir = dataDir
            )
            refreshState()
        }
    }

    fun refreshState() {
        viewModelScope.launch {
            val account = GoBridge.getAccount()
            if (account != null) {
                _state.value = AuthState.Authenticated(
                    token = account.token,
                    username = account.username,
                    avatarBase64 = account.avatarBase64
                )
            } else {
                _state.value = AuthState.Unauthenticated
            }
        }
    }

    fun createAccount(username: String) {
        if (username.isBlank()) {
            _error.value = "Digite um nome de usuário"
            return
        }
        if (username.length > 30) {
            _error.value = "Nome muito longo (máx 30 caracteres)"
            return
        }
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            GoBridge.createAccount(username.trim())
                .onSuccess { token ->
                    _newlyCreatedToken.value = token
                    refreshState()
                }
                .onFailure { _error.value = it.message ?: "Falha ao criar conta" }
            _loading.value = false
        }
    }

    fun loginWithToken(token: String) {
        val cleaned = token.trim().uppercase()
        if (cleaned.length != 8) {
            _error.value = "O token deve ter 8 caracteres"
            return
        }
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            GoBridge.loginWithToken(cleaned)
                .onSuccess { refreshState() }
                .onFailure { _error.value = it.message ?: "Falha ao validar token" }
            _loading.value = false
        }
    }

    fun updateUsername(newUsername: String) {
        val state = _state.value as? AuthState.Authenticated ?: return
        if (newUsername.isBlank()) return
        viewModelScope.launch {
            GoBridge.updateUsername(newUsername.trim())
            refreshState()
        }
    }

    fun updateAvatar(avatarBase64: String?) {
        val state = _state.value as? AuthState.Authenticated ?: return
        viewModelScope.launch {
            GoBridge.updateAvatar(avatarBase64)
            refreshState()
        }
    }

    fun logout() {
        viewModelScope.launch {
            GoBridge.logout()
            _state.value = AuthState.Unauthenticated
        }
    }

    fun clearError() { _error.value = null }
    fun clearNewlyCreatedToken() { _newlyCreatedToken.value = null }

    sealed class AuthState {
        data object Loading : AuthState()
        data object Unauthenticated : AuthState()
        data class Authenticated(
            val token: String,
            val username: String,
            val avatarBase64: String?
        ) : AuthState()
    }
}
