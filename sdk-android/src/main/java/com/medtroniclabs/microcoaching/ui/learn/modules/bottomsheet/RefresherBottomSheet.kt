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
import com.medtroniclabs.microcoaching.ui.learn.modules.QuickLearnViewModel

/**
 * Bottom sheet for the morning refresher experience.
 *
 * Supports two entry modes controlled by [EntryMode]:
 *
 * - [EntryMode.QUESTION_FIRST] (default, from [QuizRefresherCard] on modules screen):
 *   1 quiz question → lesson cards in sequence → Done / "Next Refresher"
 *
 * - [EntryMode.CARDS_FIRST] (from [MorningCard] on home screen):
 *   lesson cards in sequence → 1 quiz question → Done (no "Next Refresher")
 *
 * The [fromHomeScreen] flag suppresses the "Next Refresher" CTA when true.
 */
class RefresherBottomSheet : BottomSheetDialogFragment() {

    enum class EntryMode { QUESTION_FIRST, CARDS_FIRST }

    override fun getTheme(): Int =
        com.google.android.material.R.style.Theme_Material3_Light_BottomSheetDialog

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val chwId = arguments?.getString(ARG_CHW_ID) ?: CoachingFlowActivity.FALLBACK_CHW_ID
        val fromHomeScreen = arguments?.getBoolean(ARG_FROM_HOME_SCREEN, false) ?: false
        val entryModeName = arguments?.getString(ARG_ENTRY_MODE) ?: EntryMode.QUESTION_FIRST.name
        val entryMode = EntryMode.valueOf(entryModeName)

        val viewModel = ViewModelProvider(
            this,
            QuickLearnViewModel.factory(requireContext().applicationContext, chwId),
        )[QuickLearnViewModel::class.java]

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                SdkLocalizedTheme {
                    RefresherContent(
                        viewModel = viewModel,
                        fromHomeScreen = fromHomeScreen,
                        entryMode = entryMode,
                        onDismiss = { dismissAllowingStateLoss() },
                    )
                }
            }
        }
    }

    companion object {
        const val TAG = "RefresherBottomSheet"
        private const val ARG_CHW_ID = "chw_id"
        private const val ARG_FROM_HOME_SCREEN = "from_home_screen"
        private const val ARG_ENTRY_MODE = "entry_mode"

        fun show(
            fm: FragmentManager,
            chwId: String = MicroCoachingSDK.getInstance().currentCHWId ?: "",
            fromHomeScreen: Boolean = false,
            entryMode: EntryMode = EntryMode.QUESTION_FIRST,
        ): String {
            val sheet = RefresherBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_CHW_ID, chwId)
                    putBoolean(ARG_FROM_HOME_SCREEN, fromHomeScreen)
                    putString(ARG_ENTRY_MODE, entryMode.name)
                }
            }
            sheet.show(fm, TAG)
            return TAG
        }
    }
}
