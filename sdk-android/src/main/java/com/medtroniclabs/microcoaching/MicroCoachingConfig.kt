package com.medtroniclabs.microcoaching

import android.content.Context
import com.medtroniclabs.microcoaching.ai.model.ModelProvider
import com.medtroniclabs.microcoaching.domain.decision.CoachingMode
import com.medtroniclabs.microcoaching.sdk.MicroCoachingDataCallback

/**
 * All client-configurable settings for the MicroCoaching SDK.
 *
 * Constructed exclusively via [MicroCoachingSDK.Builder]. Once built, config is immutable.
 *
 * SPICE integration example:
 * ```kotlin
 * MicroCoachingSDK.Builder(this)
 *     .language(Language.BANGLA)
 *     .backendUrl(BuildConfig.COACHING_BACKEND_URL)
 *     .authToken(SecuredPreference.getToken())
 *     .otelEndpoint(BuildConfig.OTEL_ENDPOINT)
 *     .otelHeaders(mapOf("signoz-access-token" to BuildConfig.SIGNOZ_TOKEN))
 *     .enableTelemetry(BuildConfig.ENABLE_COACHING_TELEMETRY)
 *     .enableChat(true)
 *     .build()
 * ```
 */
@ConsistentCopyVisibility
data class MicroCoachingConfig internal constructor(
    val context: Context,

    // ── Identity ──────────────────────────────────────────────────────────────
    /** Primary language for coaching content. Default: Bangla. */
    val language: Language = Language.BANGLA,
    /** Tenant identifier forwarded to the SDK backend for multi-tenant isolation. */
    val tenantId: String = "",

    // ── Backend ───────────────────────────────────────────────────────────────
    /** Base URL of the MicroCoaching FastAPI backend. */
    val backendUrl: String = "",
    /**
     * Auth token forwarded to the SDK backend as `Authorization: Bearer <token>`.
     * Pass the SPICE JWT here — single auth source of truth.
     */
    val authToken: String = "",
    val connectionTimeoutSeconds: Int = 30,
    val readTimeoutSeconds: Int = 60,

    // ── OTel Telemetry ────────────────────────────────────────────────────────
    /** Set to true to enable OpenTelemetry span export. Safe default is false. */
    val enableTelemetry: Boolean = false,
    /**
     * OTLP/HTTP endpoint for span export, e.g. `"http://signoz:4318"`.
     * Vendor-neutral: works with SigNoz, Grafana Tempo, Jaeger, Datadog, etc.
     */
    val otelEndpoint: String = "",
    /** `service.name` attribute on all exported spans. */
    val otelServiceName: String = "micro-coaching-android",
    /**
     * HTTP headers sent with every OTLP export request.
     * Examples:
     *   SigNoz:        `mapOf("signoz-access-token" to token)`
     *   Grafana Cloud: `mapOf("Authorization" to "Bearer $token")`
     */
    val otelHeaders: Map<String, String> = emptyMap(),
    /** Sampling rate 0.0–1.0. 1.0 captures every span. Reduce for high-volume prod. */
    val otelSamplingRate: Double = 1.0,
    /** How often the batch processor flushes spans to the endpoint (milliseconds). */
    val otelBatchExportIntervalMs: Long = 5_000L,
    /** Maximum spans per export batch. */
    val otelMaxBatchSize: Int = 512,
    /** When true, spans are also printed to Logcat (debug builds only). */
    val enableOtelDebugLogging: Boolean = false,

    // ── LLM / Inference ──────────────────────────────────────────────────────
    /**
     * Absolute path to a pre-provisioned model file.
     * Used when [modelDownloadStrategy] is [ModelDownloadStrategy.PROVIDED].
     * Extension determines which engine is used:
     *   `.task`     → MediaPipe Gemma 3 1B via [GemmaService]
     *   `.litertlm` → LiteRT-LM Gemma 4 E2B via [LiteRtLmService]
     */
    val modelPath: String = "",
    /** Controls when the on-device model is downloaded. */
    val modelDownloadStrategy: ModelDownloadStrategy = ModelDownloadStrategy.ON_FIRST_USE,
    /** When true, model download is restricted to unmetered (Wi-Fi) networks. */
    val wifiOnlyModelDownload: Boolean = true,

    // ── Model Download Providers ──────────────────────────────────────────────
    /**
     * Ordered list of providers tried when downloading the model.
     * The SDK moves to the next provider if the current one fails.
     * Default: Backend → HuggingFace
     *
     * Override to change priority or disable a provider:
     * ```kotlin
     * .modelProviders(listOf(ModelProvider.HuggingFace, ModelProvider.Backend))
     * ```
     */
    val modelProviders: List<ModelProvider> = ModelProvider.DEFAULT_ORDER,
    /**
     * HuggingFace Hub access token for downloading gated models.
     * Required for `litert-community/Gemma3-1B-IT` and similar restricted repos.
     *
     * Obtain from https://huggingface.co/settings/tokens
     * For the sample app, set `HUGGING_FACE_TOKEN` in `local.properties`.
     */
    val huggingFaceToken: String = "",
    /**
     * Direct download URL for the HuggingFace model file.
     * Default: Gemma3-1B-IT INT4 in LiteRT format (~1.1 GB).
     * Override to target a different model revision or format.
     */
    val huggingFaceModelUrl: String = ModelProvider.DEFAULT_HF_MODEL_URL,

    /** Maximum tokens the LLM generates per response. */
    val maxInferenceTokens: Int = 512,
    /** LLM sampling temperature. Higher = more creative; lower = more deterministic. */
    val inferenceTemperature: Float = 0.6f,

    // ── Feature Flags ─────────────────────────────────────────────────────────
    /** Enable the AI chat fragment (UC-2 entry point). */
    val enableChat: Boolean = true,
    /** Enable Bengali voice input/output (Phase 6 — disabled by default). */
    val enableVoice: Boolean = false,
    /** Enable micro-learning module UC-1 (Phase 3 — disabled by default). */
    val enableLearnModule: Boolean = false,
    /** Enable counselling apply module UC-2 (Phase 4 — disabled by default). */
    val enableApplyModule: Boolean = false,
    /** Enable telemetry measure module UC-3 (Phase 5 — disabled by default). */
    val enableMeasureModule: Boolean = false,

    // ── v3 Behavioural / Trigger thresholds ──────────────────────────────────
    // Defaults sourced from Implementation Plan v3.3 §W-0. All knobs are
    // overridable per-module at runtime via the `config_threshold` sync resource;
    // these values are the fallback when no server-side override is cached.

    /** Quiz pass mark, expressed as percent (0–100). */
    val quizPassThreshold: Int = 70,
    /** Consecutive failed attempts within [escalationWindowDays] that escalate to the supervisor. */
    val escalationFailureCount: Int = 3,
    /** Window over which [escalationFailureCount] is evaluated. */
    val escalationWindowDays: Int = 30,
    /** Days after a passed quiz before the same module is re-surfaced for reinforcement. */
    val periodicRefreshDays: Int = 90,
    /** Gap-trigger occurrences required inside [triggerWindowDays] before firing. */
    val triggerOccurrenceThreshold: Int = 2,
    /** Window over which [triggerOccurrenceThreshold] is evaluated. */
    val triggerWindowDays: Int = 14,

    // ── UI ────────────────────────────────────────────────────────────────────
    /**
     * Controls the colour scheme used by SDK-owned screens (e.g. [CoachingFlowActivity]).
     * Default: follows the system setting.
     */
    val uiTheme: CoachingUiTheme = CoachingUiTheme.SYSTEM,

    // ── Data Access ───────────────────────────────────────────────────────────
    /**
     * Optional push-pattern callback. SPICE registers this to receive coaching
     * events without holding a direct Room dependency on SDK internals.
     *
     * Pull-pattern alternative: inject [CoachingDataRepository] via Hilt:
     * ```kotlin
     * @Provides @Singleton
     * fun provideCoachingDataRepo(): CoachingDataRepository =
     *     MicroCoachingSDK.getInstance().dataRepository
     * ```
     */
    val dataCallback: MicroCoachingDataCallback? = null,

    // ── Testing / Override ────────────────────────────────────────────────────
    /**
     * When set, [ModeSelector] bypasses all dynamic checks and always returns this mode.
     * Use in the SPICE dev build or sample app to test EDGE/ONLINE flows without
     * physically going offline or having a model loaded.
     *
     * Example:
     * ```kotlin
     * .forceMode(CoachingMode.EDGE)  // always runs Gemma even when Wi-Fi is on
     * ```
     * Leave null (default) in production.
     */
    val forcedMode: CoachingMode? = null,
)

