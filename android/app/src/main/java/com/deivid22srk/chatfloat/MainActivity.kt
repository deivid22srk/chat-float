package com.deivid22srk.chatfloat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deivid22srk.chatfloat.ui.AuthViewModel
import com.deivid22srk.chatfloat.ui.ChatViewModel
import com.deivid22srk.chatfloat.ui.screens.ChatScreen
import com.deivid22srk.chatfloat.ui.screens.LoginScreen
import com.deivid22srk.chatfloat.ui.theme.ChatFloatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChatFloatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
fun AppRoot() {
    val authVm: AuthViewModel = viewModel()
    val chatVm: ChatViewModel = viewModel()

    val state by authVm.state.collectAsState()

    when (val s = state) {
        AuthViewModel.AuthState.Loading,
        AuthViewModel.AuthState.Unauthenticated -> {
            if (s is AuthViewModel.AuthState.Loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LoginScreen(viewModel = authVm)
            }
        }
        is AuthViewModel.AuthState.Authenticated -> {
            ChatScreen(
                profile = s.profile,
                viewModel = chatVm,
                onLogout = { authVm.signOut() }
            )
        }
    }
}
