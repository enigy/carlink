package com.carlink.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.carlink.ui.settings.CallMicConfig
import kotlin.math.min

/**
 * The inputs a phone call could record from on this car, and the silent check Automatic uses to
 * pick one. Settings → Calls lists [candidates] as manual choices; [CallMicConfig.AUTOMATIC] relies
 * on [probeCarCallPath] at first launch and [SilenceGuard] during each call.
 */
internal object CallPaths {
    /** `<TYPE>:<address>`, the form [CallMicConfig.forDevice] stores. */
    fun key(d: AudioDeviceInfo): String = "${AudioRouteInfo.typeName(d.type)}:${d.address}"

    /**
     * Inputs worth offering for calls, car call path first: GM's processed call uplink, USB and
     * headset mics, and built-in mics when there is more than one (a single one is what the system's
     * choice already records). The car's other buses are radio and media feeds, not microphones.
     */
    fun candidates(am: AudioManager): List<AudioDeviceInfo> {
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        val builtIn = inputs.filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        return inputs.filter { it.type == AudioDeviceInfo.TYPE_BUS && CallMicConfig.isCallUplink(it.address) } +
            inputs.filter { it.type in EXTERNAL_MICS } +
            (if (builtIn.size > 1) builtIn else emptyList())
    }

    /** The input stored under [key], if the car still lists it. */
    fun find(
        am: AudioManager,
        key: String,
    ): AudioDeviceInfo? = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { key(it) == key }

    /** Outcome of the first-launch check: the input Automatic should use (null = system's choice) and why. */
    data class Probe(
        val device: AudioDeviceInfo?,
        val reason: String,
        /** False when the check couldn't run properly (no permission yet, an error): try again next launch. */
        val conclusive: Boolean = true,
    )

    /**
     * Silent first-launch check: record ~1 s from the car's call input with nothing playing. On the
     * truck (2026-10-20) that input carried cabin sound at -74 dBFS with no call, and in the voice
     * and echo tests it was the clear winner: no echo, voice ~27 dB louder than the built-in mic.
     * An input that is listed but returns digital silence is not used. Blocking: call off the main thread.
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught") // every failure means "use the system's choice"
    fun probeCarCallPath(context: Context): Probe {
        val am = context.getSystemService(AudioManager::class.java) ?: return Probe(null, "no AudioManager")
        val bus = candidates(am).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUS } ?: return Probe(null, "this car lists no call input")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return Probe(null, "no microphone permission yet", conclusive = false)
        }
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return Probe(null, "no record buffer size ($minBuf)")
        var record: AudioRecord? = null
        return try {
            record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 3)
            if (record.state != AudioRecord.STATE_INITIALIZED) return Probe(null, "couldn't open the car call path", conclusive = false)
            record.setPreferredDevice(bus)
            record.startRecording()
            val pcm = ShortArray(SR * PROBE_MS / 1000)
            var filled = 0
            val deadline = System.nanoTime() + (PROBE_MS + TIMEOUT_MARGIN_MS) * NANOS_PER_MS
            while (filled < pcm.size && System.nanoTime() < deadline) {
                val n = record.read(pcm, filled, min(CHUNK, pcm.size - filled))
                if (n < 0) return Probe(null, "car call path read failed ($n)", conclusive = false)
                filled += n
            }
            val routed = AudioRouteInfo.device(record.routedDevice)
            val level = liveLevelDbfs(pcm, filled, skip = SR * SKIP_MS / 1000)
            when {
                level == null -> Probe(null, "car call path recorded too little to judge")
                level < SILENT_DBFS -> Probe(null, "car call path is silent (${"%.0f".format(level)} dBFS on $routed)")
                else -> Probe(bus, "car call path carries sound (${"%.0f".format(level)} dBFS on $routed)")
            }
        } catch (e: Exception) {
            Probe(null, "car call path check failed: ${e.message}", conclusive = false)
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
        }
    }

    /**
     * RMS level of [count] samples after skipping [skip] (a fresh record can start with zeros), or
     * null if fewer than 200 ms remain. Pure, unit-tested.
     */
    fun liveLevelDbfs(
        pcm: ShortArray,
        count: Int,
        skip: Int,
    ): Double? {
        if (count - skip < SR / MIN_JUDGE_DIV) return null
        return EchoDsp.rmsDbfs(EchoDsp.shortsToFloat(pcm, count), skip, count - skip)
    }

    /** Below this the input is digital silence: an empty bus, not a quiet cabin (-74 dBFS measured). */
    const val SILENT_DBFS = -90.0
    private const val SR = 16_000
    private const val PROBE_MS = 1_200
    private const val SKIP_MS = 200
    private const val MIN_JUDGE_DIV = 5 // 200 ms
    private const val CHUNK = 320
    private const val TIMEOUT_MARGIN_MS = 1_500L
    private const val NANOS_PER_MS = 1_000_000L
    private val EXTERNAL_MICS =
        setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADSET)
}

/**
 * Automatic's in-call guard: trips once if the mic audio of a call stays digital silence for
 * [holdMs] after a short settling time, the sign that the car call path stopped carrying the
 * driver. Any frame with sound disarms it for the rest of the call. Fed from the uplink thread
 * (S16LE mono); not thread-safe. Pure, unit-tested.
 */
internal class SilenceGuard(
    private val sampleRate: Int,
    private val settleMs: Int = 500,
    private val holdMs: Int = 3_000,
) {
    private var seenSamples = 0L
    private var silentSamples = 0L
    private var done = false

    /** Feed mic PCM; returns true exactly once, when the guard trips. */
    fun feed(
        pcm: ByteArray,
        off: Int,
        len: Int,
    ): Boolean {
        if (done) return false
        val n = len / 2
        val settle = sampleRate.toLong() * settleMs / 1000
        val before = seenSamples
        seenSamples += n
        if (seenSamples <= settle) return false
        val skip = (settle - before).coerceIn(0, n.toLong()).toInt()
        val x = EchoDsp.pcm16ToFloat(pcm, off + skip * 2, (n - skip) * 2)
        if (EchoDsp.rmsDbfs(x) >= CallPaths.SILENT_DBFS) {
            done = true // sound: the path works, stop watching
            return false
        }
        silentSamples += x.size
        if (silentSamples >= sampleRate.toLong() * holdMs / 1000) {
            done = true
            return true
        }
        return false
    }
}
