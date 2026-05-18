package com.medtroniclabs.microcoaching.ui.learn.modules.bottomsheet

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.medtroniclabs.microcoaching.MicroCoachingSDK
import com.medtroniclabs.microcoaching.ui.SdkLocalizedTheme
import com.medtroniclabs.microcoaching.ui.flow.CoachingFlowActivity
import com.medtroniclabs.microcoaching.ui.learn.LearnViewModel

/**
 * Partial-height bottom sheet that runs the quiz portion of a refresher
 * module without the lesson-content stop. Callers prime the underlying
 * [LearnViewModel] via [LearnViewModel.selectModuleForQuiz] **before**
 * calling [show] so the sheet renders straight into `QuizInProgress`.
 *
 * Sheet height: default Material 3 peek (~half screen) with drag-to-expand.
 * Tapping outside or dragging down dismisses.
 *
 * **Host usage:**
 * ```kotlin
 * learnViewModel.selectModuleForQuiz(refresherModule)
 * RefresherQuizBottomSheet.show(parentFragmentManager, chwId)
 * ```
 */
class RefresherQuizBottomSheet : BottomSheetDialogFragment() {

    override fun getTheme(): Int =
        com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val chwId = arguments?.getString(ARG_CHW_ID)
            ?: CoachingFlowActivity.FALLBACK_CHW_ID
        val viewModel = ViewModelProvider(
            requireActivity(),
            LearnViewModel.factory(requireContext().applicationContext, chwId),
        )[LearnViewModel::class.java]

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                // SdkLocalizedTheme ensures stringResource() inside the sheet
                // resolves from the SDK-configured language.
                SdkLocalizedTheme {
                    RefresherQuizContent(
                        viewModel = viewModel,
                        onDismiss = { dismissAllowingStateLoss() },
                    )
                }
            }
        }
    }

    companion object {
        const val TAG = "RefresherQuizBottomSheet"
        private const val ARG_CHW_ID = "chw_id"

        fun show(
            fm: FragmentManager,
            chwId: String = MicroCoachingSDK.getInstance().currentCHWId ?: "",
        ): String {
            val sheet = RefresherQuizBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_CHW_ID, chwId) }
            }
            sheet.show(fm, TAG)
            return TAG
        }
    }
}
