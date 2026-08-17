package com.example.api

import androidx.annotation.Keep

@Keep
data class GeminiResult(
    val text: String,
    val base64Image: String? = null,
    val videoUri: String? = null,
    val base64Audio: String? = null,
    val modelUsed: String = "No Model"
)

enum class DeepaAiMode(val displayName: String, val icon: String) {
    GENERAL("AI Disabled", "🚫")
}

object GeminiClient {
    fun getApiKey(): String {
        return ""
    }

    suspend fun getGeminiResponse(prompt: String): String {
        return "AI models are disabled."
    }

    suspend fun getGeminiResult(prompt: String): GeminiResult {
        return GeminiResult(text = "AI models are disabled.", modelUsed = "None")
    }

    suspend fun executeDeepaAi(
        prompt: String,
        mode: DeepaAiMode = DeepaAiMode.GENERAL,
        attachedMedia: Pair<String, String>? = null,
        aspectRatio: String = "1:1",
        resolution: String = "2K",
        isMusicClip: Boolean = true
    ): GeminiResult {
        return GeminiResult(text = "AI models are disabled.", modelUsed = "None")
    }
}
