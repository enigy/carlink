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
 * from [last] and leaves its own stream's there as it configures. Read and written on renderers' threads, one at a time.
 */
class StreamSetups {
    @Volatile var last: StreamSetup? = null

    /** False once a decoder built ahead drew nothing (CarlinkManager's stall watch): decoders are built at the stream's. */
    @Volatile var buildAhead = true
}
