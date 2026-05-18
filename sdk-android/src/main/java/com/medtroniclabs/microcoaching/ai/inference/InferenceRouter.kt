package com.medtroniclabs.microcoaching.ai.inference

import android.util.Log
import com.medtroniclabs.microcoaching.MicroCoachingConfig
import com.medtroniclabs.microcoaching.ModelDownloadStrategy
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Selects the appropriate [LLMService] implementation at runtime based on the model
 * file extension and device capability.
 *
 * Routing rules:
 *   - `.task`     → [GemmaService] (MediaPipe Gemma 3; works on all field devices)
 *   - `.litertlm` → [LiteRtLmService] (LiteRT-LM Gemma 4; requires 6+ GB RAM)
 *   - No model    → [isModelAvailable] = false; chat shows "download required" state
 *
 * This is the single point of truth for which LLM is active.
 * Create one instance per SDK session and share it across [ChatViewModel].
 */
class InferenceRouter(private val config: MicroCoachingConfig) {

    private val gemmaService = GemmaService(config.context)
    private val liteRtLmService = LiteRtLmService(config.context)

    /** The currently active service, or null if no model is available. */
    var activeService: LLMService? = null
        private set

    /** Returns true when a model file is present and loaded. */
    val isModelAvailable: Boolean get() = activeService?.isModelLoaded?.value == true

    /**
     * Detect the model file and initialize the appropriate engine.
     * Call this once at app start (or on-first-use, depending on download strategy).
     *
     * @return The loaded [LLMService], or null if no model file is found.
     */
    suspend fun initializeIfModelPresent(): LLMService? {
        val modelFile = resolveModelFile() ?: run {
            Log.i(TAG, "No model file found — chat will show 'download required' state")
            return null
        }

        val service = serviceForFile(modelFile) ?: run {
            Log.w(TAG, "Unrecognised model file extension: ${modelFile.extension}")
            return null
        }

        val llmConfig = LLMConfiguration(
            modelPath = modelFile.absolutePath,
            maxTokens = config.maxInferenceTokens,
            temperature = config.inferenceTemperature,
        )

        runCatching {
            service.loadModel(llmConfig)
            activeService = service
            Log.i(TAG, "Inference engine ready: ${service::class.simpleName} — ${modelFile.name}")
        }.onFailure { cause ->
            Log.e(TAG, "Failed to load model ${modelFile.name}: ${cause.message}")
            activeService = null
        }

        return activeService
    }

    /**
     * Find the model file on the device.
     *
     * Priority order:
     *   1. [MicroCoachingConfig.modelPath] if explicitly set (PROVIDED strategy)
     *   2. First `.task` file in external files dir
     *   3. First `.litertlm` file in external files dir
     */
    private fun resolveModelFile(): File? {
        if (config.modelPath.isNotBlank()) {
            val explicit = File(config.modelPath)
            if (explicit.exists()) return explicit
            Log.w(TAG, "Configured modelPath does not exist: ${config.modelPath}")
        }

        val externalDir = config.context.getExternalFilesDir(null) ?: return null

        // Prefer Gemma 3 (.task) for compatibility with field devices
        return externalDir.listFiles()
            ?.firstOrNull { it.extension == "task" }
            ?: externalDir.listFiles()?.firstOrNull { it.extension == "litertlm" }
    }

    private fun serviceForFile(file: File): LLMService? = when {
        file.name.endsWith(GemmaService.MODEL_EXTENSION) -> gemmaService
        file.name.endsWith(LiteRtLmService.MODEL_EXTENSION) -> liteRtLmService
        else -> null
    }

    /** Release resources for both engines. */
    fun release() {
        gemmaService.unloadModel()
        liteRtLmService.unloadModel()
        activeService = null
    }

    companion object {
        private const val TAG = "InferenceRouter"
    }
}
