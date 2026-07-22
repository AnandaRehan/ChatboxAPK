package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class ChatRepository(
    private val chatDao: ChatDao,
    private val settingsDao: SettingsDao
) {
    private val TAG = "ChatRepository"

    val allSessions: Flow<List<ChatSession>> = chatDao.getAllSessions().flowOn(Dispatchers.IO)

    val appSettings: Flow<AppSettings?> = settingsDao.getSettingsFlow().flowOn(Dispatchers.IO)

    fun getMessages(sessionId: Long): Flow<List<ChatMessage>> {
        return chatDao.getMessagesForSession(sessionId).flowOn(Dispatchers.IO)
    }

    suspend fun createNewSession(title: String = "Obrolan Baru"): Long = withContext(Dispatchers.IO) {
        val session = ChatSession(title = title)
        chatDao.insertSession(session)
    }

    suspend fun updateSessionTitle(sessionId: Long, title: String) = withContext(Dispatchers.IO) {
        val session = chatDao.getSessionById(sessionId)
        if (session != null) {
            chatDao.updateSession(session.copy(title = title))
        }
    }

    suspend fun deleteSession(sessionId: Long) = withContext(Dispatchers.IO) {
        chatDao.deleteMessagesForSession(sessionId)
        chatDao.deleteSession(sessionId)
    }

    suspend fun saveSettings(settings: AppSettings) = withContext(Dispatchers.IO) {
        settingsDao.insertOrUpdateSettings(settings)
    }

    suspend fun getSettingsOnce(): AppSettings = withContext(Dispatchers.IO) {
        settingsDao.getSettingsOnce() ?: AppSettings()
    }

    /**
     * Sends a message to the active AI service, handling local storage of images if attached,
     * database record persistence, and loading chat history for context.
     */
    suspend fun sendMessage(
        sessionId: Long,
        text: String,
        imageUriStr: String?,
        context: Context
    ): String = withContext(Dispatchers.IO) {
        // 1. If we have an image, make a safe local copy
        var localImagePath: String? = null
        var latestImageB64: String? = null
        if (imageUriStr != null) {
            try {
                val uri = Uri.parse(imageUriStr)
                localImagePath = copyImageToInternalStorage(context, uri)
                if (localImagePath != null) {
                    latestImageB64 = encodeImageToBase64(localImagePath)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy attached image", e)
            }
        }

        // 2. Insert User Message to Database
        val userMessage = ChatMessage(
            sessionId = sessionId,
            role = "user",
            content = text,
            imagePath = localImagePath
        )
        chatDao.insertMessage(userMessage)

        // 3. Load full session messages as history context
        val history = chatDao.getMessagesForSessionOnce(sessionId)

        // 4. Retrieve current Settings
        val settings = settingsDao.getSettingsOnce() ?: AppSettings()

        // 5. Insert initial Assistant Message to Database (empty content)
        val assistantMessage = ChatMessage(
            sessionId = sessionId,
            role = "model",
            content = ""
        )
        val assistantMsgId = chatDao.insertMessage(assistantMessage)

        // 6. Auto-update session title if it's currently generic
        try {
            val session = chatDao.getSessionById(sessionId)
            if (session != null && (session.title == "Obrolan Baru" || session.title.trim().isEmpty())) {
                val proposedTitle = if (text.length > 25) text.substring(0, 22) + "..." else text
                chatDao.updateSession(session.copy(title = proposedTitle))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-update session title", e)
        }

        // 7. Stream AI Response and update assistant message in database in real-time
        var accumulatedText = ""
        var lastDbUpdate = System.currentTimeMillis()

        try {
            AiService.generateChatResponseStream(settings, history, latestImageB64)
                .catch { e ->
                    Log.e(TAG, "Exception during AI stream", e)
                    val errText = "Maaf, terjadi kesalahan saat menghubungi AI: ${e.localizedMessage ?: "Tidak ada koneksi"}"
                    accumulatedText = if (accumulatedText.isEmpty()) errText else "$accumulatedText\n\n[$errText]"
                }
                .collect { chunk ->
                    accumulatedText += chunk
                    val now = System.currentTimeMillis()
                    if (now - lastDbUpdate > 40) { // throttle to ~40ms for smooth 25fps UI updates
                        lastDbUpdate = now
                        chatDao.insertMessage(
                            ChatMessage(
                                id = assistantMsgId,
                                sessionId = sessionId,
                                role = "model",
                                content = accumulatedText
                            )
                        )
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception calling AiService stream", e)
            if (accumulatedText.isEmpty()) {
                accumulatedText = "Maaf, terjadi kesalahan: ${e.localizedMessage}"
            }
        }

        // Final DB update to guarantee complete text is written
        val finalText = accumulatedText.ifEmpty { "Respon dari AI kosong." }
        chatDao.insertMessage(
            ChatMessage(
                id = assistantMsgId,
                sessionId = sessionId,
                role = "model",
                content = finalText
            )
        )

        finalText
    }

    suspend fun clearAllMessagesInSession(sessionId: Long) = withContext(Dispatchers.IO) {
        chatDao.deleteMessagesForSession(sessionId)
    }

    suspend fun regenerateResponseForMessage(
        sessionId: Long,
        targetMessageId: Long
    ): String = withContext(Dispatchers.IO) {
        val allMessages = chatDao.getMessagesForSessionOnce(sessionId)
        val targetIndex = allMessages.indexOfFirst { it.id == targetMessageId }
        if (targetIndex == -1) return@withContext ""

        val targetMsg = allMessages[targetIndex]
        val userMsgIndex = if (targetMsg.role.lowercase() == "user") {
            targetIndex
        } else {
            (targetIndex - 1 downTo 0).firstOrNull { allMessages[it].role.lowercase() == "user" } ?: -1
        }

        if (userMsgIndex == -1) return@withContext ""
        val userMsg = allMessages[userMsgIndex]

        val history = allMessages.subList(0, userMsgIndex + 1)
        val latestImageB64 = userMsg.imagePath?.let { path ->
            encodeImageToBase64(path)
        }

        var assistantMsgId: Long = -1L
        if (userMsgIndex + 1 < allMessages.size && allMessages[userMsgIndex + 1].role.lowercase() == "model") {
            assistantMsgId = allMessages[userMsgIndex + 1].id
        }

        if (assistantMsgId == -1L) {
            val newAssistantMsg = ChatMessage(
                sessionId = sessionId,
                role = "model",
                content = ""
            )
            assistantMsgId = chatDao.insertMessage(newAssistantMsg)
        } else {
            chatDao.insertMessage(
                ChatMessage(
                    id = assistantMsgId,
                    sessionId = sessionId,
                    role = "model",
                    content = ""
                )
            )
        }

        val settings = settingsDao.getSettingsOnce() ?: AppSettings()

        var accumulatedText = ""
        var lastDbUpdate = System.currentTimeMillis()

        try {
            AiService.generateChatResponseStream(settings, history, latestImageB64)
                .catch { e ->
                    Log.e(TAG, "Exception during AI stream regeneration", e)
                    val errText = "Maaf, terjadi kesalahan saat menghubungi AI: ${e.localizedMessage ?: "Tidak ada koneksi"}"
                    accumulatedText = if (accumulatedText.isEmpty()) errText else "$accumulatedText\n\n[$errText]"
                }
                .collect { chunk ->
                    accumulatedText += chunk
                    val now = System.currentTimeMillis()
                    if (now - lastDbUpdate > 40) {
                        lastDbUpdate = now
                        chatDao.insertMessage(
                            ChatMessage(
                                id = assistantMsgId,
                                sessionId = sessionId,
                                role = "model",
                                content = accumulatedText
                            )
                        )
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Exception calling AiService stream regeneration", e)
            if (accumulatedText.isEmpty()) {
                accumulatedText = "Maaf, terjadi kesalahan: ${e.localizedMessage}"
            }
        }

        val finalText = accumulatedText.ifEmpty { "Respon dari AI kosong." }
        chatDao.insertMessage(
            ChatMessage(
                id = assistantMsgId,
                sessionId = sessionId,
                role = "model",
                content = finalText
            )
        )

        finalText
    }

    suspend fun editAndRegenerateUserMessage(
        sessionId: Long,
        userMessageId: Long,
        newContent: String
    ): String = withContext(Dispatchers.IO) {
        val allMessages = chatDao.getMessagesForSessionOnce(sessionId)
        val userMsg = allMessages.find { it.id == userMessageId } ?: return@withContext ""

        chatDao.insertMessage(userMsg.copy(content = newContent))
        regenerateResponseForMessage(sessionId, userMessageId)
    }

    private fun copyImageToInternalStorage(context: Context, uri: Uri): String? {
        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        return try {
            inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val destFile = File(context.filesDir, "chatbox_img_${System.currentTimeMillis()}.jpg")
            outputStream = FileOutputStream(destFile)
            inputStream.copyTo(outputStream)
            destFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error copying image to internal files", e)
            null
        } finally {
            inputStream?.close()
            outputStream?.close()
        }
    }

    private fun encodeImageToBase64(path: String): String? {
        return try {
            val file = File(path)
            if (!file.exists()) return null
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            val maxDimension = 1024
            val scaledBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
                val newWidth = if (ratio > 1) maxDimension else (maxDimension * ratio).toInt()
                val newHeight = if (ratio > 1) (maxDimension / ratio).toInt() else maxDimension
                Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
            } else {
                bitmap
            }
            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }
}
