package com.medtroniclabs.microcoaching.sherpa

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.R
import com.medtroniclabs.microcoaching.ai.voice.OfflineSttEngine
import com.medtroniclabs.microcoaching.ai.voice.VoiceInputController
import com.medtroniclabs.microcoaching.ui.SdkLocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Offline Bengali STT via sherpa-onnx streaming Zipformer
 * (model: `sherpa-onnx-streaming-zipformer-bn-vosk-2026-02-09`).
 *
 * Lifecycle:
 *   1. [SttModelManager][com.medtroniclabs.microcoaching.ai.voice.stt.SttModelManager]
 *      has downloaded the four required files (`encoder.onnx`, `decoder.onnx`,
 *      `joiner.onnx`, `tokens.txt`) into [modelDir].
 *   2. [ChatVoiceInputController][com.medtroniclabs.microcoaching.ai.voice.ChatVoiceInputController]
 *      constructs us via the factory hook (`Builder.offlineSttEngineFactory`).
 *   3. On first [startListening] we lazily build the [OnlineRecognizer] —
 *      construction is expensive (loads ~90 MB of ONNX weights), so we cache
 *      it for subsequent sessions.
 *   4. Audio capture runs on [Dispatchers.IO]; callbacks marshal to main.
 *
 * **Continuous dictation:** the recognizer's built-in endpointing
 * (rule1/rule2/rule3) fires on every in-sentence pause, not just at the end of
 * dictation — so a non-empty endpoint is treated as a sub-utterance boundary:
 * the decoded text is appended to a running transcript and the stream is reset
 * to keep capturing, instead of ending the session. Only [stopListening] (mic
 * re-tap) or the [MAX_SESSION_MS] safety cap finalises via `onResult`.
 */
