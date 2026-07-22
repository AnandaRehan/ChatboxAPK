package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
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

        // 5. Call AI Service
        val aiResponse = AiService.generateChatResponse(settings, history, latestImageB64)

        // 6. Insert AI Response to Database
        val assistantMessage = ChatMessage(
            sessionId = sessionId,
            role = "model",
            content = aiResponse
        )
        chatDao.insertMessage(assistantMessage)

        // 7. Auto-update session title if it's currently generic and this is the first turn
        try {
            val session = chatDao.getSessionById(sessionId)
            if (session != null && (session.title == "Obrolan Baru" || session.title.trim().isEmpty())) {
                val proposedTitle = if (text.length > 25) text.substring(0, 22) + "..." else text
                chatDao.updateSession(session.copy(title = proposedTitle))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-update session title", e)
        }

        aiResponse
    }

    suspend fun clearAllMessagesInSession(sessionId: Long) = withContext(Dispatchers.IO) {
        chatDao.deleteMessagesForSession(sessionId)
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
