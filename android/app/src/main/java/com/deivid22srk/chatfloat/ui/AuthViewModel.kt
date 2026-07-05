package com.deivid22srk.chatfloat.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deivid22srk.chatfloat.data.AccountManager
import com.deivid22srk.chatfloat.data.TelegramBotRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthViewModel : ViewModel() {

    private lateinit var accountManager: AccountManager
    private lateinit var repo: TelegramBotRepository

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Populated right after account creation — caller shows it to the user. */
    private val _newlyCreatedToken = MutableStateFlow<String?>(null)
    val newlyCreatedToken: StateFlow<String?> = _newlyCreatedToken.asStateFlow()

    fun init(context: Context, repository: TelegramBotRepository) {
        accountManager = AccountManager(context)
        repo = repository
        refreshState()
    }

    fun refreshState() {
        val token = accountManager.getToken()
        val username = accountManager.getUsername()
        if (token != null && username != null) {
            _state.value = AuthState.Authenticated(
                token = token,
                username = username,
                avatarBase64 = accountManager.getAvatarBase64()
            )
        } else {
            _state.value = AuthState.Unauthenticated
        }
    }

    /**
     * Creates a new account locally and publishes a REGISTER envelope to the
     * group so other instances can resolve the token.
     *
     * On success, sets [newlyCreatedToken] and switches to Authenticated.
     */
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
            val token = accountManager.createAccount(username.trim())
            // Publish registration (fire-and-forget on success; account is local anyway)
            runCatching {
                repo.registerAccount(token, username.trim(), null)
            }
            _newlyCreatedToken.value = token
            refreshState()
            _loading.value = false
        }
    }

    /**
     * Logs in with an existing token. Resolves the account from the group
     * history. On success, restores token + username + avatar locally.
     */
    fun loginWithToken(token: String) {
        val cleaned = token.trim().uppercase()
        if (cleaned.length != 8) {
            _error.value = "O token deve ter 8 caracteres"
            return
        }
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching {
                val resolved = repo.resolveAccountByToken(cleaned)
                if (resolved == null) {
                    _error.value = "Token não encontrado no grupo. Verifique se o bot tem acesso ao histórico."
                } else {
                    accountManager.restoreAccount(
                        token = resolved.token,
                        username = resolved.username,
                        avatarBase64 = resolved.avatarBase64
                    )
                    refreshState()
                }
            }.onFailure {
                _error.value = it.message ?: "Falha ao validar token"
            }
            _loading.value = false
        }
    }

    /**
     * Updates the local username and re-publishes the account envelope.
     */
    fun updateUsername(newUsername: String) {
        val state = _state.value as? AuthState.Authenticated ?: return
        if (newUsername.isBlank()) return
        accountManager.setUsername(newUsername.trim())
        viewModelScope.launch {
            runCatching {
                repo.registerAccount(state.token, newUsername.trim(), state.avatarBase64)
            }
        }
        refreshState()
    }

    /**
     * Updates the avatar (base64 PNG). Pass null to remove the avatar.
     */
    fun updateAvatar(avatarBase64: String?) {
        val state = _state.value as? AuthState.Authenticated ?: return
        accountManager.setAvatar(avatarBase64)
        viewModelScope.launch {
            runCatching {
                repo.publishAvatarUpdate(state.token, avatarBase64)
            }
        }
        refreshState()
    }

    fun logout() {
        accountManager.logout()
        _state.value = AuthState.Unauthenticated
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
