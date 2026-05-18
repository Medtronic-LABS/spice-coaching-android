package com.medtroniclabs.microcoaching.ai.model

import java.io.File

/** Represents the current state of the on-device model lifecycle. */
sealed class ModelState {

    /** No model download has started and no model file is present. */
    object Idle : ModelState()

    /** Model download is in progress. */
    data class Downloading(val progressPercent: Int) : ModelState()

    /** Download failed. */
    data class DownloadFailed(val reason: String) : ModelState()

    /** Model file is present and integrity-verified. Ready for inference. */
    data class Ready(val modelFile: File) : ModelState()

    /** Model failed to load into the inference engine. */
    data class LoadFailed(val reason: String) : ModelState()
}
