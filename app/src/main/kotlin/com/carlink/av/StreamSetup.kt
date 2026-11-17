package com.carlink.av

/**
 * One stream's decoder setup, as its renderer configured for it: the codec, the size and the parameter sets (Annex-B,
 * start codes kept). CarlinkManager keeps the last one, in memory only, so the next video epoch can build its decoder
 * before its stream comes ([VideoRenderer]). Parameter sets are the encoder's settings for the picture; they say nothing
 * about the phone or the driver.
 */
class StreamSetup(
    val codec: VideoCodec,
    val width: Int,
    val height: Int,
    val vps: ByteArray?,
    val sps: ByteArray,
    val pps: ByteArray,
) {
    init {
        require(codec != VideoCodec.HEVC || vps != null) { "HEVC needs its VPS" }
    }

    /** The csd it configures with, as [VideoCodecs.ParamSets.csd] gives it: HEVC's VPS+SPS+PPS as one, H.264's SPS+PPS. */
    val csd: ByteArray = if (codec == VideoCodec.HEVC) (vps ?: ByteArray(0)) + sps + pps else sps + pps
}

/**
 * The last stream's setup, shared by a manager's video epochs one after another: a renderer builds its decoder ahead
 * from it ([aheadFrom]) and leaves its own stream's there as it configures ([last]). Read and written on renderers'
 * threads, one at a time. Whether to build ahead at all is [buildAhead]'s, the process's by default; an epoch that builds
 * nothing ahead is given [notAhead], the same setups, so its stream's setup is still left for the next epoch.
 */
class StreamSetups private constructor(
    val buildAhead: BuildAheadSwitch,
    private val store: Store,
    private val ahead: Boolean,
) {
    constructor(buildAhead: BuildAheadSwitch = BuildAheadSwitch.process) : this(buildAhead, Store(), ahead = true)

    /** The last stream's setup, shared with every view of these setups. */
    var last: StreamSetup?
        get() = store.last
        set(value) {
            store.last = value
        }

    /** What a renderer given these builds its decoder ahead from: the last setup, while building ahead is on, or none. */
    val aheadFrom: StreamSetup? get() = store.last.takeIf { ahead && buildAhead.on }

    /** These setups for an epoch that builds nothing ahead (the picture watchdog's rebuild, an idle decoder released). */
    fun notAhead(): StreamSetups = StreamSetups(buildAhead, store, ahead = false)

    private class Store {
        @Volatile var last: StreamSetup? = null
    }
}

/**
 * Whether video decoders are built ahead of their stream. Off for good, until Carlink's process ends, once one built ahead
 * failed it: it threw at its first use, or it was fed and drew nothing (the renderer, CarlinkManager's stall and picture
 * watchdogs). Decoders are then built at the stream's parameter sets, as before building ahead. Per process
 * ([process]), not per manager: a rebuilt manager (Reset Device, a new screen) must not try the same head unit again.
 */
class BuildAheadSwitch {
    @Volatile var on = true
        private set

    /** Turn it off: true when this call did, so the caller logs it once. */
    fun turnOff(): Boolean {
        if (!on) return false
        synchronized(this) {
            if (!on) return false
            on = false
            return true
        }
    }

    companion object {
        /** The process's switch. */
        val process = BuildAheadSwitch()
    }
}
