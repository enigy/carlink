package com.carlink.av

/**
 * Access units reach the decoder and nothing reaches the screen. On the 9 Nov bench a video epoch latched H.264 from
 * one iPhone and was then fed another's HEVC: it reconfigured as video/avc every 0.5 s, rendered nothing, and the frame
 * stayed frozen with touch dead until the overlay watchdog happened to rebuild it.
 *
 * [sample] is called every [Timing.sampleMs] with the epoch's counters and says when to rebuild it: input kept
 * arriving for [Timing.stallMs] with no frame drawn. Nothing arriving is no stall (a still screen, or no phone), and a
 * rebuild comes at most once per [Timing.cooldownMs], so a decoder that can't draw at all isn't rebuilt in a loop.
 * Not thread-safe: one caller (CarlinkManager's main scope).
 */
class VideoStallWatch(
    val timing: Timing = Timing(),
) {
    data class Timing(
        val sampleMs: Long = 2_000,
        val stallMs: Long = 6_000,
        val cooldownMs: Long = 15_000,
    )

    private var epoch: Int? = null
    private var bytes = 0L
    private var frames = 0L

    // When input first came with nothing drawn; null while frames are drawn.
    private var stalledSince: Long? = null
    private var lastRebuild: Long? = null

    /** One look at epoch [seq]'s counters at [now]: true to rebuild it now. A new [seq] starts over. */
    fun sample(
        seq: Int,
        now: Long,
        bytesIn: Long,
        framesRendered: Long,
    ): Boolean {
        val fed = bytesIn > bytes
        val drew = framesRendered > frames
        val sameEpoch = seq == epoch
        epoch = seq
        bytes = bytesIn
        frames = framesRendered
        if (!sameEpoch || drew) {
            stalledSince = null
            return false
        }
        if (fed && stalledSince == null) stalledSince = now
        val since = stalledSince ?: return false
        val cooled = lastRebuild?.let { now - it >= timing.cooldownMs } ?: true
        if (now - since < timing.stallMs || !cooled) return false
        lastRebuild = now
        stalledSince = null
        return true
    }
}
