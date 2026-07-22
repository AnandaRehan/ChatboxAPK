package com.example.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

object AiService {
    private const val TAG = "AiService"

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    suspend fun generateChatResponse(
        settings: AppSettings,
        history: List<ChatMessage>,
        latestImageB64: String? = null
    ): String = withContext(Dispatchers.IO) {
        val provider = settings.apiProvider
        Log.d(TAG, "Generating response using provider: $provider")

        try {
            when (provider.lowercase()) {
                "gemini" -> generateGeminiResponse(settings, history, latestImageB64)
                "ollama" -> generateOllamaResponse(settings, history)
                "custom" -> generateCustomResponse(settings, history)
                else -> "Unsupported provider: $provider"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating response", e)
            "Error: ${e.localizedMessage ?: "Unknown error occurred"}"
        }
    }

    private suspend fun generateGeminiResponse(
        settings: AppSettings,
        history: List<ChatMessage>,
        latestImageB64: String?
    ): String {
        val apiKey = settings.geminiApiKey.ifEmpty {
            // Fallback to BuildConfig if empty
            try {
                com.example.BuildConfig.GEMINI_API_KEY
            } catch (e: Exception) {
                ""
            }
        }

        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY") {
            return "Error: Gemini API Key is missing. Please set it in Settings."
        }

        val model = settings.selectedModel
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val rootJson = JSONObject()
        val contentsArray = JSONArray()

        // 1. Convert ChatHistory into Gemini content format
        for (i in history.indices) {
            val msg = history[i]
            val contentObj = JSONObject()
            
            // Map our roles to Gemini roles ("user" or "model")
            val geminiRole = if (msg.role.lowercase() == "user") "user" else "model"
            contentObj.put("role", geminiRole)

            val partsArray = JSONArray()

            // If it's the very last message in history and we have an image attachment, send it as inlineData
            if (i == history.lastIndex && latestImageB64 != null) {
                val imagePart = JSONObject().apply {
                    put("inlineData", JSONObject().apply {
                        put("mimeType", "image/jpeg")
                        put("data", latestImageB64)
                    })
                }
                partsArray.put(imagePart)
            } else if (msg.imagePath != null) {
                // If the message has a saved image, load it and send it
                val base64 = loadAndEncodeImage(msg.imagePath)
                if (base64 != null) {
                    val imagePart = JSONObject().apply {
                        put("inlineData", JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", base64)
                        })
                    }
                    partsArray.put(imagePart)
                }
            }

            // Text part
            val textPart = JSONObject().apply {
                put("text", msg.content)
            }
            partsArray.put(textPart)

            contentObj.put("parts", partsArray)
            contentsArray.put(contentObj)
        }

        rootJson.put("contents", contentsArray)

