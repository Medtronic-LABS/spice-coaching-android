package com.medtroniclabs.microcoaching.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.medtroniclabs.microcoaching.Language
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.ai.voice.VoiceInputController
import com.medtroniclabs.microcoaching.ui.SdkLocaleHelper
import com.medtroniclabs.microcoaching.ui.screens.ChatScreen
import com.medtroniclabs.microcoaching.ui.theme.MicroCoachingTheme

/**
 * Exportable AI coaching chat Fragment.
 *
 * Uses Jetpack Compose internally via [ComposeView], making it embeddable
 * in SPICE's existing XML-based layouts without any Compose migration.
 *
 * SDK is DI-framework-agnostic — no Hilt required. The Fragment creates
 * its own ViewModel via [ChatViewModel.Factory].
 *
 * If SPICE uses Hilt, it can provide [ChatViewModel] via Hilt by wrapping
 * this pattern in its own Fragment subclass.
 *
 * **SPICE integration — embed in any Activity or Fragment:**
 * ```kotlin
 * supportFragmentManager.beginTransaction()
 *     .add(R.id.coaching_container,
 *          CoachingChatFragment.newInstance(patientId = patientTrackId.toString()))
 *     .commit()
 * ```
 *
 * **With patient context (for UC-2 Apply):**
 * ```kotlin
 * CoachingChatFragment.newInstance(
 *     patientId = patientTrackId.toString(),
 *     systemContext = "Focus on hypertension medication adherence counseling.",
 * )
 * ```
 */
class CoachingChatFragment : Fragment() {

    private lateinit var viewModel: ChatViewModel

    /**
     * Real STT controller registered by the host (or null). When null we don't
     * render the mic icon at all — see comment near [onMicTap] wiring below.
     */
    private var voiceInput: VoiceInputController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val patientId = arguments?.getString(ARG_PATIENT_ID) ?: ""
        val systemContext = arguments?.getString(ARG_SYSTEM_CONTEXT) ?: ""
        val factory = ChatViewModel.Factory(
            application = requireActivity().application,
            patientId = patientId,
            systemContext = systemContext,
        )
        viewModel = ViewModelProvider(this, factory)[ChatViewModel::class.java]
        // Voice input: only enable if the host has registered a real controller
        // AND `enableVoice` is on. The previous behaviour was to fall back to a
        // [NoOpVoiceInputController] which rendered a misleading "coming soon"
        // toast — confirmed unhelpful by the design review. STT is Phase 6.
        val sdk = MicroCoachingSDK.getInstance()
        voiceInput = sdk.voiceInputController?.takeIf {
            sdk.config.enableVoice && it.isAvailable()
        }
        if (sdk.voiceInputController == null && sdk.config.enableVoice) {
            // Voice was opted in but no controller was registered — this is an
            // integration mistake in the host. Surface in logs so it's catchable.
            android.util.Log.w(
                "CoachingChatFragment",
                "config.enableVoice=true but no VoiceInputController registered — mic hidden.",
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return ComposeView(requireContext()).apply {
            // Dispose Compose when the Fragment's View is destroyed to avoid memory leaks
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            setContent {
                val langCtx = SdkLocaleHelper.wrap(
                    requireContext(),
                    MicroCoachingSDK.getInstance().language,
                )
                CompositionLocalProvider(LocalContext provides langCtx) {
                    MicroCoachingTheme {
                        val uiState by viewModel.uiState.collectAsState()
                        ChatScreen(
                            uiState = uiState,
                            onSendMessage = { viewModel.sendMessage(it) },
                            onSendSuggested = { sq ->
                                val sdk = MicroCoachingSDK.getInstance()
                                val text = when (sdk.language) {
                                    Language.BANGLA ->
                                        sq.banglaQuestion.ifBlank { sq.question }
                                    Language.ENGLISH ->
                                        sq.question.ifBlank { sq.banglaQuestion }
                                }
                                viewModel.sendMessage(text, scenarioId = sq.scenarioId)
                            },
                            onRequestDownload = viewModel::requestModelDownload,
                            onSpeakMessage = viewModel::speakText,
                            // Mic icon is rendered only when a real STT controller is
                            // wired (Phase 6). Passing null keeps it hidden — see
                            // [com.medtroniclabs.microcoaching.ui.common.ChatInputBar].
                            onMicTap = voiceInput?.let { controller ->
                                {
                                    controller.startListening(
                                        object : VoiceInputController.TranscriptionListener {
                                            override fun onResult(transcript: String) {
                                                if (transcript.isNotBlank()) viewModel.sendMessage(transcript)
                                            }
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val ARG_PATIENT_ID = "patient_id"
        private const val ARG_SYSTEM_CONTEXT = "system_context"

        /**
         * Create a new instance of [CoachingChatFragment].
         *
         * @param patientId Anonymized patient ID (use patientTrackId from SPICE).
         *   Pass empty string for non-patient-specific coaching.
         * @param systemContext Optional coaching context / system prompt override.
         */
        fun newInstance(
            patientId: String = "",
            systemContext: String = "",
        ): CoachingChatFragment {
            return CoachingChatFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_PATIENT_ID, patientId)
                    putString(ARG_SYSTEM_CONTEXT, systemContext)
                }
            }
        }
    }
}
