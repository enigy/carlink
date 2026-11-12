package com.carlink.ui.pane

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.carlink.logging.logInfo

/**
 * A call ringing in brings its card back up: a waiting call too, should the driver have swiped away from the first one
 * (owner, 12 Nov: the pane swipes during a call).
 */
@Composable
internal fun FollowRinging(
    ringing: Boolean,
    pages: List<PaneCard>,
    shown: PaneCard,
    show: (PaneCard) -> Unit,
) {
    LaunchedEffect(ringing) {
        if (ringing && PaneCard.CALL in pages && shown != PaneCard.CALL) {
            logInfo("[UI_ACTION] Pane: a call rings, showing CALL", tag = "UI")
            show(PaneCard.CALL)
        }
    }
}
