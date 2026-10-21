package com.carlink.ui.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How far before a turn the alert starts (Settings › Cluster › Start alerting), as the owner set it:
 * a distance at 25 mph and one at 60 mph. Between the two, [com.carlink.navigation.TurnAlert]
 * interpolates the warning TIME, so the distance grows smoothly with speed.
 */
enum class AlertTiming(
    val code: String,
    override val label: String,
    val milesAt25: Double,
    val milesAt60: Double,
) : Labelled {
    EARLIER("earlier", "Earlier", 0.5, 2.0),
    STANDARD("standard", "Standard", 0.25, 1.0),
    LATER("later", "Later", 0.1, 0.5),
    ;

    companion object {
        fun fromCode(code: String?): AlertTiming = entries.firstOrNull { it.code == code } ?: STANDARD
    }
}

/** One full flash: the normal frame, then the colored frame, half of [cycleMs] each. */
enum class FlashSpeed(
    val code: String,
    override val label: String,
    val cycleMs: Long,
) : Labelled {
    SLOW("slow", "Slow", 1500L),
    MEDIUM("medium", "Medium", 1000L),
    FAST("fast", "Fast", 500L),
    ;

    companion object {
        fun fromCode(code: String?): FlashSpeed = entries.firstOrNull { it.code == code } ?: MEDIUM
    }
}

/** The colored frame's inlay. */
enum class InlayColor(
    val code: String,
    override val label: String,
    val argb: Int,
) : Labelled {
    RED("red", "Red", 0xFFFF3B30.toInt()),
    AMBER("amber", "Amber", 0xFFFF9F0A.toInt()),
    GREEN("green", "Green", 0xFF34C759.toInt()),
    CYAN("cyan", "Cyan", 0xFF32D2F5.toInt()),
    MAGENTA("magenta", "Magenta", 0xFFFF2D92.toInt()),
    ;

    companion object {
        fun fromCode(code: String?): InlayColor = entries.firstOrNull { it.code == code } ?: RED
    }
}

/** Inlay keeps the icon's white outline; Outline adds a colored edge around it as well. */
enum class AlertStyle(
    val code: String,
    override val label: String,
) : Labelled {
    INLAY("inlay", "Inlay"),
    OUTLINE("outline", "Outline"),
    ;

    companion object {
        fun fromCode(code: String?): AlertStyle = entries.firstOrNull { it.code == code } ?: INLAY
    }
}

/** Settings › Cluster › Turn alert. Off by default. */
data class TurnAlertConfig(
    val enabled: Boolean = false,
    val timing: AlertTiming = AlertTiming.STANDARD,
    val speed: FlashSpeed = FlashSpeed.MEDIUM,
    val color: InlayColor = InlayColor.RED,
    val style: AlertStyle = AlertStyle.INLAY,
    val solidLast: Boolean = true,
)

/** Durable [TurnAlertConfig]; read by the cluster session ([com.carlink.navigation.TurnAlert]). */
class TurnAlertStore private constructor(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val state =
        MutableStateFlow(
            TurnAlertConfig(
                enabled = prefs.getBoolean(KEY_ENABLED, false),
                timing = AlertTiming.fromCode(prefs.getString(KEY_TIMING, null)),
                speed = FlashSpeed.fromCode(prefs.getString(KEY_SPEED, null)),
                color = InlayColor.fromCode(prefs.getString(KEY_COLOR, null)),
                style = AlertStyle.fromCode(prefs.getString(KEY_STYLE, null)),
                solidLast = prefs.getBoolean(KEY_SOLID_LAST, true),
            ),
        )

    val flow: StateFlow<TurnAlertConfig> = state.asStateFlow()

    val current: TurnAlertConfig get() = state.value

    fun setEnabled(on: Boolean) = update(current.copy(enabled = on))

    fun setTiming(timing: AlertTiming) = update(current.copy(timing = timing))

    fun setSpeed(speed: FlashSpeed) = update(current.copy(speed = speed))

    fun setColor(color: InlayColor) = update(current.copy(color = color))

    fun setStyle(style: AlertStyle) = update(current.copy(style = style))

    fun setSolidLast(on: Boolean) = update(current.copy(solidLast = on))

    private fun update(c: TurnAlertConfig) {
        prefs
            .edit()
            .putBoolean(KEY_ENABLED, c.enabled)
            .putString(KEY_TIMING, c.timing.code)
            .putString(KEY_SPEED, c.speed.code)
            .putString(KEY_COLOR, c.color.code)
            .putString(KEY_STYLE, c.style.code)
            .putBoolean(KEY_SOLID_LAST, c.solidLast)
            .apply()
        state.value = c
    }

    companion object {
        // PERSISTENCE CONTRACT: renaming any of these orphans the stored choice.
        private const val PREFS_NAME = "carlink_turn_alert"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TIMING = "timing"
        private const val KEY_SPEED = "speed"
        private const val KEY_COLOR = "color"
        private const val KEY_STYLE = "style"
        private const val KEY_SOLID_LAST = "solid_last"

        @Volatile private var instance: TurnAlertStore? = null

        fun get(context: Context): TurnAlertStore =
            instance ?: synchronized(this) {
                instance ?: TurnAlertStore(context).also { instance = it }
            }
    }
}
