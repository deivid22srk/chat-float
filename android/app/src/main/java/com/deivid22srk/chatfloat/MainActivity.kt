package com.deivid22srk.chatfloat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deivid22srk.chatfloat.ui.ChatViewModel
import com.deivid22srk.chatfloat.ui.screens.ChatScreen
import com.deivid22srk.chatfloat.ui.screens.UsernameScreen
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
    val chatVm: ChatViewModel = viewModel()
    val username by chatVm.username.collectAsState()

    AppLifecycleObserver(chatVm)

    if (username.isEmpty()) {
        UsernameScreen(onSave = { chatVm.setUsername(it) })
    } else {
        ChatScreen(viewModel = chatVm)
    }
}

@Composable
private fun AppLifecycleObserver(viewModel: ChatViewModel) {
    // Trigger init once
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.init(context)
        viewModel.startPolling()
    }
}
