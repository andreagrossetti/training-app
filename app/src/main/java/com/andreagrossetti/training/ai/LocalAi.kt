package com.andreagrossetti.training.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Gemini Nano on the phone, through ML Kit's Prompt API and the system's AICore.
 * Inference only works while the app is in the foreground.
 */
class LocalAi {
    enum class Status { UNAVAILABLE, DOWNLOADABLE, DOWNLOADING, AVAILABLE }

    private val model: GenerativeModel by lazy { Generation.getClient() }

    suspend fun status(): Status = when (model.checkStatus()) {
        FeatureStatus.AVAILABLE -> Status.AVAILABLE
        FeatureStatus.DOWNLOADABLE -> Status.DOWNLOADABLE
        FeatureStatus.DOWNLOADING -> Status.DOWNLOADING
        else -> Status.UNAVAILABLE
    }

    /** Model name and input token limit, for diagnostics. */
    suspend fun info(): String = "${model.getBaseModelName()} · max ${model.getTokenLimit()} token"

    /** Download progress in bytes; completes when the model is ready, throws if it fails. */
    fun download(): Flow<Long> = model.download().map { status ->
        when (status) {
            is DownloadStatus.DownloadFailed -> throw status.e
            is DownloadStatus.DownloadProgress -> status.totalBytesDownloaded
            else -> 0L
        }
    }

    suspend fun generate(prompt: String, system: String? = null, temperature: Float = 0.3f): String {
        if (system != null && !model.isSystemPromptAvailable()) return generate("$system\n\n$prompt", null, temperature)
        val request = if (system != null) {
            generateContentRequest(SystemInstruction(system), TextPart(prompt)) { this.temperature = temperature }
        } else {
            generateContentRequest(TextPart(prompt)) { this.temperature = temperature }
        }
        return model.generateContent(request).candidates.firstOrNull()?.text.orEmpty()
    }

    companion object {
        private val fence = Regex("^\\s*```[a-zA-Z]*\\s*\\n?|\\n?\\s*```\\s*$")

        /** Nano sometimes wraps JSON in ```json … ``` even when told not to: strip the fence before parsing. */
        fun stripCodeFence(text: String): String = fence.replace(text, "").trim()
    }
}
