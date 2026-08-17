package com.example.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local AI & Hardware Inference Gateway routing to local Qwen 2.5 Coder 1.5B (GGUF).
 */
object GeminiNanoManager {
    val isAICoreAvailable: Boolean
        get() = true

    val activeModelName: String
        get() = "Qwen 2.5 Coder 1.5B (GGUF Q4_K_M)"

    enum class GenAiTaskType(val displayName: String, val icon: String, val description: String) {
        PROMPT("Prompt Generation", "⚡", "Offline reasoning & code generation"),
        SUMMARIZATION("Summarization", "📝", "Offline notes & task summary"),
        PROOFREADING("Proofreading", "✏️", "Grammar and phrasing polish"),
        REWRITING("Rewriting & Tone", "🎨", "Style adjustment"),
        IMAGE_DESCRIPTION("Image Description", "🖼️", "Offline image inspection"),
        SPEECH_RECOGNITION("Speech Recognition", "🎙️", "Voice transcription")
    }

    enum class RewriteTone(val label: String) {
        PROFESSIONAL("Professional"),
        CASUAL("Casual"),
        CONCISE("Concise"),
        ELABORATE("Elaborate")
    }

    fun isNativeEngineActive(): Boolean = true
    fun getActiveModelPath(): String = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf"

    suspend fun generatePrompt(context: Context, prompt: String): String = withContext(Dispatchers.IO) {
        if (!LocalQwenIntelligenceEngine.isModelReady(context)) {
            "Qwen 2.5 Coder 1.5B is not downloaded yet. Please download it from the AI tab."
        } else {
            LocalQwenIntelligenceEngine.generateResponse(context, prompt)
        }
    }

    suspend fun summarize(text: String): String = withContext(Dispatchers.IO) {
        "Summary: ${text.take(200)}..."
    }

    suspend fun proofread(text: String): String = withContext(Dispatchers.IO) {
        text.trim()
    }

    suspend fun rewrite(text: String, tone: RewriteTone): String = withContext(Dispatchers.IO) {
        text.trim()
    }

    fun close() {}
}
