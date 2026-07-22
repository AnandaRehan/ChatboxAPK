package com.example.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class ChatViewModel(private val repository: ChatRepository) : ViewModel() {
    private val TAG = "ChatViewModel"

    // Live Flow of all chat history sessions
    val sessions: StateFlow<List<ChatSession>> = repository.allSessions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Current Active Session ID
    private val _currentSessionId = MutableStateFlow<Long?>(null)
    val currentSessionId: StateFlow<Long?> = _currentSessionId.asStateFlow()

    // Live Flow of messages in the currently selected session
    val messages: StateFlow<List<ChatMessage>> = _currentSessionId
        .flatMapLatest { sessionId ->
            if (sessionId != null) {
                repository.getMessages(sessionId)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // App Settings flow (Theme, API settings, Ollama endpoints)
    val settings: StateFlow<AppSettings> = repository.appSettings
        .map { it ?: AppSettings() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppSettings()
        )

    // Input States
    var inputText = MutableStateFlow("")
        private set
    var selectedImageUri = MutableStateFlow<Uri?>(null)
        private set

    // UI State flags
    var isGenerating = MutableStateFlow(false)
        private set
    var isSidebarOpen = MutableStateFlow(false)
        private set
    var isSettingsOpen = MutableStateFlow(false)
        private set

    init {
        // Initialize default settings and create an initial session if empty
        viewModelScope.launch {
            val currentSettings = repository.getSettingsOnce()
            if (repository.appSettings.firstOrNull() == null) {
                repository.saveSettings(AppSettings())
            }
            
            // Auto-select or create first session when sessions are loaded
            sessions.collectLatest { list ->
                if (list.isNotEmpty() && _currentSessionId.value == null) {
                    _currentSessionId.value = list.first().id
                } else if (list.isEmpty() && _currentSessionId.value == null) {
                    createNewSession()
                }
            }
        }
    }

    fun selectSession(sessionId: Long) {
        _currentSessionId.value = sessionId
        isSidebarOpen.value = false
    }

    fun createNewSession(title: String = "Obrolan Baru") {
        viewModelScope.launch {
            val newId = repository.createNewSession(title)
            _currentSessionId.value = newId
            isSidebarOpen.value = false
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            repository.deleteSession(sessionId)
            if (_currentSessionId.value == sessionId) {
                val remaining = sessions.value.filter { it.id != sessionId }
                _currentSessionId.value = remaining.firstOrNull()?.id
            }
        }
    }

    fun updateInputText(text: String) {
        inputText.value = text
    }

    fun selectImage(uri: Uri?) {
        selectedImageUri.value = uri
    }

    fun clearAttachedImage() {
        selectedImageUri.value = null
    }

    fun toggleDarkMode() {
        viewModelScope.launch {
            val curr = settings.value
            repository.saveSettings(curr.copy(isDarkMode = !curr.isDarkMode))
        }
    }

    fun toggleSidebar() {
        isSidebarOpen.value = !isSidebarOpen.value
    }

    fun setSettingsOpen(isOpen: Boolean) {
        isSettingsOpen.value = isOpen
    }

    fun updateSettings(updated: AppSettings) {
        viewModelScope.launch {
            repository.saveSettings(updated)
        }
    }

    fun clearCurrentSessionMessages() {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch {
            repository.clearAllMessagesInSession(sessionId)
        }
    }

    fun sendMessage(context: Context) {
        val sessionId = _currentSessionId.value ?: return
        val text = inputText.value.trim()
        val imageUri = selectedImageUri.value

        if (text.isEmpty() && imageUri == null) return

        isGenerating.value = true
        inputText.value = ""
        selectedImageUri.value = null

        viewModelScope.launch {
            try {
                repository.sendMessage(
                    sessionId = sessionId,
                    text = text,
                    imageUriStr = imageUri?.toString(),
                    context = context
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
                Toast.makeText(context, "Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            } finally {
                isGenerating.value = false
            }
        }
    }

    fun regenerateResponse(messageId: Long) {
        val sessionId = _currentSessionId.value ?: return
        isGenerating.value = true
        viewModelScope.launch {
            try {
                repository.regenerateResponseForMessage(sessionId, messageId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to regenerate response", e)
            } finally {
                isGenerating.value = false
            }
        }
    }

    fun editAndRegenerateMessage(messageId: Long, newContent: String) {
        val sessionId = _currentSessionId.value ?: return
        if (newContent.trim().isEmpty()) return
        isGenerating.value = true
        viewModelScope.launch {
            try {
                repository.editAndRegenerateUserMessage(sessionId, messageId, newContent.trim())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to edit and regenerate message", e)
            } finally {
                isGenerating.value = false
            }
        }
    }

    fun exportChatAsMarkdown(context: Context) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val activeSession = sessions.value.find { it.id == sessionId } ?: return@launch
            val chatMessages = messages.value
            if (chatMessages.isEmpty()) {
                viewModelScope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "Tidak ada pesan untuk diekspor", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val mdText = ExportUtils.exportToMarkdown(activeSession.title, chatMessages)
            
            // Share the Markdown as text content directly (user can copy or send to any editor)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Ekspor Chat: ${activeSession.title}")
                putExtra(Intent.EXTRA_TEXT, mdText)
            }
            
            viewModelScope.launch(Dispatchers.Main) {
                context.startActivity(Intent.createChooser(intent, "Ekspor ke Markdown"))
            }
        }
    }

    fun exportChatAsPdf(context: Context) {
        val sessionId = _currentSessionId.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val activeSession = sessions.value.find { it.id == sessionId } ?: return@launch
            val chatMessages = messages.value
            if (chatMessages.isEmpty()) {
                viewModelScope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "Tidak ada pesan untuk diekspor", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }

            val pdfFile = ExportUtils.exportToPdf(context, activeSession.title, chatMessages)
            if (pdfFile != null) {
                // Share the generated PDF file via secure FileProvider URI
                val authority = "com.aistudio.chatbox.vhyzkp.fileprovider"
                val fileUri = FileProvider.getUriForFile(context, authority, pdfFile)

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                viewModelScope.launch(Dispatchers.Main) {
                    context.startActivity(Intent.createChooser(intent, "Ekspor ke PDF"))
                }
            } else {
                viewModelScope.launch(Dispatchers.Main) {
                    Toast.makeText(context, "Gagal membuat PDF", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

class ChatViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            val database = AppDatabase.getDatabase(context)
            val repository = ChatRepository(database.chatDao(), database.settingsDao())
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
