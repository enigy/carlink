package com.carlink.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.ui.components.CarlinkDialog
import com.carlink.ui.settings.SessionSettings

/**
 * Settings' way out (owner, 10-28). When a setting that only a new connection applies
 * ([SessionSettings]) changed while Settings was open, closing asks first: reset the connection now,
 * or keep the change saved for the next connection. [opened] notes what was saved as Settings
 * opened; [holdForAnswer] decides at the close, against what the session in use was [built] with.
 */
@Stable
internal class SettingsExit(
    private val saved: () -> SessionSettings,
    private val built: () -> SessionSettings?,
) {
    private var openedWith: SessionSettings? = null

    /** The settings the question names; empty while none is up. */
    var asking by mutableStateOf(emptyList<String>())
        private set

    /** Settings opened: what is saved now is what this visit's changes are measured from. */
    fun opened() {
        openedWith = saved()
        asking = emptyList()
    }

    /** Settings asked to close. True when the question is up instead, and the close waits for its answer. */
    fun holdForAnswer(): Boolean {
        val from = openedWith ?: return false
        asking = SessionSettings.toAsk(built(), from, saved())
        return asking.isNotEmpty()
    }

    /** The driver answered (or dismissed the question). */
    fun answered() {
        asking = emptyList()
    }

    companion object {
        /** The question for [names]: "Display mode and Driver's seat take effect at a new connection. …" */
        fun question(names: List<String>): String {
            val many = names.size > 1
            val list = if (many) names.dropLast(1).joinToString(", ") + " and " + names.last() else names.firstOrNull().orEmpty()
            val (verb, them) = if (many) "take" to "them" else "takes" to "it"
            return "$list $verb effect at a new connection. Reset now to apply $them (CarPlay is gone for about 10 seconds), " +
                "or keep $them saved for the next connection."
        }
    }
}

/** A [SettingsExit] reading the saved settings from this context; [built] is the session in use's. */
@Composable
internal fun rememberSettingsExit(built: () -> SessionSettings?): SettingsExit {
    val context = LocalContext.current
    val latest by rememberUpdatedState(built)
    return remember { SettingsExit({ SessionSettings.saved(context) }, { latest() }) }
}

/** [exit]'s question while one is up. Later, Back and a tap outside all keep the change for the next connection. */
@Composable
internal fun SettingsExitPrompt(
    exit: SettingsExit,
    onReset: () -> Unit,
    onLater: () -> Unit,
) {
    val names = exit.asking
    if (names.isEmpty()) return
    CarlinkDialog(
        title = "Reset the connection?",
        text = SettingsExit.question(names),
        confirmLabel = "Reset now",
        onConfirm = {
            logWarn("[UI_ACTION] Settings exit: Reset now for $names", tag = "UI")
            exit.answered()
            onReset()
        },
        onDismiss = {
            logInfo("[UI_ACTION] Settings exit: later for $names", tag = "UI")
            exit.answered()
            onLater()
        },
        icon = Icons.Default.Sync,
        dismissLabel = "Later",
    )
}
