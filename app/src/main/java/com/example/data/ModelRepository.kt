package com.example.data

import com.example.util.DeviceSpecs

enum class CompatibilityStatus {
    OPTIMAL,
    COMPATIBLE,
    COMPATIBLE_WITH_CAUTION,
    STORAGE_LOW,
    HARDWARE_UNSUPPORTED
}

data class CompatibilityResult(
    val status: CompatibilityStatus,
    val badgeLabel: String,
    val description: String,
    val storageDeficitGb: Double = 0.0,
    val isDownloadBlocked: Boolean = false,
    val requiresWarningDialog: Boolean = false,
    val showsCautionToast: Boolean = false
)

data class LocalAiModel(
    val id: String,
    val name: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeGb: Double,
    val minRamGb: Double,
    val recRamGb: Double,
    val minStorageGb: Double,
    val description: String,
    val category: String
) {
    fun evaluateCompatibility(deviceSpecs: DeviceSpecs): CompatibilityResult {
        // 1. Storage check first (Blocks download completely)
        if (deviceSpecs.freeStorageGb < minStorageGb) {
            val deficit = (minStorageGb - deviceSpecs.freeStorageGb * 10).let { Math.round(it) / 10.0 }
            val deficitText = String.format("%.1f", deficit)
            return CompatibilityResult(
                status = CompatibilityStatus.STORAGE_LOW,
                badgeLabel = "Insufficient Storage",
                description = "Requires $minStorageGb GB free storage. Need ${deficitText} GB more space.",
                storageDeficitGb = deficit,
                isDownloadBlocked = true,
                requiresWarningDialog = false
            )
        }

        // 2. RAM check for Hardware Unsupported (RAM < minRamGb)
        if (deviceSpecs.totalPhysicalRamGb < minRamGb) {
            return CompatibilityResult(
                status = CompatibilityStatus.HARDWARE_UNSUPPORTED,
                badgeLabel = "High Risk: Hardware Below Specs",
                description = "Device RAM (${deviceSpecs.totalPhysicalRamGb} GB) is below minimum required (${minRamGb} GB). Running this model may cause OOM crashes.",
                isDownloadBlocked = false,
                requiresWarningDialog = true
            )
        }

        // 3. 4GB Low-RAM Device caution handling (RAM <= 4.5 GB & model min RAM >= 3.5 GB)
        if (deviceSpecs.isLowRamDevice && minRamGb >= 3.5) {
            return CompatibilityResult(
                status = CompatibilityStatus.COMPATIBLE_WITH_CAUTION,
                badgeLabel = "Heavy for this Device",
                description = "Compatible with caution. Close background applications for optimal response speed.",
                isDownloadBlocked = false,
                showsCautionToast = true
            )
        }

        // 4. Optimal vs Compatible
        if (deviceSpecs.totalPhysicalRamGb >= recRamGb) {
            return CompatibilityResult(
                status = CompatibilityStatus.OPTIMAL,
                badgeLabel = "Recommended",
                description = "Device hardware exceeds recommended specs. Excellent performance expected.",
                isDownloadBlocked = false
            )
        }

        return CompatibilityResult(
            status = CompatibilityStatus.COMPATIBLE,
            badgeLabel = "Supported",
            description = "Device meets hardware minimums. Stable local execution guaranteed.",
            isDownloadBlocked = false
        )
    }
}

object ModelRepository {

    val FLAGSHIP_GEMINI_NANO = LocalAiModel(
        id = "google_gemini_nano",
        name = "Google Gemini Nano (On-Device Offline AI)",
        fileName = "google-gemini-nano-v2.bin",
        downloadUrl = "https://huggingface.co/google/gemma-3-1b-it-gguf/resolve/main/gemma-3-1b-it-q4_k_m.gguf",
        sizeGb = 1.0,
        minRamGb = 3.5,
        recRamGb = 4.0,
        minStorageGb = 1.2,
        description = "Google's official on-device foundation AI model. Operates 100% offline with zero internet for smart reasoning, coding, summaries, and device automation.",
        category = "Google On-Device AI"
    )

    val GOOGLE_GEMMA_ONDEVICE = LocalAiModel(
        id = "google_gemma_1b",
        name = "Google Gemma 3 1B (On-Device AI)",
        fileName = "google-gemma-3-1b-it.bin",
        downloadUrl = "https://huggingface.co/google/gemma-3-1b-it-gguf/resolve/main/gemma-3-1b-it-q4_k_m.gguf",
        sizeGb = 1.1,
        minRamGb = 4.0,
        recRamGb = 6.0,
        minStorageGb = 1.4,
        description = "Google's high-efficiency lightweight on-device model for quick offline tasks, academic help, and text processing.",
        category = "Google On-Device AI"
    )

    // Legacy alias for compatibility
    val FLAGSHIP_QWEN_CODER = FLAGSHIP_GEMINI_NANO

    val catalog: List<LocalAiModel> = listOf(
        FLAGSHIP_GEMINI_NANO,
        GOOGLE_GEMMA_ONDEVICE
    )

    fun getModelById(id: String): LocalAiModel? {
        if (id == "qwen2_5_coder_1_5b" || id == "flagship") return FLAGSHIP_GEMINI_NANO
        return catalog.find { it.id == id }
    }
}
