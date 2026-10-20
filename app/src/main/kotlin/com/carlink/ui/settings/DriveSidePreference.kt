package com.carlink.ui.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which side the driver sits (Carlink2's Drive Side). Sent to the box in the SUBSCRIBE config as
 * `rightHandDrive`, which the box puts in CarPlay's `/info`: CarPlay then puts its dock and status
 * bar on the driver's side. The config only travels at session start, so a change applies at the
 * next connection. Persisted by [code], never ordinal.
 */
enum class DriveSide(
    val code: String,
    override val label: String,
) : Labelled {
    LEFT("left", "Left"),
    RIGHT("right", "Right"),
    ;

    companion object {
        val DEFAULT = LEFT

        fun fromCode(code: String?): DriveSide = entries.find { it.code == code } ?: DEFAULT
    }
}

/** The persisted [DriveSide], in the style of [BackgroundLogoStore]. One instance per process. */
class DriveSideStore private constructor(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(DriveSide.fromCode(prefs.getString(KEY_SIDE, null)))

    val flow: StateFlow<DriveSide> = state.asStateFlow()

    val current: DriveSide get() = state.value

    fun set(side: DriveSide) {
        prefs.edit().putString(KEY_SIDE, side.code).apply()
        state.value = side
    }

    companion object {
        // PERSISTENCE CONTRACT: renaming either orphans the stored choice.
        private const val PREFS_NAME = "carlink_drive_side"
        private const val KEY_SIDE = "side"

        @Volatile private var instance: DriveSideStore? = null

        fun get(context: Context): DriveSideStore =
            instance ?: synchronized(this) {
                instance ?: DriveSideStore(context).also { instance = it }
            }
    }
}
