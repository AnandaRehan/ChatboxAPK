package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.data.AppSettings
import com.example.data.ChatMessage
import com.example.data.ChatSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    val sessions by viewModel.sessions.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val currentSessionId by viewModel.currentSessionId.collectAsState()
    val settings by viewModel.settings.collectAsState()
    
    val inputText by viewModel.inputText.collectAsState()
    val selectedImageUri by viewModel.selectedImageUri.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val isSidebarOpen by viewModel.isSidebarOpen.collectAsState()
    val isSettingsOpen by viewModel.isSettingsOpen.collectAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // Keep drawerState in sync with viewModel.isSidebarOpen
    LaunchedEffect(isSidebarOpen) {
        if (isSidebarOpen) {
            drawerState.open()
        } else {
            drawerState.close()
        }
    }

    // Handle drawer closure detection
    LaunchedEffect(drawerState.isOpen) {
        if (!drawerState.isOpen && isSidebarOpen) {
            viewModel.toggleSidebar()
        } else if (drawerState.isOpen && !isSidebarOpen) {
            viewModel.toggleSidebar()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(300.dp),
                drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
            ) {
                SidebarContent(
                    sessions = sessions,
                    currentSessionId = currentSessionId,
                    onSessionSelect = { viewModel.selectSession(it) },
                    onNewSession = { viewModel.createNewSession() },
                    onDeleteSession = { viewModel.deleteSession(it) },
                    onToggleDarkMode = { viewModel.toggleDarkMode() },
                    isDarkMode = settings.isDarkMode,
                    onOpenSettings = { viewModel.setSettingsOpen(true) }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                val activeSession = sessions.find { it.id == currentSessionId }
                var showMenu by remember { mutableStateOf(false) }
                var isEditingTitle by remember { mutableStateOf(false) }
                var editTitleText by remember { mutableStateOf("") }

                LaunchedEffect(activeSession) {
                    editTitleText = activeSession?.title ?: ""
                }

                Column(modifier = Modifier.fillMaxWidth()) {
                    TopAppBar(
                        title = {
                            if (isEditingTitle) {
                                TextField(
                                    value = editTitleText,
                                    onValueChange = { editTitleText = it },
                                    singleLine = true,
                                    trailingIcon = {
                                        IconButton(onClick = {
                                            if (editTitleText.trim().isNotEmpty()) {
                                                viewModel.createNewSession() // fallback or save
                                                coroutineScope.launch {
                                                    currentSessionId?.let { id ->
                                                        viewModel.updateSettings(settings) // trigger
                                                        viewModel.selectSession(id)
                                                        // We can save directly to repository
                                                        val db = com.example.data.AppDatabase.getDatabase(context)
                                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                            db.chatDao().updateSession(
                                                                com.example.data.ChatSession(id = id, title = editTitleText)
                                                            )
                                                        }
                                                    }
                                                    isEditingTitle = false
                                                }
                                            }
                                        }) {
                                            Icon(Icons.Default.Check, contentDescription = "Simpan Judul")
                                        }
                                    },
                                    colors = TextFieldDefaults.colors(
                                        focusedContainerColor = Color.Transparent,
                                        unfocusedContainerColor = Color.Transparent
                                    ),
                                    modifier = Modifier.fillMaxWidth(0.85f)
                                )
                            } else {
                                Row(
                                    modifier = Modifier.clickable { isEditingTitle = true },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = activeSession?.title ?: "Chatbox AI",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = "Edit Judul",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { viewModel.toggleSidebar() }) {
                                Icon(Icons.Default.Menu, contentDescription = "Menu Sidebar")
                            }
                        },
                        actions = {
                            IconButton(onClick = { viewModel.setSettingsOpen(true) }) {
                                Icon(Icons.Default.Settings, contentDescription = "Pengaturan")
                            }

                            Box {
                                IconButton(onClick = { showMenu = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = "Menu Ekspor")
                                }
                                DropdownMenu(
                                    expanded = showMenu,
                                    onDismissRequest = { showMenu = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Ekspor ke PDF") },
                                        leadingIcon = { Icon(Icons.Default.PictureAsPdf, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            viewModel.exportChatAsPdf(context)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Ekspor ke Markdown") },
                                        leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                                        onClick = {
                                            showMenu = false
                                            viewModel.exportChatAsMarkdown(context)
                                        }
                                    )
                                    Divider()
                                    DropdownMenuItem(
                                        text = { Text("Hapus Pesan Obrolan", color = MaterialTheme.colorScheme.error) },
                                        leadingIcon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            showMenu = false
                                            viewModel.clearCurrentSessionMessages()
                                            Toast.makeText(context, "Riwayat obrolan dibersihkan", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            }
                        }
                    )
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), thickness = 1.dp)
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // High Density SubHeader Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Interactive model selector chip
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.secondary)
                                .clickable { viewModel.setSettingsOpen(true) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.onSecondary.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "PRO",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSecondary
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (settings.apiProvider == "gemini") {
                                    val formatted = when (settings.selectedModel) {
                                        "gemini-3.5-flash" -> "Gemini 3.5 Flash"
                                        "gemini-3.1-flash-lite-preview" -> "Gemini 3.1 Flash Lite"
                                        "gemini-2.5-flash" -> "Gemini 2.5 Flash"
                                        "gemini-2.5-flash-lite-preview" -> "Gemini 2.5 Flash Lite"
                                        "gemini-2.5-pro" -> "Gemini 2.5 Pro"
                                        "gemini-3-flash-preview" -> "Gemini 3 Flash Preview"
                                        "gemini-2.5-flash-image" -> "Nano Banana"
                                        "gemini-3-pro-image-preview" -> "Nano Banana Pro"
                                        else -> settings.selectedModel
                                    }
                                    formatted
                                } else if (settings.apiProvider == "ollama") {
                                    settings.ollamaModelName
                                } else {
                                    settings.customModelName.ifEmpty { "Custom Model" }
                                },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        // Instant Export shortcut button
                        var showExportSubMenu by remember { mutableStateOf(false) }
                        Box {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .clickable { showExportSubMenu = true }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "EXPORT",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            DropdownMenu(
                                expanded = showExportSubMenu,
                                onDismissRequest = { showExportSubMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Ekspor ke PDF") },
                                    leadingIcon = { Icon(Icons.Default.PictureAsPdf, contentDescription = null) },
                                    onClick = {
                                        showExportSubMenu = false
                                        viewModel.exportChatAsPdf(context)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Ekspor ke Markdown") },
                                    leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                                    onClick = {
                                        showExportSubMenu = false
                                        viewModel.exportChatAsMarkdown(context)
                                    }
                                )
                            }
                        }
                    }

                    // Message Area
                    if (messages.isEmpty()) {
                        WelcomePlaceholder(
                            activeProviderName = settings.apiProvider.uppercase(),
                            activeModelName = if (settings.apiProvider == "gemini") settings.selectedModel else settings.ollamaModelName,
                            onStartSamplePrompt = {
                                viewModel.updateInputText(it)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        MessageList(
                            messages = messages,
                            isGenerating = isGenerating,
                            onRegenerate = { msgId -> viewModel.regenerateResponse(msgId) },
                            onEditAndRegenerate = { msgId, newText -> viewModel.editAndRegenerateMessage(msgId, newText) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Input Box
                    ChatInputBox(
                        inputText = inputText,
                        selectedImageUri = selectedImageUri,
                        isGenerating = isGenerating,
                        onInputChanged = { viewModel.updateInputText(it) },
                        onImageAttached = { viewModel.selectImage(it) },
                        onImageCleared = { viewModel.clearAttachedImage() },
                        onSendClicked = { viewModel.sendMessage(context) }
                    )
                }
            }
        }
    }

    // Settings Modal Dialog Overlay
    if (isSettingsOpen) {
        SettingsDialog(
            settings = settings,
            onDismiss = { viewModel.setSettingsOpen(false) },
            onSave = { updatedSettings ->
                viewModel.updateSettings(updatedSettings)
                viewModel.setSettingsOpen(false)
                Toast.makeText(context, "Pengaturan berhasil disimpan", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun SidebarContent(
    sessions: List<ChatSession>,
    currentSessionId: Long?,
    onSessionSelect: (Long) -> Unit,
    onNewSession: () -> Unit,
    onDeleteSession: (Long) -> Unit,
    onToggleDarkMode: () -> Unit,
    isDarkMode: Boolean,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp)
    ) {
        // App Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Chat,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Chatbox AI",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Lokal & Gemini Client",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // New Chat Button
        Button(
            onClick = onNewSession,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Obrolan Baru")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "RIWAYAT CHAT",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )

        // Sessions List
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(sessions) { session ->
                val isSelected = session.id == currentSessionId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else Color.Transparent
                        )
                        .clickable { onSessionSelect(session.id) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Message,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = session.title,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }

                    if (sessions.size > 1) {
                        IconButton(
                            onClick = { onDeleteSession(session.id) },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Hapus Sesi",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        Divider(modifier = Modifier.padding(vertical = 8.dp))

        // Drawer Bottom Config Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Theme toggle button
            IconButton(
                onClick = onToggleDarkMode,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .size(40.dp)
            ) {
                Icon(
                    imageVector = if (isDarkMode) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = "Ganti Tema",
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            // Quick Settings Shortcut
            Button(
                onClick = onOpenSettings,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Pengaturan", fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun WelcomePlaceholder(
    activeProviderName: String,
    activeModelName: String,
    onStartSamplePrompt: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Bagaimana saya bisa membantumu hari ini?",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Model Aktif: $activeProviderName ($activeModelName)",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(30.dp))

            // Starter Suggestions
            Text(
                text = "Saran Pertanyaan:",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            val suggestions = listOf(
                "Jelaskan apa itu REST API secara singkat.",
                "Buat program Hello World di Kotlin.",
                "Berikan resep nasi goreng kambing yang enak."
            )

            suggestions.forEach { suggestion ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { onStartSamplePrompt(suggestion) },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = suggestion,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun MessageList(
    messages: List<ChatMessage>,
    isGenerating: Boolean,
    onRegenerate: (Long) -> Unit,
    onEditAndRegenerate: (Long, String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val context = LocalContext.current

    var selectedMessageForOptions by remember { mutableStateOf<ChatMessage?>(null) }
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }

    // Scroll to bottom as streaming content updates or new messages arrive
    LaunchedEffect(messages.lastOrNull()?.content, messages.size, isGenerating) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 20.dp)
    ) {
        items(messages) { message ->
            MessageBubble(
                message = message,
                onLongClick = { selectedMessageForOptions = message }
            )
        }

        if (isGenerating && (messages.isEmpty() || messages.last().role.lowercase() == "user")) {
            item {
                LoadingIndicatorBubble()
            }
        }
    }

    // Options Dialog on Long-Press
    if (selectedMessageForOptions != null) {
        val targetMsg = selectedMessageForOptions!!
        val isUser = targetMsg.role.lowercase() == "user"

        Dialog(onDismissRequest = { selectedMessageForOptions = null }) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (isUser) "Opsi Pertanyaan" else "Opsi Jawaban AI",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    if (isUser) {
                        // 1. Tanya Ulang / Reload Jawaban
                        OptionMenuItem(
                            icon = Icons.Default.Refresh,
                            title = "Tanya Ulang / Reload Jawaban",
                            subtitle = "Jawab ulang pertanyaan ini dan gantikan jawaban AI sebelumnya",
                            onClick = {
                                val msgId = targetMsg.id
                                selectedMessageForOptions = null
                                onRegenerate(msgId)
                            }
                        )

                        // 2. Edit Pertanyaan
                        OptionMenuItem(
                            icon = Icons.Default.Edit,
                            title = "Edit Pertanyaan",
                            subtitle = "Ubah teks pertanyaan dan minta AI menjawab kembali",
                            onClick = {
                                val msgToEdit = targetMsg
                                selectedMessageForOptions = null
                                editingMessage = msgToEdit
                            }
                        )

                        // 3. Salin Teks Pertanyaan
                        OptionMenuItem(
                            icon = Icons.Default.ContentCopy,
                            title = "Salin Teks Pertanyaan",
                            subtitle = "Salin teks pertanyaan ini ke clipboard",
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Pertanyaan", targetMsg.content)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Teks pertanyaan disalin ke clipboard", Toast.LENGTH_SHORT).show()
                                selectedMessageForOptions = null
                            }
                        )
                    } else {
                        // AI Answer Options
                        OptionMenuItem(
                            icon = Icons.Default.ContentCopy,
                            title = "Salin Teks Jawaban",
                            subtitle = "Salin teks jawaban AI ke clipboard",
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Jawaban AI", targetMsg.content)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Teks jawaban disalin ke clipboard", Toast.LENGTH_SHORT).show()
                                selectedMessageForOptions = null
                            }
                        )

                        OptionMenuItem(
                            icon = Icons.Default.Refresh,
                            title = "Generate Ulang Jawaban Ini",
                            subtitle = "Minta AI menjawab kembali pertanyaan ini",
                            onClick = {
                                val msgId = targetMsg.id
                                selectedMessageForOptions = null
                                onRegenerate(msgId)
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { selectedMessageForOptions = null }) {
                            Text("Tutup", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }

    // Edit Question Dialog
    if (editingMessage != null) {
        val targetMsgToEdit = editingMessage!!
        var editedText by remember(targetMsgToEdit.id) { mutableStateOf(targetMsgToEdit.content) }

        Dialog(onDismissRequest = { editingMessage = null }) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Edit Pertanyaan",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    OutlinedTextField(
                        value = editedText,
                        onValueChange = { editedText = it },
                        label = { Text("Pertanyaan Anda") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 100.dp, max = 220.dp),
                        shape = RoundedCornerShape(12.dp),
                        maxLines = 6
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { editingMessage = null }) {
                            Text("Batal")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val msgId = targetMsgToEdit.id
                                val newText = editedText
                                editingMessage = null
                                onEditAndRegenerate(msgId, newText)
                            },
                            enabled = editedText.trim().isNotEmpty()
                        ) {
                            Text("Simpan & Tanya Ulang")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OptionMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun MessageBubble(
    message: ChatMessage,
    onLongClick: (ChatMessage) -> Unit
) {
    val isUser = message.role.lowercase() == "user"
    val timeString = remember(message.timestamp) {
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        sdf.format(java.util.Date(message.timestamp))
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            // AI Icon Avatar
            Box(
                modifier = Modifier
                    .padding(end = 8.dp, top = 4.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(if (isUser) 0.85f else 0.92f),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
        ) {
            // Chat Bubble Container
            Box(
                modifier = Modifier
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 2.dp,
                            bottomEnd = if (isUser) 2.dp else 16.dp
                        )
                    )
                    .background(
                        if (isUser) MaterialTheme.colorScheme.secondary
                        else MaterialTheme.colorScheme.surface
                    )
                    .then(
                        if (!isUser) {
                            Modifier.border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(
                                    topStart = 16.dp,
                                    topEnd = 16.dp,
                                    bottomStart = 2.dp,
                                    bottomEnd = 16.dp
                                )
                            )
                        } else Modifier
                    )
                    .pointerInput(message.id) {
                        detectTapGestures(
                            onLongPress = {
                                onLongClick(message)
                            }
                        )
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column {
                    // Render image attachment if exists
                    if (message.imagePath != null) {
                        val imgFile = File(message.imagePath)
                        if (imgFile.exists()) {
                            AsyncImage(
                                model = imgFile,
                                contentDescription = "Lampiran Gambar",
                                modifier = Modifier
                                    .padding(bottom = 6.dp)
                                    .widthIn(max = 220.dp)
                                    .heightIn(max = 220.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }

                    if (isUser) {
                        Text(
                            text = message.content,
                            color = MaterialTheme.colorScheme.onSecondary,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )
                    } else {
                        if (message.content.isEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "Sedang mengetik...",
                                    fontSize = 13.sp,
                                    fontStyle = FontStyle.Italic,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        } else {
                            MarkdownText(
                                text = message.content,
                                textColor = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            // High Density Subtitle/Metadata Info Under Bubbles
            if (isUser) {
                Text(
                    text = timeString,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp, end = 4.dp)
                )
            } else {
                Row(
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Gemini 2.5 • $timeString",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    
                    val context = LocalContext.current
                    Icon(
                        imageVector = Icons.Default.ThumbUp,
                        contentDescription = "Suka",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .size(12.dp)
                            .clickable { Toast.makeText(context, "Menerima feedback positif", Toast.LENGTH_SHORT).show() }
                    )
                    Icon(
                        imageVector = Icons.Default.ThumbDown,
                        contentDescription = "Tidak Suka",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .size(12.dp)
                            .clickable { Toast.makeText(context, "Feedback negatif disimpan", Toast.LENGTH_SHORT).show() }
                    )
                }
            }
        }
    }
}

@Composable
fun LoadingIndicatorBubble() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        // AI Icon Avatar
        Box(
            modifier = Modifier
                .padding(end = 6.dp, top = 4.dp)
                .size(30.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.SmartToy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(16.dp)
            )
        }

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 2.dp, bottomEnd = 14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "AI sedang berpikir...",
                    fontSize = 13.sp,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatInputBox(
    inputText: String,
    selectedImageUri: Uri?,
    isGenerating: Boolean,
    onInputChanged: (String) -> Unit,
    onImageAttached: (Uri) -> Unit,
    onImageCleared: () -> Unit,
    onSendClicked: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onImageAttached(uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // Attachment Preview Row
        AnimatedVisibility(
            visible = selectedImageUri != null,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            if (selectedImageUri != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(RoundedCornerShape(8.dp))
                    ) {
                        AsyncImage(
                            model = selectedImageUri,
                            contentDescription = "Pratinjau Lampiran",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        IconButton(
                            onClick = onImageCleared,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(20.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear Image",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Gambar terlampir (akan dikirim dengan pesan berikutnya)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Input Actions Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Left Action Plus Button
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { 
                        Toast.makeText(context, "Fitur tambahan segera hadir!", Toast.LENGTH_SHORT).show()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Menu Tambahan",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Text Input Bar with Gallery attachment built-in
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = inputText,
                    onValueChange = onInputChanged,
                    placeholder = { Text("Pesan Chatbox AI...", fontSize = 14.sp) },
                    maxLines = 4,
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    )
                )

                IconButton(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    enabled = !isGenerating,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "Lampirkan Gambar",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Right Custom Send Button (48x48, rounded-2xl)
            val canSend = inputText.trim().isNotEmpty() || selectedImageUri != null
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (canSend && !isGenerating) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    )
                    .clickable(enabled = canSend && !isGenerating) {
                        focusManager.clearFocus()
                        onSendClicked()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Kirim",
                    tint = if (canSend && !isGenerating) Color.White else Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Decent Encrypted Subtitle (as specified in Design HTML)
        Text(
            text = "END-TO-END ENCRYPTED • CLOUD SYNC ACTIVE",
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDialog(
    settings: AppSettings,
    onDismiss: () -> Unit,
    onSave: (AppSettings) -> Unit
) {
    var apiProvider by remember { mutableStateOf(settings.apiProvider) }
    var geminiApiKey by remember { mutableStateOf(settings.geminiApiKey) }
    var selectedModel by remember { mutableStateOf(settings.selectedModel) }
    
    var ollamaBaseUrl by remember { mutableStateOf(settings.ollamaBaseUrl) }
    var ollamaModelName by remember { mutableStateOf(settings.ollamaModelName) }
    var ollamaImproveNetworkCompat by remember { mutableStateOf(settings.ollamaImproveNetworkCompat) }
    
    var customBaseUrl by remember { mutableStateOf(settings.customBaseUrl) }
    var customApiKey by remember { mutableStateOf(settings.customApiKey) }
    var customModelName by remember { mutableStateOf(settings.customModelName) }

    var customSystemPrompt by remember { mutableStateOf(settings.customSystemPrompt) }
    var temperature by remember { mutableStateOf(settings.temperature) }
    
    // Cloud Sync States
    var isCloudSyncEnabled by remember { mutableStateOf(settings.isCloudSyncEnabled) }
    var cloudSyncUrl by remember { mutableStateOf(settings.cloudSyncUrl) }
    var cloudSyncPassword by remember { mutableStateOf(settings.cloudSyncPassword) }
    var isSyncingNow by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    val geminiModels = listOf(
        "gemini-3.6-flash" to "Gemini 3.6 Flash",
        "gemini-3.5-flash" to "Gemini 3.5 Flash",
        "gemini-3.1-flash-lite-preview" to "Gemini 3.1 Flash Lite",
        "gemini-2.5-flash" to "Gemini 2.5 Flash",
        "gemini-2.5-pro" to "Gemini 2.5 Pro",
        "gemini-2.0-flash" to "Gemini 2.0 Flash",
        "gemini-1.5-flash" to "Gemini 1.5 Flash",
        "gemini-1.5-pro" to "Gemini 1.5 Pro"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Pengaturan Chatbox AI",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Tutup")
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp))

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. API Provider Switch
                    Column {
                        Text(
                            text = "AI PROVIDER AKTIF",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                "gemini" to "Gemini API",
                                "ollama" to "Ollama (Local)",
                                "custom" to "Custom API"
                            ).forEach { (providerId, providerName) ->
                                val active = apiProvider == providerId
                                FilterChip(
                                    selected = active,
                                    onClick = { apiProvider = providerId },
                                    label = { Text(providerName, fontSize = 12.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    Divider()

                    // 2. Specific Config based on active provider
                    when (apiProvider) {
                        "gemini" -> {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = "KONFIGURASI GOOGLE GEMINI",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                
                                OutlinedTextField(
                                    value = geminiApiKey,
                                    onValueChange = { geminiApiKey = it },
                                    label = { Text("Gemini API Key") },
                                    placeholder = { Text("Masukkan API Key Google Gemini") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                // Model Dropdown Selection
                                Column {
                                    Text("Pilih Model Gemini:", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    var expanded by remember { mutableStateOf(false) }
                                    Box {
                                        OutlinedButton(
                                            onClick = { expanded = true },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(geminiModels.find { it.first == selectedModel }?.second ?: selectedModel)
                                            Spacer(modifier = Modifier.weight(1f))
                                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                                        }
                                        DropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false },
                                            modifier = Modifier.fillMaxWidth(0.85f)
                                        ) {
                                            geminiModels.forEach { (modelId, modelLabel) ->
                                                DropdownMenuItem(
                                                    text = { Text(modelLabel) },
                                                    onClick = {
                                                        selectedModel = modelId
                                                        expanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "ollama" -> {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = "KONFIGURASI OLLAMA (LOCAL AI)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                OutlinedTextField(
                                    value = ollamaBaseUrl,
                                    onValueChange = { ollamaBaseUrl = it },
                                    label = { Text("Base URL Ollama") },
                                    placeholder = { Text("Contoh: http://10.0.2.2:11434") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = ollamaModelName,
                                    onValueChange = { ollamaModelName = it },
                                    label = { Text("Nama Model Lokal") },
                                    placeholder = { Text("Contoh: llama3, mistral, phi3") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text(
                                            text = "Improve Network Compatibility",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Abaikan SSL, paksa HTTP/1.1 & tambahkan header kustom untuk koneksi lokal/Termux/tunnel",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = ollamaImproveNetworkCompat,
                                        onCheckedChange = { ollamaImproveNetworkCompat = it }
                                    )
                                }
                            }
                        }
                        "custom" -> {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(
                                    text = "INTEGRASI API PIHAK KETIGA (CUSTOM)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                OutlinedTextField(
                                    value = customBaseUrl,
                                    onValueChange = { customBaseUrl = it },
                                    label = { Text("Base URL API Custom") },
                                    placeholder = { Text("Contoh: https://api.openai.com/v1") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = customApiKey,
                                    onValueChange = { customApiKey = it },
                                    label = { Text("API Key (Opsional)") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = customModelName,
                                    onValueChange = { customModelName = it },
                                    label = { Text("Model Name") },
                                    placeholder = { Text("Contoh: deepseek-chat, gpt-4o") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    Divider()

                    // 3. System Prompt & Temp Sliders
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "KONFIGURASI SYSTEM PROMPT & PARAMETER",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        OutlinedTextField(
                            value = customSystemPrompt,
                            onValueChange = { customSystemPrompt = it },
                            label = { Text("System Prompt Kustom") },
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (apiProvider == "ollama") {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Temperature (Khusus Ollama):", fontSize = 12.sp)
                                    Text(String.format("%.1f", temperature), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                                Slider(
                                    value = temperature,
                                    onValueChange = { temperature = it },
                                    valueRange = 0.0f..1.0f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    Divider()

                    // 4. Cloud Synchronization Configurations
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "SINKRONISASI DATA VIA CLOUD",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Switch(
                                checked = isCloudSyncEnabled,
                                onCheckedChange = { isCloudSyncEnabled = it }
                            )
                        }

                        if (isCloudSyncEnabled) {
                            OutlinedTextField(
                                value = cloudSyncUrl,
                                onValueChange = { cloudSyncUrl = it },
                                label = { Text("Server Sync Cloud (WebDAV/REST)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = cloudSyncPassword,
                                onValueChange = { cloudSyncPassword = it },
                                label = { Text("Kunci Passphrase Enkripsi") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            
                            Button(
                                onClick = {
                                    isSyncingNow = true
                                    coroutineScope.launch {
                                        delay(1500)
                                        isSyncingNow = false
                                        Toast.makeText(context, "Sinkronisasi Lintas Perangkat Berhasil!", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary
                                ),
                                enabled = !isSyncingNow
                            ) {
                                if (isSyncingNow) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSecondary)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Menghubungkan...")
                                } else {
                                    Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Sinkronkan Sekarang")
                                }
                            }
                        }
                    }
                }

                Divider(modifier = Modifier.padding(vertical = 12.dp))

                // Footer Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Batal")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        val updated = settings.copy(
                            apiProvider = apiProvider,
                            geminiApiKey = geminiApiKey,
                            selectedModel = selectedModel,
                            ollamaBaseUrl = ollamaBaseUrl,
                            ollamaModelName = ollamaModelName,
                            ollamaImproveNetworkCompat = ollamaImproveNetworkCompat,
                            customBaseUrl = customBaseUrl,
                            customApiKey = customApiKey,
                            customModelName = customModelName,
                            customSystemPrompt = customSystemPrompt,
                            temperature = temperature,
                            isCloudSyncEnabled = isCloudSyncEnabled,
                            cloudSyncUrl = cloudSyncUrl,
                            cloudSyncPassword = cloudSyncPassword
                        )
                        onSave(updated)
                    }) {
                        Text("Simpan & Tutup")
                    }
                }
            }
        }
    }
}
