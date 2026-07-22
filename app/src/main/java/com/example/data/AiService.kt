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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
        val rawApiKey = settings.geminiApiKey.trim()
        val apiKey = if (rawApiKey.isNotEmpty()) rawApiKey else {
            try {
                com.example.BuildConfig.GEMINI_API_KEY
            } catch (e: Exception) {
                ""
            }
        }

        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY" || apiKey == "null") {
            return "Maaf, API Key Google Gemini belum diatur. Silakan buka Pengaturan (ikon roda gigi) untuk memasukkan API Key Gemini Anda."
        }

        val rawModel = settings.selectedModel.lowercase()
        val model = when {
            rawModel.contains("3.6-flash") -> "gemini-3.6-flash"
            rawModel.contains("3.5-flash") -> "gemini-3.5-flash"
            rawModel.contains("3.1-flash") -> "gemini-3.1-flash-lite-preview"
            rawModel.contains("3-flash") -> "gemini-3-flash-preview"
            rawModel.contains("2.5-flash") -> "gemini-2.5-flash"
            rawModel.contains("2.5-pro") -> "gemini-2.5-pro"
            rawModel.contains("2.0-flash") -> "gemini-2.0-flash"
            rawModel.contains("1.5-flash") -> "gemini-1.5-flash"
            rawModel.contains("1.5-pro") -> "gemini-1.5-pro"
            else -> settings.selectedModel.ifEmpty { "gemini-2.5-flash" }
        }

        fun buildRequestUrl(targetModel: String) =
            "https://generativelanguage.googleapis.com/v1beta/models/$targetModel:generateContent?key=$apiKey"

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

        var request = Request.Builder()
            .url(buildRequestUrl(model))
            .post(requestBodyStr.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        var responseBody = ""
        var responseCode = 0

        client.newCall(request).execute().use { response ->
            responseCode = response.code
            responseBody = response.body?.string() ?: ""
        }

        // If initial request failed due to model issue, fallback to gemini-2.5-flash or gemini-1.5-flash
        if (responseCode == 404 && model != "gemini-1.5-flash") {
            val fallbackModel = "gemini-1.5-flash"
            Log.d(TAG, "Retrying Gemini with fallback model: $fallbackModel")
            request = Request.Builder()
                .url(buildRequestUrl(fallbackModel))
                .post(requestBodyStr.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                responseCode = response.code
                responseBody = response.body?.string() ?: ""
            }
        }

        Log.d(TAG, "Gemini Response Code: $responseCode, Body: $responseBody")

        if (responseCode !in 200..299) {
            val errObj = try { JSONObject(responseBody) } catch (e: Exception) { null }
            val errMsg = errObj?.optJSONObject("error")?.optString("message") ?: "HTTP error $responseCode"
            return "Gemini API Error: $errMsg"
        }

        val respJson = JSONObject(responseBody)
        val candidates = respJson.optJSONArray("candidates")
        if (candidates != null && candidates.length() > 0) {
            val candidate = candidates.getJSONObject(0)
            val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            if (parts != null && parts.length() > 0) {
                return parts.getJSONObject(0).optString("text", "No text response")
            }
        }
        return "Respon dari Gemini kosong."
    }

    private fun resolveOllamaUrl(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return ""

        val uri = try { java.net.URI(trimmed) } catch (e: Exception) { null }
        val path = uri?.path ?: ""

        return if (path.isEmpty() || path == "/") {
            val base = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
            base + "api/chat"
        } else {
            trimmed
        }
    }

    private fun getOkHttpClient(improveCompat: Boolean): OkHttpClient {
        if (!improveCompat) return client

        return try {
            val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
                object : javax.net.ssl.X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                }
            )

            val sslContext = javax.net.ssl.SSLContext.getInstance("SSL").apply {
                init(null, trustAllCerts, java.security.SecureRandom())
            }

            OkHttpClient.Builder()
                .connectTimeout(90, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(90, TimeUnit.SECONDS)
                .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as javax.net.ssl.X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .retryOnConnectionFailure(true)
                .build()
        } catch (e: Exception) {
            client
        }
    }

    private suspend fun generateOllamaResponse(
        settings: AppSettings,
        history: List<ChatMessage>
    ): String {
        val url = resolveOllamaUrl(settings.ollamaBaseUrl)
        if (url.isEmpty()) {
            return "Error: Ollama Base URL is empty in Settings."
        }

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
        Log.d(TAG, "Ollama Request to $url: $requestBodyStr")

        val reqBuilder = Request.Builder()
            .url(url)
            .post(requestBodyStr.toRequestBody(JSON_MEDIA_TYPE))

        if (settings.ollamaImproveNetworkCompat) {
            reqBuilder.addHeader("User-Agent", "Mozilla/5.0 (Android; Mobile)")
            reqBuilder.addHeader("Accept", "*/*")
            reqBuilder.addHeader("Connection", "keep-alive")
        }

        val httpClient = getOkHttpClient(settings.ollamaImproveNetworkCompat)

        httpClient.newCall(reqBuilder.build()).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            Log.d(TAG, "Ollama Response Code: ${response.code}, Body: $responseBody")

            if (!response.isSuccessful) {
                return "Ollama API Error (Code ${response.code}): $responseBody"
            }

            val respJson = JSONObject(responseBody)
            val choices = respJson.optJSONArray("choices")
            val textContent = if (choices != null && choices.length() > 0) {
                val choice0 = choices.getJSONObject(0)
                val msg = choice0.optJSONObject("message")
                msg?.optString("content") ?: choice0.optString("text", "")
            } else {
                val messageObj = respJson.optJSONObject("message")
                messageObj?.optString("content") ?: respJson.optString("response", "Empty response from Ollama API.")
            }
            return if (textContent.isNotEmpty()) textContent else "Respon dari Ollama kosong."
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

    fun generateChatResponseStream(
        settings: AppSettings,
        history: List<ChatMessage>,
        latestImageB64: String? = null
    ): Flow<String> {
        val provider = settings.apiProvider
        Log.d(TAG, "Generating streaming response using provider: $provider")
        return when (provider.lowercase()) {
            "gemini" -> generateGeminiStream(settings, history, latestImageB64)
            "ollama" -> generateOllamaStream(settings, history)
            "custom" -> generateCustomStream(settings, history)
            else -> generateGeminiStream(settings, history, latestImageB64)
        }
    }

    private fun generateGeminiStream(
        settings: AppSettings,
        history: List<ChatMessage>,
        latestImageB64: String?
    ): Flow<String> = flow {
        val rawApiKey = settings.geminiApiKey.trim()
        val apiKey = if (rawApiKey.isNotEmpty()) rawApiKey else {
            try {
                com.example.BuildConfig.GEMINI_API_KEY
            } catch (e: Exception) {
                ""
            }
        }

        if (apiKey.isEmpty() || apiKey == "MY_GEMINI_API_KEY" || apiKey == "null") {
            emit("Maaf, API Key Google Gemini belum diatur. Silakan buka Pengaturan (ikon roda gigi) untuk memasukkan API Key Gemini Anda.")
            return@flow
        }

        val rawModel = settings.selectedModel.lowercase()
        var model = when {
            rawModel.contains("3.6-flash") -> "gemini-3.6-flash"
            rawModel.contains("3.5-flash") -> "gemini-3.5-flash"
            rawModel.contains("3.1-flash") -> "gemini-3.1-flash-lite-preview"
            rawModel.contains("3-flash") -> "gemini-3-flash-preview"
            rawModel.contains("2.5-flash") -> "gemini-2.5-flash"
            rawModel.contains("2.5-pro") -> "gemini-2.5-pro"
            rawModel.contains("2.0-flash") -> "gemini-2.0-flash"
            rawModel.contains("1.5-flash") -> "gemini-1.5-flash"
            rawModel.contains("1.5-pro") -> "gemini-1.5-pro"
            else -> settings.selectedModel.ifEmpty { "gemini-2.5-flash" }
        }

        fun buildUrl(targetModel: String) =
            "https://generativelanguage.googleapis.com/v1beta/models/$targetModel:streamGenerateContent?key=$apiKey&alt=sse"

        val rootJson = JSONObject()
        val contentsArray = JSONArray()

        for (i in history.indices) {
            val msg = history[i]
            val contentObj = JSONObject()
            val geminiRole = if (msg.role.lowercase() == "user") "user" else "model"
            contentObj.put("role", geminiRole)

            val partsArray = JSONArray()
            if (i == history.lastIndex && latestImageB64 != null) {
                val imagePart = JSONObject().apply {
                    put("inlineData", JSONObject().apply {
                        put("mimeType", "image/jpeg")
                        put("data", latestImageB64)
                    })
                }
                partsArray.put(imagePart)
            } else if (msg.imagePath != null) {
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

            val textPart = JSONObject().apply {
                put("text", msg.content)
            }
            partsArray.put(textPart)

            contentObj.put("parts", partsArray)
            contentsArray.put(contentObj)
        }

        rootJson.put("contents", contentsArray)

        if (settings.customSystemPrompt.isNotEmpty()) {
            val systemInstruction = JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", settings.customSystemPrompt)
                }))
            }
            rootJson.put("systemInstruction", systemInstruction)
        }

        val config = JSONObject().apply {
            put("temperature", settings.temperature)
        }
        rootJson.put("generationConfig", config)

        val requestBodyStr = rootJson.toString()
        Log.d(TAG, "Gemini Stream Request to $model")

        var request = Request.Builder()
            .url(buildUrl(model))
            .post(requestBodyStr.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        var response = client.newCall(request).execute()

        // Fallback to gemini-1.5-flash if 404
        if (response.code == 404 && model != "gemini-1.5-flash") {
            response.close()
            model = "gemini-1.5-flash"
            Log.d(TAG, "Retrying Gemini Stream with fallback model: $model")
            request = Request.Builder()
                .url(buildUrl(model))
                .post(requestBodyStr.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            response = client.newCall(request).execute()
        }

        if (!response.isSuccessful) {
            val errBody = response.body?.string() ?: ""
            val errObj = try { JSONObject(errBody) } catch (e: Exception) { null }
            val errMsg = errObj?.optJSONObject("error")?.optString("message") ?: "HTTP error ${response.code}"
            emit("Gemini API Error: $errMsg")
            response.close()
            return@flow
        }

        val body = response.body ?: run {
            emit("Respon dari Gemini kosong.")
            response.close()
            return@flow
        }

        val reader = body.charStream().buffered()
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line?.trim() ?: continue
                if (l.startsWith("data:")) {
                    val jsonStr = l.removePrefix("data:").trim()
                    if (jsonStr.isEmpty() || jsonStr == "[DONE]") continue
                    try {
                        val jsonObj = JSONObject(jsonStr)
                        val candidates = jsonObj.optJSONArray("candidates")
                        if (candidates != null && candidates.length() > 0) {
                            val candidate = candidates.getJSONObject(0)
                            val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
                            if (parts != null && parts.length() > 0) {
                                for (p in 0 until parts.length()) {
                                    val text = parts.getJSONObject(p).optString("text", "")
                                    if (text.isNotEmpty()) {
                                        emit(text)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing Gemini SSE chunk: $jsonStr", e)
                    }
                }
            }
        } finally {
            reader.close()
            response.close()
        }
    }.flowOn(Dispatchers.IO)

    private fun generateOllamaStream(
        settings: AppSettings,
        history: List<ChatMessage>
    ): Flow<String> = flow {
        val url = resolveOllamaUrl(settings.ollamaBaseUrl)
        if (url.isEmpty()) {
            emit("Error: Ollama Base URL is empty in Settings.")
            return@flow
        }

        val rootJson = JSONObject()
        rootJson.put("model", settings.ollamaModelName)
        rootJson.put("stream", true)

        val messagesArray = JSONArray()

        if (settings.customSystemPrompt.isNotEmpty()) {
            val sysObj = JSONObject().apply {
                put("role", "system")
                put("content", settings.customSystemPrompt)
            }
            messagesArray.put(sysObj)
        }

        for (msg in history) {
            val msgObj = JSONObject().apply {
                put("role", if (msg.role.lowercase() == "user") "user" else "assistant")
                put("content", msg.content)
            }
            messagesArray.put(msgObj)
        }

        rootJson.put("messages", messagesArray)

        val options = JSONObject().apply {
            put("temperature", settings.temperature)
        }
        rootJson.put("options", options)

        val requestBodyStr = rootJson.toString()
        Log.d(TAG, "Ollama Stream Request to $url: $requestBodyStr")

        val reqBuilder = Request.Builder()
            .url(url)
            .post(requestBodyStr.toRequestBody(JSON_MEDIA_TYPE))

        if (settings.ollamaImproveNetworkCompat) {
            reqBuilder.addHeader("User-Agent", "Mozilla/5.0 (Android; Mobile)")
            reqBuilder.addHeader("Accept", "*/*")
            reqBuilder.addHeader("Connection", "keep-alive")
        }

        val httpClient = getOkHttpClient(settings.ollamaImproveNetworkCompat)

        val response = httpClient.newCall(reqBuilder.build()).execute()
        if (!response.isSuccessful) {
            val errBody = response.body?.string() ?: ""
            emit("Ollama API Error (Code ${response.code}): $errBody")
            response.close()
            return@flow
        }

        val body = response.body ?: run {
            response.close()
            return@flow
        }

        val reader = body.charStream().buffered()
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                var l = line?.trim() ?: continue
                if (l.isEmpty()) continue
                if (l.startsWith("data:")) {
                    l = l.removePrefix("data:").trim()
                    if (l == "[DONE]" || l.isEmpty()) continue
                }
                try {
                    val jsonObj = JSONObject(l)
                    val choices = jsonObj.optJSONArray("choices")
                    val content = if (choices != null && choices.length() > 0) {
                        val choice0 = choices.getJSONObject(0)
                        val delta = choice0.optJSONObject("delta")
                        val msg = choice0.optJSONObject("message")
                        delta?.optString("content", "") ?: msg?.optString("content", "") ?: ""
                    } else {
                        val messageObj = jsonObj.optJSONObject("message")
                        messageObj?.optString("content") ?: jsonObj.optString("response", "")
                    }
                    if (content.isNotEmpty()) {
                        emit(content)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing Ollama stream chunk: $l", e)
                }
            }
        } finally {
            reader.close()
            response.close()
        }
    }.flowOn(Dispatchers.IO)

    private fun generateCustomStream(
        settings: AppSettings,
        history: List<ChatMessage>
    ): Flow<String> = flow {
        var baseUrl = settings.customBaseUrl.trim()
        if (baseUrl.isEmpty()) {
            emit("Error: Custom API Base URL is empty in Settings.")
            return@flow
        }

        if (!baseUrl.endsWith("/")) {
            baseUrl += "/"
        }
        val url = if (baseUrl.contains("chat/completions")) baseUrl else baseUrl + "chat/completions"

        val rootJson = JSONObject()
        rootJson.put("model", settings.customModelName.ifEmpty { "custom-model" })
        rootJson.put("stream", true)

        val messagesArray = JSONArray()

        if (settings.customSystemPrompt.isNotEmpty()) {
            val sysObj = JSONObject().apply {
                put("role", "system")
                put("content", settings.customSystemPrompt)
            }
            messagesArray.put(sysObj)
        }

        for (msg in history) {
            val msgObj = JSONObject().apply {
                put("role", if (msg.role.lowercase() == "user") "user" else "assistant")
                put("content", msg.content)
            }
            messagesArray.put(msgObj)
        }

        rootJson.put("messages", messagesArray)

        val requestBodyStr = rootJson.toString()
        Log.d(TAG, "Custom API Stream Request to $url: $requestBodyStr")

        val reqBuilder = Request.Builder()
            .url(url)
            .post(requestBodyStr.toRequestBody(JSON_MEDIA_TYPE))

        if (settings.customApiKey.isNotEmpty()) {
            reqBuilder.addHeader("Authorization", "Bearer ${settings.customApiKey}")
        }

        val response = client.newCall(reqBuilder.build()).execute()
        if (!response.isSuccessful) {
            val errBody = response.body?.string() ?: ""
            emit("Custom API Error (Code ${response.code}): $errBody")
            response.close()
            return@flow
        }

        val body = response.body ?: run {
            response.close()
            return@flow
        }

        val reader = body.charStream().buffered()
        try {
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line?.trim() ?: continue
                if (l.startsWith("data:")) {
                    val jsonStr = l.removePrefix("data:").trim()
                    if (jsonStr == "[DONE]") break
                    if (jsonStr.isEmpty()) continue
                    try {
                        val jsonObj = JSONObject(jsonStr)
                        val choices = jsonObj.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val delta = choices.getJSONObject(0).optJSONObject("delta")
                            val content = delta?.optString("content", "") ?: ""
                            if (content.isNotEmpty()) {
                                emit(content)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing Custom API SSE: $jsonStr", e)
                    }
                }
            }
        } finally {
            reader.close()
            response.close()
        }
    }.flowOn(Dispatchers.IO)

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

    suspend fun fetchOllamaModels(baseUrlStr: String, improveCompat: Boolean): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val trimmedUrl = baseUrlStr.trim()
            if (trimmedUrl.isEmpty()) {
                return@withContext Result.failure(Exception("URL API Host masih kosong"))
            }

            val uri = try { java.net.URI(trimmedUrl) } catch (e: Exception) { null }
            val hostBase = if (uri != null && uri.scheme != null && uri.host != null) {
                val portStr = if (uri.port != -1) ":${uri.port}" else ""
                "${uri.scheme}://${uri.host}$portStr"
            } else {
                trimmedUrl.replace(Regex("/v1/chat/completions/?$"), "")
                    .replace(Regex("/api/chat/?$"), "")
                    .removeSuffix("/")
            }

            val endpoints = mutableListOf<String>()
            
            // If user inputted full path like http://127.0.0.1:8080/v1/chat/completions
            if (trimmedUrl.contains("/v1/chat/completions")) {
                endpoints.add(trimmedUrl.replace("/v1/chat/completions", "/v1/models"))
            }
            endpoints.add("$hostBase/api/tags")
            endpoints.add("$hostBase/v1/models")
            endpoints.add("$hostBase/models")

            val httpClient = getOkHttpClient(improveCompat)

            for (endpoint in endpoints.distinct()) {
                try {
                    val reqBuilder = Request.Builder()
                        .url(endpoint)
                        .get()

                    if (improveCompat) {
                        reqBuilder.addHeader("User-Agent", "Mozilla/5.0 (Android; Mobile)")
                        reqBuilder.addHeader("Accept", "*/*")
                        reqBuilder.addHeader("Connection", "keep-alive")
                    }

                    httpClient.newCall(reqBuilder.build()).execute().use { response ->
                        if (response.isSuccessful) {
                            val bodyStr = response.body?.string() ?: ""
                            val json = JSONObject(bodyStr)
                            val resultList = mutableListOf<String>()

                            // 1. Ollama format: { "models": [ { "name": "llama3:latest" }, ... ] }
                            val modelsArr = json.optJSONArray("models")
                            if (modelsArr != null) {
                                for (i in 0 until modelsArr.length()) {
                                    val item = modelsArr.optJSONObject(i)
                                    val name = item?.optString("name") ?: item?.optString("model") ?: item?.optString("id")
                                    if (!name.isNullOrBlank() && !resultList.contains(name)) {
                                        resultList.add(name)
                                    }
                                }
                            }

                            // 2. OpenAI format: { "data": [ { "id": "llama-3.2-3b" }, ... ] }
                            val dataArr = json.optJSONArray("data")
                            if (dataArr != null) {
                                for (i in 0 until dataArr.length()) {
                                    val item = dataArr.optJSONObject(i)
                                    val id = item?.optString("id") ?: item?.optString("name")
                                    if (!id.isNullOrBlank() && !resultList.contains(id)) {
                                        resultList.add(id)
                                    }
                                }
                            }

                            if (resultList.isNotEmpty()) {
                                return@withContext Result.success(resultList)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Failed fetching models from $endpoint: ${e.message}")
                }
            }

            return@withContext Result.failure(Exception("Tidak dapat mengambil model dari $trimmedUrl. Pastikan server lokal berjalan."))
        } catch (e: Exception) {
            return@withContext Result.failure(e)
        }
    }
}
