package com.medtroniclabs.microcoaching.ui.chat

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Bottom-sheet wrapper around [CoachingChatFragment] for quick CHW-AI access.
 *
 * Surfaces the existing chat content as a draggable sheet — the host triggers
 * it from a FAB on the home screen (see `docs/designs/chat-with-voice-input.png`).
 *
 * The sheet starts at [BottomSheetBehavior.STATE_EXPANDED] so the whole chat is
 * visible immediately. Tapping outside or swiping down dismisses it.
 *
 * **Host integration:**
 * ```kotlin
 * CoachingChatBottomSheet.show(parentFragmentManager)
 * ```
 *
 * To pre-seed a system context (e.g. patient or scenario context for a focused
 * chat), pass [systemContext] when calling [show]; it is forwarded verbatim to
 * [CoachingChatFragment.newInstance].
 */
class CoachingChatBottomSheet : BottomSheetDialogFragment() {

    override fun getTheme(): Int = com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog

    override fun onCreateDialog(savedInstanceState: Bundle?) =
        super.onCreateDialog(savedInstanceState).apply {
            (this as? BottomSheetDialog)?.behavior?.apply {
                state = BottomSheetBehavior.STATE_EXPANDED
                skipCollapsed = true
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val patientId = arguments?.getString(ARG_PATIENT_ID).orEmpty()
        val systemContext = arguments?.getString(ARG_SYSTEM_CONTEXT).orEmpty()

        val containerView = FragmentContainerView(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        if (savedInstanceState == null) {
            childFragmentManager.beginTransaction()
                .replace(
                    containerView.id,
                    CoachingChatFragment.newInstance(
                        patientId = patientId,
                        systemContext = systemContext,
                    ),
                    CHILD_TAG,
                )
                .commitNow()
        }
        return containerView
    }

    companion object {
        const val TAG = "CoachingChatBottomSheet"
        private const val CHILD_TAG = "CoachingChatBottomSheet.Child"
        private const val ARG_PATIENT_ID = "patient_id"
        private const val ARG_SYSTEM_CONTEXT = "system_context"

        /**
         * Show the sheet. Returns the tag the host can use to dismiss it.
         *
         * @param patientId Optional hashed patient ID for telemetry tagging.
         * @param systemContext Optional pre-seeded system context for focused chat.
         */
        fun show(
            fm: FragmentManager,
            patientId: String = "",
            systemContext: String = "",
        ): String {
            val sheet = CoachingChatBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_PATIENT_ID, patientId)
                    putString(ARG_SYSTEM_CONTEXT, systemContext)
                }
            }
            sheet.show(fm, TAG)
            return TAG
        }
    }
}