        // 2. Add System Instruction if present
        if (settings.customSystemPrompt.isNotEmpty()) {
            val systemInstruction = JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", settings.customSystemPrompt)
                }))
            }
            rootJson.put("systemInstruction", systemInstruction)
        }

        // 3. Generation Config
        val config = JSONObject().apply {
            put("temperature", settings.temperature)
        }
        rootJson.put("generationConfig", config)

        val requestBodyStr = rootJson.toString()
        Log.d(TAG, "Gemini Request to $model: $requestBodyStr")

        val request = Request.Builder()
            .url(url)
            .post(requestBodyStr.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            Log.d(TAG, "Gemini Response Code: ${response.code}, Body: $responseBody")

            if (!response.isSuccessful) {
                val errObj = try { JSONObject(responseBody) } catch (e: Exception) { null }
                val errMsg = errObj?.optJSONObject("error")?.optString("message") ?: "HTTP error ${response.code}"
                return "Gemini API Error: $errMsg"
            }

            val respJson = JSONObject(responseBody)
            val candidates = respJson.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val candidate = candidates.getJSONObject(0)
                val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    return parts.getJSONObject(0).optString("text", "No text in candidate")
                }
            }
            return "Empty response from Gemini API."
        }
    }

    private suspend fun generateOllamaResponse(
        settings: AppSettings,
        history: List<ChatMessage>
    ): String {
        var baseUrl = settings.ollamaBaseUrl.trim()
        if (baseUrl.isEmpty()) {
            return "Error: Ollama Base URL is empty in Settings."
        }
        // Ensure standard endpoint path is used
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/"
        }
        val url = baseUrl + "api/chat"

        val rootJson = JSONObject()
        rootJson.put("model", settings.ollamaModelName)
        rootJson.put("stream", false)

        val messagesArray = JSONArray()

        // 1. System prompt
        if (settings.customSystemPrompt.isNotEmpty()) {
            val sysObj = JSONObject().apply {
                put("role", "system")
                put("content", settings.customSystemPrompt)
            }
            messagesArray.put(sysObj)
        }

        // 2. Chat history
        for (msg in history) {
            val msgObj = JSONObject().apply {
                put("role", if (msg.role.lowercase() == "user") "user" else "assistant")
                put("content", msg.content)
            }
            messagesArray.put(msgObj)
        }

        rootJson.put("messages", messagesArray)

        // Options
        val options = JSONObject().apply {
            put("temperature", settings.temperature)
        }
        rootJson.put("options", options)

        val requestBodyStr = rootJson.toString()
        Log.d(TAG, "Ollama Request: $requestBodyStr")

        val request = Request.Builder()
            .url(url)
            .post(requestBodyStr.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            Log.d(TAG, "Ollama Response Code: ${response.code}, Body: $responseBody")

            if (!response.isSuccessful) {
                return "Ollama API Error (Code ${response.code}): $responseBody"
            }

            val respJson = JSONObject(responseBody)
            val messageObj = respJson.optJSONObject("message")
            return messageObj?.optString("content") ?: "Empty response from Ollama API."
        }
    }

    private suspend fun generateCustomResponse(
        settings: AppSettings,
        history: List<ChatMessage>
    ): String {
        var baseUrl = settings.customBaseUrl.trim()
        if (baseUrl.isEmpty()) {
            return "Error: Custom API Base URL is empty in Settings."
        }
        
        // Form the chat/completions endpoint
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/"
        }
        val url = if (baseUrl.contains("chat/completions")) baseUrl else baseUrl + "chat/completions"

        val rootJson = JSONObject()
        rootJson.put("model", settings.customModelName.ifEmpty { "custom-model" })
        rootJson.put("temperature", settings.temperature)

        val messagesArray = JSONArray()

        // 1. System prompt
        if (settings.customSystemPrompt.isNotEmpty()) {
            val sysObj = JSONObject().apply {
                put("role", "system")
                put("content", settings.customSystemPrompt)
            }
            messagesArray.put(sysObj)
        }

        // 2. Chat history
        for (msg in history) {
            val msgObj = JSONObject().apply {
                put("role", if (msg.role.lowercase() == "user") "user" else "assistant")
                put("content", msg.content)
            }
            messagesArray.put(msgObj)
        }

        rootJson.put("messages", messagesArray)

        val requestBodyStr = rootJson.toString()
        Log.d(TAG, "Custom API Request to $url: $requestBodyStr")

        val reqBuilder = Request.Builder()
            .url(url)
            .post(requestBodyStr.toRequestBody(JSON_MEDIA_TYPE))

        if (settings.customApiKey.isNotEmpty()) {
            reqBuilder.addHeader("Authorization", "Bearer ${settings.customApiKey}")
        }

        client.newCall(reqBuilder.build()).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            Log.d(TAG, "Custom API Response Code: ${response.code}, Body: $responseBody")

            if (!response.isSuccessful) {
                return "Custom API Error (Code ${response.code}): $responseBody"
            }

            val respJson = JSONObject(responseBody)
            val choices = respJson.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                val choice = choices.getJSONObject(0)
                val messageObj = choice.optJSONObject("message")
                return messageObj?.optString("content") ?: "Empty message in Custom API choice response"
            }
            return "No choices found in Custom API response."
        }
    }

    private fun loadAndEncodeImage(path: String): String? {
        return try {
            val file = File(path)
            if (!file.exists()) return null
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            
            // Downscale to prevent huge payloads
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
            Log.e(TAG, "Failed to load/encode image $path", e)
            null
        }
    }
}
