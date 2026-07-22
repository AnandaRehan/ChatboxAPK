package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_sessions")
data class ChatSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String, // "user" or "model"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val imagePath: String? = null // local file path of copied attachment image
)

@Entity(tableName = "app_settings")
data class AppSettings(
    @PrimaryKey val id: Int = 1,
    val geminiApiKey: String = "",
    val ollamaBaseUrl: String = "http://10.0.2.2:11434", // Default Android emulator local host IP
    val ollamaModelName: String = "llama3",
    val customSystemPrompt: String = "You are a helpful and intelligent AI assistant.",
    val temperature: Float = 0.7f,
    val isDarkMode: Boolean = true,
    val isCloudSyncEnabled: Boolean = false,
    val cloudSyncUrl: String = "https://sync.example.com",
    val cloudSyncPassword: String = "",
    val apiProvider: String = "gemini", // "gemini", "ollama", "custom"
    val selectedModel: String = "gemini-2.5-flash",
    val customBaseUrl: String = "",
    val customApiKey: String = "",
    val customModelName: String = "custom-model"
)