/** Supported coaching interface languages. */
enum class Language(val bcp47: String) {
    BANGLA("bn-BD"),
    ENGLISH("en-US");

    companion object {
        fun fromBcp47(code: String): Language =
            entries.firstOrNull { it.bcp47.startsWith(code, ignoreCase = true) }
                ?: ENGLISH
    }
}

/** Controls the colour scheme applied to SDK-owned UI screens. */
enum class CoachingUiTheme {
    /** Follow the device system setting (default). */
    SYSTEM,
    /** Always use light theme regardless of system setting. */
    LIGHT,
    /** Always use dark theme regardless of system setting. */
    DARK,
}

/** Controls when the on-device Gemma model is downloaded to the device. */
enum class ModelDownloadStrategy {
    /**
     * Download as soon as the SDK initializes.
     * Recommended for Bangladesh onboarding flow where Wi-Fi is available upfront.
     */
    ON_SDK_INIT,

    /**
     * Download only when the user first opens a feature requiring inference.
     * Shows a download progress UI before the chat is available.
     */
    ON_FIRST_USE,

    /**
     * The host app or MDM system pre-provisions the model file.
     * SDK reads [MicroCoachingConfig.modelPath] directly — no download.
     */
    PROVIDED,

    /**
     * Download triggered manually by calling [ModelManager.triggerDownload].
     * Use this when the host app controls the onboarding flow.
     */
    MANUAL,
}