class SherpaBengaliEngine(
    private val appContext: Context,
    override val modelDir: File,
) : OfflineSttEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognizerLock = Any()

    @Volatile
    private var recognizer: OnlineRecognizer? = null

    @Volatile
    private var captureScope: CoroutineScope? = null

    override fun isAvailable(): Boolean {
        if (!modelDir.isDirectory) return false
        return REQUIRED_FILES.all { File(modelDir, it).exists() }
    }

    override fun startListening(listener: VoiceInputController.TranscriptionListener) {
        if (!isAvailable()) {
            listener.onError(localized(R.string.chat_voice_error_engine))
            return
        }
        if (!hasRecordPermission()) {
            listener.onError(localized(R.string.chat_voice_permission_denied))
            return
        }
        // Cancel any in-flight session before starting a new one.
        captureScope?.cancel()
        captureScope = null

        val rec = runCatching { ensureRecognizer() }.getOrElse {
            Log.e(TAG, "OnlineRecognizer init failed", it)
            listener.onError(localized(R.string.chat_voice_error_engine))
            return
        }

        val record = runCatching { buildAudioRecord() }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            listener.onError(localized(R.string.chat_voice_error_engine))
            return
        }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        captureScope = scope
        scope.launch {
            val stream = rec.createStream()
            val readBuf = ShortArray(SAMPLE_CHUNK_SIZE)
            var lastPartial = ""
            var accumulatedText = ""
            var done = false
            val sessionStartMs = SystemClock.elapsedRealtime()
            try {
                record.startRecording()
                while (isActive && !done) {
                    if (SystemClock.elapsedRealtime() - sessionStartMs >= MAX_SESSION_MS) {
                        Log.i(TAG, "session exceeded $MAX_SESSION_MS ms — finalising")
                        done = true
                        break
                    }
                    val read = record.read(readBuf, 0, readBuf.size)
                    if (read <= 0) continue
                    val samples = FloatArray(read) { i -> readBuf[i] / 32768f }
                    synchronized(recognizerLock) {
                        stream.acceptWaveform(samples, SAMPLE_RATE)
                        while (rec.isReady(stream)) rec.decode(stream)

                        val text = rec.getResult(stream).text.trim()
                        val merged = if (accumulatedText.isBlank()) text
                        else "${accumulatedText.trim()} ${text.trim()}".trim()
                        if (merged != lastPartial && merged.isNotBlank()) {
                            lastPartial = merged
                            mainHandler.post { listener.onPartial(merged) }
                        }

                        if (rec.isEndpoint(stream)) {
                            val chunkText = rec.getResult(stream).text.trim()
                            rec.reset(stream)
                            if (chunkText.isNotBlank()) {
                                // Sub-utterance boundary — a pause, not the end of
                                // dictation. Fold into the running transcript and
                                // keep listening; do NOT finalise the session here.
                                accumulatedText = if (accumulatedText.isBlank()) chunkText
                                else "${accumulatedText.trim()} ${chunkText.trim()}"
                                lastPartial = accumulatedText
                                mainHandler.post { listener.onPartial(accumulatedText) }
                            } else {
                                // Empty endpoint — false trigger, nothing to fold in.
                                lastPartial = accumulatedText
                            }
                        }
                    }
                }
                // Loop exited via user stop or the session cap — finalise whatever
                // was accumulated plus any trailing decoded text still in flight.
                val finalText = synchronized(recognizerLock) {
                    val trailing = rec.getResult(stream).text.trim()
                    rec.reset(stream)
                    if (trailing.isBlank()) accumulatedText
                    else if (accumulatedText.isBlank()) trailing
                    else "${accumulatedText.trim()} ${trailing.trim()}"
                }
                mainHandler.post { listener.onResult(finalText) }
            } catch (t: Throwable) {
                Log.e(TAG, "capture loop crashed", t)
                mainHandler.post { listener.onError(localized(R.string.chat_voice_error_generic)) }
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
                runCatching { stream.release() }
            }
        }
    }

    override fun stopListening() {
        captureScope?.cancel()
        captureScope = null
    }

    /**
     * Release the loaded recognizer (~90 MB of ONNX weights once Bengali
     * dictation has run). Invoked via the [VoiceInputController.release] chain
     * from `MicroCoachingSDK.shutdown()` when the SDK instance is replaced.
     */
    override fun release() {
        captureScope?.cancel()
        captureScope = null
        synchronized(recognizerLock) {
            recognizer?.release()
            recognizer = null
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun ensureRecognizer(): OnlineRecognizer {
        recognizer?.let { return it }
        return synchronized(recognizerLock) {
            recognizer ?: buildRecognizer().also { recognizer = it }
        }
    }

    private fun buildRecognizer(): OnlineRecognizer {
        val config = OnlineRecognizerConfig().apply {
            featConfig = FeatureConfig().apply {
                sampleRate = SAMPLE_RATE
                featureDim = 80
                dither = 0f
            }
            modelConfig = OnlineModelConfig().apply {
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, "encoder.onnx").absolutePath,
                    decoder = File(modelDir, "decoder.onnx").absolutePath,
                    joiner = File(modelDir, "joiner.onnx").absolutePath,
                )
                tokens = File(modelDir, "tokens.txt").absolutePath
                numThreads = 2
                provider = "cpu"
                modelType = "zipformer2"
                debug = false
            }
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0f),
                rule2 = EndpointRule(true, 1.2f, 0f),
                rule3 = EndpointRule(false, 0f, 20f),
            )
            enableEndpoint = true
            decodingMethod = "greedy_search"
            maxActivePaths = 4
        }
        // Filesystem paths are absolute so the AssetManager argument is unused.
        return OnlineRecognizer(assetManager = null, config = config)
    }

    private fun buildAudioRecord(): AudioRecord {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = (minBufferSize.coerceAtLeast(SAMPLE_CHUNK_SIZE * 2)) * 4
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
    }

    private fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun localized(resId: Int): String = runCatching {
        val language = MicroCoachingSDK.getInstance().language
        SdkLocaleHelper.wrap(appContext, language).getString(resId)
    }.getOrDefault(appContext.getString(resId))

    /** Holder for the last result token so we can surface it on partial polls. */
    private fun OnlineRecognizer.lastPartialOf(stream: OnlineStream): String =
        getResult(stream).text.trim()

    companion object {
        private const val TAG = "SherpaBengaliEngine"
        private const val SAMPLE_RATE = 16_000

        /** 100 ms at 16 kHz mono = 1600 samples. */
        private const val SAMPLE_CHUNK_SIZE = 1600

        /** Hard cap on the wall-clock duration of a single continuous dictation session. */
        private const val MAX_SESSION_MS = 90_000L

        private val REQUIRED_FILES = listOf(
            "encoder.onnx",
            "decoder.onnx",
            "joiner.onnx",
            "tokens.txt",
        )
    }
}
