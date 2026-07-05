package com.deivid22srk.chatfloat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deivid22srk.chatfloat.data.ChatRepository
import com.deivid22srk.chatfloat.data.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the current auth state and exposes sign-in / sign-up operations.
 */
class AuthViewModel : ViewModel() {

    private val repo = ChatRepository()

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    init {
        checkSession()
    }

    fun checkSession() {
        val user = repo.currentUser()
        if (user != null) {
            viewModelScope.launch {
                val profile = repo.fetchProfile(user.id)
                _state.value = AuthState.Authenticated(profile ?: Profile(user.id, user.id.take(8)))
            }
        } else {
            _state.value = AuthState.Unauthenticated
        }
    }

    fun signIn(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _error.value = "Preencha todos os campos"
            return
        }
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching {
                repo.signIn(email, password)
            }.onSuccess {
                checkSession()
            }.onFailure { e ->
                _error.value = e.message ?: "Falha na autenticação"
            }
            _loading.value = false
        }
    }

    fun signUp(email: String, password: String, username: String) {
        if (email.isBlank() || password.isBlank() || username.isBlank()) {
            _error.value = "Preencha todos os campos"
            return
        }
        if (password.length < 6) {
            _error.value = "A senha deve ter ao menos 6 caracteres"
            return
        }
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            runCatching {
                repo.signUp(email, password, username)
            }.onSuccess {
                // After signup, the user is signed in automatically
                checkSession()
            }.onFailure { e ->
                _error.value = e.message ?: "Falha ao criar conta"
            }
            _loading.value = false
        }
    }

    fun signOut() {
        viewModelScope.launch {
            runCatching { repo.signOut() }
            _state.value = AuthState.Unauthenticated
        }
    }

    fun clearError() { _error.value = null }

    sealed class AuthState {
        data object Loading : AuthState()
        data object Unauthenticated : AuthState()
        data class Authenticated(val profile: Profile) : AuthState()
    }
}
