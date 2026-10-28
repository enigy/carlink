@file:Suppress("MatchingDeclarationName") // the lock plus the helpers both tests share

package com.carlink.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import java.util.concurrent.atomic.AtomicBoolean

/** Keeps the parked audio tests ([EchoTest], [VoiceTest]) from running over each other. */
internal object AudioTestLock {
    private val busy = AtomicBoolean(false)

    fun tryLock(): Boolean = busy.compareAndSet(false, true)

    fun unlock() = busy.set(false)
}

/** Attributes of the call track: USAGE_VOICE_COMMUNICATION speech. */
internal fun voiceCallAttributes(): AudioAttributes =
    AudioAttributes
        .Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

/**
 * Attach AEC, NS and AGC to [sessionId] like the call mic does, adding each created effect to
 * [into] (the caller releases them). Returns one "name=state:implementation" entry per effect.
 */
@Suppress("TooGenericExceptionCaught") // a vendor effect that throws is reported, never fatal
internal fun attachVoiceEffects(
    sessionId: Int,
    into: MutableList<AudioEffect>,
): String {
    val parts = mutableListOf<String>()

    fun add(
        name: String,
        available: Boolean,
        create: () -> AudioEffect?,
    ) {
        if (!available) {
            parts += "$name=unavailable"
            return
        }
        try {
            val e = create()
            if (e == null) {
                parts += "$name=null"
            } else {
                e.enabled = true
                into += e
                parts += "$name=${if (e.enabled) "on" else "OFF"}:${AudioRouteInfo.effect(e.descriptor)}"
            }
        } catch (ex: Exception) {
            parts += "$name=failed(${ex.message})"
        }
    }
    add("aec", AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(sessionId) }
    add("ns", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(sessionId) }
    add("agc", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(sessionId) }
    return parts.joinToString(" ")
}

/**
 * One way a call could record the driver, as the parked tests ([EchoTest], [VoiceTest]) try it.
 * [appEffects] attaches Android's AEC/NS/AGC to the record session; [commMode] puts the platform
 * in MODE_IN_COMMUNICATION first; [device] pins the record to an input (GM's Voice_Uplink bus).
 */
internal data class CallPath(
    val label: String,
    val source: Int,
    val appEffects: Boolean,
    val commMode: Boolean,
    val device: AudioDeviceInfo? = null,
) {
    val isRawMic: Boolean get() = source == MediaRecorder.AudioSource.MIC
}

/**
 * The call paths worth comparing on this head unit, Carlink's own first:
 *  - "System choice": VOICE_COMMUNICATION, no app effects, mode untouched — what
 *    [MicrophoneCaptureManager] records when Settings → Calls → Call microphone is System choice.
 *  - "Android echo canceller (Carlink2)": VOICE_COMMUNICATION with AEC/NS/AGC in
 *    MODE_IN_COMMUNICATION — Carlink2's Automatic ([193]/[194]/[235]).
 *  - "Android echo canceller, normal mode": the same effects without the mode, to see which of the two matters.
 *  - "Car call path (Carlink)": the Voice_Uplink bus, no app effects, mode untouched — Carlink's
 *    "Car call path" today. Only when the car lists the bus.
 *  - "Car call path, call mode (Carlink2)": the bus in MODE_IN_COMMUNICATION — Carlink2's ([225]).
 *  - "Raw mic": AudioSource.MIC, no effects; the baseline nothing cancels.
 */
internal fun callPathsToCompare(am: AudioManager): List<CallPath> {
    val vc = MediaRecorder.AudioSource.VOICE_COMMUNICATION
    val bus = AudioRouteInfo.uplinkBus(am)
    return listOfNotNull(
        CallPath("System choice", vc, appEffects = false, commMode = false),
        CallPath("Android echo canceller (Carlink2)", vc, appEffects = true, commMode = true),
        CallPath("Android echo canceller, normal mode", vc, appEffects = true, commMode = false),
        bus?.let { CallPath("Car call path (Carlink)", vc, appEffects = false, commMode = false, device = it) },
        bus?.let { CallPath("Car call path, call mode (Carlink2)", vc, appEffects = false, commMode = true, device = it) },
        CallPath("Raw mic", MediaRecorder.AudioSource.MIC, appEffects = false, commMode = false),
    )
}
