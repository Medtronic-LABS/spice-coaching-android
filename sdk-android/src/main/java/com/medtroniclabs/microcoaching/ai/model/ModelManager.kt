package com.medtroniclabs.microcoaching.ai.model

import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.medtroniclabs.microcoaching.MicroCoachingConfig
import com.medtroniclabs.microcoaching.ModelDownloadStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/**
 * Manages the on-device model lifecycle: detection, download scheduling, and integrity verification.
 *
 * Download is performed by [ModelDownloadWorker] via WorkManager — survives process death
 * and respects [MicroCoachingConfig.wifiOnlyModelDownload].
 *
 * Provider fallback order is driven by [MicroCoachingConfig.modelProviders].
 * Default: Backend → HuggingFace.
 *
 * SHA-256 verification is run after every download to detect corrupted files.
 */
class ModelManager(private val config: MicroCoachingConfig) {

    private val _state = MutableStateFlow<ModelState>(ModelState.Idle)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    // Long-lived scope for observing WorkManager state. Lives as long as ModelManager (app lifetime).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Returns the first model file found in external files dir, or null. */
    fun findLocalModel(): File? {
        val dir = config.context.getExternalFilesDir(null) ?: return null
        return dir.listFiles()?.firstOrNull { it.extension == "task" || it.extension == "litertlm" }
    }

    /** Returns true if a model file is present on device (regardless of integrity). */
    fun isModelPresent(): Boolean = findLocalModel() != null

    /**
     * Schedule a download via WorkManager if no model is present.
     * Download respects [MicroCoachingConfig.wifiOnlyModelDownload].
     *
     * No-op if:
     *   - A model file already exists on device
     *   - Strategy is [ModelDownloadStrategy.MANUAL] or [ModelDownloadStrategy.PROVIDED]
     */
    fun scheduleDownloadIfNeeded() {
        if (config.modelDownloadStrategy == ModelDownloadStrategy.PROVIDED ||
            config.modelDownloadStrategy == ModelDownloadStrategy.MANUAL
        ) return

        if (isModelPresent()) {
            Log.i(TAG, "Model already present — skipping download")
            _state.value = ModelState.Ready(findLocalModel()!!)
            return
        }

        scheduleDownload()
    }

    /**
     * Manually trigger model download. Use when strategy is [ModelDownloadStrategy.MANUAL].
     * Safe to call multiple times — WorkManager deduplicates by unique work name.
     */
    fun triggerDownload() {
        scheduleDownload()
    }

    private fun scheduleDownload() {
        val networkType = if (config.wifiOnlyModelDownload) {
            NetworkType.UNMETERED
        } else {
            NetworkType.CONNECTED
        }

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()

        val providerKeys = config.modelProviders.map { it.toKey() }.toTypedArray()

        val workRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    ModelDownloadWorker.KEY_PROVIDERS to providerKeys,
                    ModelDownloadWorker.KEY_BACKEND_URL to config.backendUrl,
                    ModelDownloadWorker.KEY_AUTH_TOKEN to config.authToken,
                    ModelDownloadWorker.KEY_HF_TOKEN to config.huggingFaceToken,
                    ModelDownloadWorker.KEY_HF_URL to config.huggingFaceModelUrl,
                )
            )
            .addTag(DOWNLOAD_TAG)
            .build()

        WorkManager.getInstance(config.context)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, workRequest)

        _state.value = ModelState.Downloading(progressPercent = 0)
        Log.i(
            TAG,
            "Model download scheduled — providers=${config.modelProviders.map { it::class.simpleName }}, wifiOnly=${config.wifiOnlyModelDownload}"
        )

        // Observe WorkInfo to keep _state in sync as the worker progresses
        scope.launch {
            WorkManager.getInstance(config.context)
                .getWorkInfosForUniqueWorkFlow(UNIQUE_WORK_NAME)
                .collect { infoList ->
                    val info = infoList.firstOrNull() ?: return@collect
                    when (info.state) {
                        WorkInfo.State.RUNNING -> {
                            val pct = info.progress.getInt(ModelDownloadWorker.KEY_PROGRESS, 0)
                            _state.value = ModelState.Downloading(pct)
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            val path = info.outputData.getString(ModelDownloadWorker.KEY_FILE_PATH)
                            val file = path?.let { File(it) }?.takeIf { it.exists() }
                            _state.value = if (file != null) {
                                Log.i(TAG, "Model ready at: ${file.absolutePath}")
                                ModelState.Ready(file)
                            } else {
                                ModelState.DownloadFailed("Model file missing after download completed")
                            }
                        }
                        WorkInfo.State.FAILED -> {
                            val error = info.outputData.getString(ModelDownloadWorker.KEY_ERROR)
                                ?: "All providers failed"
                            Log.e(TAG, "Download failed: $error")
                            _state.value = ModelState.DownloadFailed(error)
                        }
                        WorkInfo.State.CANCELLED -> {
                            _state.value = ModelState.DownloadFailed("Download cancelled")
                        }
                        else -> { /* ENQUEUED / BLOCKED — waiting for constraints, no state change */ }
                    }
                }
        }
    }

    /**
     * Called when the inference engine fails to load the model file (e.g. corrupt download).
     * Deletes the file so the next launch or user action triggers a fresh download.
     */
    fun onModelLoadFailed(reason: String = "Model file corrupt — please download again") {
        findLocalModel()?.let { file ->
            if (file.delete()) {
                Log.w(TAG, "Deleted corrupt model file: ${file.name}")
            }
        }
        _state.value = ModelState.LoadFailed(reason)
    }

    /**
     * Verify SHA-256 integrity of the given model file.
     * @param expectedHash Expected hex digest, or null to skip verification.
     */
    fun verifyIntegrity(file: File, expectedHash: String?): Boolean {
        if (expectedHash == null) return true
        val actualHash = file.sha256()
        val ok = actualHash.equals(expectedHash, ignoreCase = true)
        if (!ok) Log.e(TAG, "Integrity check failed for ${file.name}: expected=$expectedHash actual=$actualHash")
        return ok
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var read: Int
            while (stream.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "ModelManager"
        const val DOWNLOAD_TAG = "microcoaching_model_download"
        const val UNIQUE_WORK_NAME = "microcoaching_model_download"
    }
}
