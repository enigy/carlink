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
 * Which input a phone call records from. App-only; the box never sees it. Siri always takes the
 * system's choice. Persisted by [code].
 *
 * - [AUTOMATIC] (default): the best path this car offers, found without the driver. A silent check
 *   at first launch (and after a car software update) records a second from the car's call input;
 *   if it carries sound, calls use it, otherwise the system's choice. Each call is guarded: a car
 *   call path that goes silent mid-call falls back for the rest of the call and is remembered.
 * - [SYSTEM]: name no input. VOICE_COMMUNICATION, and the OS/HAL pick the device and processing
 *   (Carlink3's "Automatic" before 2026-10-20). On some GM builds that already lands on the car's
 *   Voice_Uplink path; on others it is the built-in mic with an echo canceller that barely works.
 * - A device path (`device:<TYPE>:<address>`): pinned to one input the car lists, such as GM's
 *   Voice_Uplink bus ("Car call path"). The driver chose it, so it is used on every call with no
 *   guard; only if the input has disappeared does the call fall back to the system's choice.
 */
data class CallMicConfig(
    val code: String,
    override val label: String,
) : Labelled {
    val isAutomatic: Boolean get() = code == AUTOMATIC_CODE

    /** `<TYPE>:<address>` of a pinned input, or null for [AUTOMATIC] / [SYSTEM]. */
    val deviceKey: String? get() = code.removePrefix(DEVICE_PREFIX).takeIf { code.startsWith(DEVICE_PREFIX) }

    companion object {
        private const val AUTOMATIC_CODE = "automatic"
        private const val SYSTEM_CODE = "system"
        private const val DEVICE_PREFIX = "device:"

        /** GM's processed call uplink, the input GM's own CarPlay records calls from. */
        const val CAR_CALL_PATH_KEY = "BUS:Voice_Uplink"

        val AUTOMATIC = CallMicConfig(AUTOMATIC_CODE, "Automatic")
        val SYSTEM = CallMicConfig(SYSTEM_CODE, "System choice")
        val CAR_CALL_PATH = forDevice(CAR_CALL_PATH_KEY)
        val DEFAULT = AUTOMATIC

        /** The choice pinned to the input with this `<TYPE>:<address>` key. */
        fun forDevice(key: String): CallMicConfig = CallMicConfig(DEVICE_PREFIX + key, labelFor(key))

        /**
         * Restore a stored [code]. Before 2026-10-20 the stored values were "automatic" (today's
         * [SYSTEM] behaviour, now the smarter [AUTOMATIC]) and "voice_uplink" (the bus, kept).
         */
        fun fromCode(code: String?): CallMicConfig =
            when {
                code == null || code == AUTOMATIC_CODE -> AUTOMATIC
                code == SYSTEM_CODE -> SYSTEM
                code == "voice_uplink" -> CAR_CALL_PATH
                code.startsWith(DEVICE_PREFIX) -> forDevice(code.removePrefix(DEVICE_PREFIX))
                else -> DEFAULT
            }

        /** What to call an input on the settings screen, from its `<TYPE>:<address>` key. */
        fun labelFor(key: String): String {
            val type = key.substringBefore(':')
            val address = key.substringAfter(':', "")
            return when {
                type == "BUS" && isCallUplink(address) -> "Car call path"
                type == "BUILTIN_MIC" -> if (address.isEmpty()) "Built-in mic" else "Built-in mic ($address)"
                type == "USB" -> "USB mic"
                type == "HEADSET" -> "Headset mic"
                address.isEmpty() -> type
                else -> "$type $address"
            }
        }

        /** GM names its processed call uplink Voice_Uplink (mic_for_vc on some builds). */
        fun isCallUplink(address: String): Boolean = address.lowercase().let { "uplink" in it || "mic_for" in it }
    }
}

/**
 * What [CallMicConfig.AUTOMATIC] settled on for this car, and why. [osBuild] is the car software it
 * was decided on: a different build re-runs the check, since routing varies by GM OS build.
 */
data class AutoCallPath(
    val deviceKey: String?,
    val osBuild: String,
    val reason: String,
) {
    val usesCarPath: Boolean get() = deviceKey != null
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
    private val auto = MutableStateFlow(readAuto())

    val callMicFlow: StateFlow<CallMicConfig> = mic.asStateFlow()
    val callBufferFlow: StateFlow<CallBufferConfig> = buffer.asStateFlow()

    /** What Automatic chose for this car, or null before the first check. */
    val autoPathFlow: StateFlow<AutoCallPath?> = auto.asStateFlow()
    val autoPath: AutoCallPath? get() = auto.value

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

    /** Record what Automatic settled on ([AutoCallPath]). */
    fun setAutoPath(value: AutoCallPath) {
        prefs
            .edit()
            .putString(KEY_AUTO_DEVICE, value.deviceKey ?: "")
            .putString(KEY_AUTO_OS, value.osBuild)
            .putString(KEY_AUTO_REASON, value.reason)
            .apply()
        auto.value = value
    }

    private fun readAuto(): AutoCallPath? {
        val device = prefs.getString(KEY_AUTO_DEVICE, null) ?: return null
        return AutoCallPath(device.ifEmpty { null }, prefs.getString(KEY_AUTO_OS, "").orEmpty(), prefs.getString(KEY_AUTO_REASON, "").orEmpty())
    }

    companion object {
        // PERSISTENCE CONTRACT: renaming any of these orphans every stored choice.
        private const val PREFS_NAME = "carlink_call_audio"
        private const val KEY_CALL_MIC = "call_mic"
        private const val KEY_CALL_BUFFER = "call_buffer"
        private const val KEY_AUTO_DEVICE = "auto_device"
        private const val KEY_AUTO_OS = "auto_os"
        private const val KEY_AUTO_REASON = "auto_reason"

        @Volatile private var instance: CallAudioStore? = null

        fun get(context: Context): CallAudioStore =
            instance ?: synchronized(this) {
                instance ?: CallAudioStore(context).also { instance = it }
            }
    }
}

/** The Call microphone row's choices and the line under it, kept apart from Android for tests. */
object CallMicChoices {
    /**
     * Call microphone choices: Automatic, the system's choice, then each input the car lists that a
     * call could use (`<TYPE>:<address>` keys). A pinned input the car no longer lists stays on the
     * list while it is selected, so the selection never vanishes from the row.
     */
    fun options(
        inputKeys: List<String>,
        selected: CallMicConfig,
    ): List<CallMicConfig> {
        val devices = inputKeys.map { CallMicConfig.forDevice(it) }
        val missing = selected.takeIf { it.deviceKey != null && it !in devices }
        return listOf(CallMicConfig.AUTOMATIC, CallMicConfig.SYSTEM) + devices + listOfNotNull(missing)
    }

    /** The line under Call microphone: what the choice does, and for Automatic what it picked and why. */
    fun note(
        mic: CallMicConfig,
        auto: AutoCallPath?,
        osBuild: String,
        carListsInputs: Boolean,
    ): String =
        when {
            mic == CallMicConfig.SYSTEM -> "The car's software picks the microphone."
            !mic.isAutomatic -> "Every call records from ${mic.label}. Automatic's checks are off."
            !carListsInputs -> "This car has no separate call input, so calls use the system's choice."
            auto == null || auto.osBuild != osBuild -> "Checks this car the first time it can. Until then calls try the car call path."
            auto.usesCarPath -> "Using the car call path: ${auto.reason}."
            else -> "Using the system's choice: ${auto.reason}."
        }
}
