package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.ChatScreen
import com.example.ui.ChatViewModel
import com.example.ui.ChatViewModelFactory
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Instantiate the ViewModel with our customized database factory
            val chatViewModel: ChatViewModel = viewModel(
                factory = ChatViewModelFactory(applicationContext)
            )
            
            // Collect active settings flow to handle manual theme toggling reactively
            val settings by chatViewModel.settings.collectAsState()

            MyApplicationTheme(
                darkTheme = settings.isDarkMode,
                dynamicColor = false // Force consistent polished custom palette
            ) {
                ChatScreen(viewModel = chatViewModel)
            }
        }
    }
}
