package com.carlink.ui.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A choice with a label for its segmented button on the settings screen. */
interface Labelled {
    val label: String
}

/**
 * Which input a phone call records from (Carlink2 [225]/[235]). App-only; the box never sees it.
 *
 * - [AUTOMATIC]: name no input. VOICE_COMMUNICATION, and the OS/HAL pick the device and processing.
 *   On some GM builds that already lands on the car's Voice_Uplink path with its call echo canceller.
 * - [VOICE_UPLINK]: force GM's processed call-uplink bus (what GM's own CarPlay records), for builds
 *   where AUTOMATIC echoes. Only offered when the car lists that bus.
 *
 * Siri always takes the AUTOMATIC path. Persisted by [code].
 */
enum class CallMicConfig(
    val code: String,
    override val label: String,
) : Labelled {
    AUTOMATIC("automatic", "Automatic"),
    VOICE_UPLINK("voice_uplink", "Car call path"),
    ;

    companion object {
        val DEFAULT = AUTOMATIC

        fun fromCode(code: String?): CallMicConfig = entries.find { it.code == code } ?: DEFAULT
    }
}

/**
 * How much caller audio the call track may hold before it plays (Carlink2 [226]). App-only.
 *
 * In Carlink3 this sizes the CALL playback track: [LOW] opens it at [bufferMultiplier] 2x the
 * platform minimum, [STANDARD] at the 4x every other voice track uses. Caller audio arrives at the
 * rate it plays, so a backlog never drains on its own; a smaller track caps how late the caller's
 * words can get, at the cost of less protection against a hiccup. Takes effect on the next call.
 */
enum class CallBufferConfig(
    val code: String,
    override val label: String,
    val bufferMultiplier: Int,
) : Labelled {
    LOW("low", "Low", 2),
    STANDARD("standard", "Standard", 4),
    ;

    companion object {
        val DEFAULT = LOW

        fun fromCode(code: String?): CallBufferConfig = entries.find { it.code == code } ?: DEFAULT
    }
}

/**
 * The persisted call-audio choices, in the style of [DisplayModeStore]: SharedPreferences plus a
 * [StateFlow] per setting so the Control tab and the audio path follow a change live. One instance
 * per process ([get]). Reads are synchronous so the audio threads can consult them inline.
 */
class CallAudioStore private constructor(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mic = MutableStateFlow(CallMicConfig.fromCode(prefs.getString(KEY_CALL_MIC, null)))
    private val buffer = MutableStateFlow(CallBufferConfig.fromCode(prefs.getString(KEY_CALL_BUFFER, null)))

    val callMicFlow: StateFlow<CallMicConfig> = mic.asStateFlow()
    val callBufferFlow: StateFlow<CallBufferConfig> = buffer.asStateFlow()

    val callMic: CallMicConfig get() = mic.value
    val callBuffer: CallBufferConfig get() = buffer.value

    fun setCallMic(value: CallMicConfig) {
        prefs.edit().putString(KEY_CALL_MIC, value.code).apply()
        mic.value = value
    }

    fun setCallBuffer(value: CallBufferConfig) {
        prefs.edit().putString(KEY_CALL_BUFFER, value.code).apply()
        buffer.value = value
    }

    companion object {
        // PERSISTENCE CONTRACT: renaming any of these orphans every stored choice.
        private const val PREFS_NAME = "carlink_call_audio"
        private const val KEY_CALL_MIC = "call_mic"
        private const val KEY_CALL_BUFFER = "call_buffer"

        @Volatile private var instance: CallAudioStore? = null

        fun get(context: Context): CallAudioStore =
            instance ?: synchronized(this) {
                instance ?: CallAudioStore(context).also { instance = it }
            }
    }
}
