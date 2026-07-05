package com.deivid22srk.chatfloat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deivid22srk.chatfloat.ui.AuthViewModel
import com.deivid22srk.chatfloat.ui.ChatViewModel
import com.deivid22srk.chatfloat.ui.screens.ChatScreen
import com.deivid22srk.chatfloat.ui.screens.CreateAccountScreen
import com.deivid22srk.chatfloat.ui.screens.LoginScreen
import com.deivid22srk.chatfloat.ui.screens.SettingsScreen
import com.deivid22srk.chatfloat.ui.screens.WelcomeScreen
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

private enum class Screen { Root, CreateAccount, Login, Chat, Settings }

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val authVm: AuthViewModel = viewModel()
    val chatVm: ChatViewModel = viewModel()

    LaunchedEffect(Unit) {
        authVm.init(context)
    }

    val authState by authVm.state.collectAsState()
    var screen by rememberSaveable { mutableStateOf(Screen.Root) }

    when (val s = authState) {
        AuthViewModel.AuthState.Loading -> {
            // Show nothing while loading
        }
        AuthViewModel.AuthState.Unauthenticated -> {
            when (screen) {
                Screen.CreateAccount -> CreateAccountScreen(
                    viewModel = authVm,
                    onBack = { screen = Screen.Root }
                )
                Screen.Login -> LoginScreen(
                    viewModel = authVm,
                    onBack = { screen = Screen.Root }
                )
                else -> WelcomeScreen(
                    onCreateAccount = { screen = Screen.CreateAccount },
                    onLoginWithToken = { screen = Screen.Login }
                )
            }
        }
        is AuthViewModel.AuthState.Authenticated -> {
            LaunchedEffect(s.token) {
                chatVm.startPolling()
            }

            val newlyCreatedToken by authVm.newlyCreatedToken.collectAsState()
            if (newlyCreatedToken != null && screen == Screen.CreateAccount) {
                CreateAccountScreen(
                    viewModel = authVm,
                    onBack = { screen = Screen.Root }
                )
            } else {
                when (screen) {
                    Screen.Settings -> SettingsScreen(
                        authVm = authVm,
                        onBack = { screen = Screen.Chat }
                    )
                    else -> {
                        screen = Screen.Chat
                        ChatScreen(
                            viewModel = chatVm,
                            username = s.username,
                            avatarUrl = s.avatarUrl,
                            onOpenSettings = { screen = Screen.Settings }
                        )
                    }
                }
            }
        }
    }
}
