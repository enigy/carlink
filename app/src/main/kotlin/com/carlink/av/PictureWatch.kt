package com.carlink.av

/**
 * One video epoch's side of the picture ([VideoRenderer]): while [PictureGate] waits for a picture, every frame the decoder
 * hands to the Surface is offered to [onFrame] with the wait it answers, when this epoch's first video after that wait
 * began reached it, and when the decoder that drew it was ready. The gate takes the first and stops waiting, so a frame
 * is offered only until the picture is up: a volatile read per message and per frame, nothing more, while it is.
 *
 * It also says which decoded frames belong to the wait at all ([stale]): only those of access units fed after it began
 * ([fed]). A decoder kept from the last phone can still hand over that phone's last frame after a new wait begins, and
 * that frame must never be the picture. So the renderer reads the gate's wait once per step ([now]) and hands that one
 * value to each call of the step: once for an access unit, before it is queued ([startsWait], [fed]), and once for a
 * frame, when it is judged ([stale], [drawn]). A new wait begun in the middle of a step then finds that step's access
 * unit or frame counted under the wait before it, never under itself.
 *
 * Pulled, not pushed: each step asks the gate for its current wait ([waiting]), so a renderer opened or retired while the
 * gate starts a new wait can never be left watching for an old one. Plain Kotlin on an injected [clock], so its rules
 * are tested off the head unit. The renderer's own thread makes every call except [firstVideoAt], which any thread may.
 */
class PictureWatch(
    /** The gate's current wait, or [PictureGate.NONE] while the picture is up. */
    private val waiting: () -> Long,
    private val clock: () -> Long,
    /** This epoch's first video for a wait, as it comes: the gate keeps the wait's first across epochs ([PictureGate.videoIn]). */
    private val onVideoIn: (wait: Long, atMs: Long) -> Unit = { _, _ -> },
    private val onFrame: (wait: Long, frame: FirstFrame) -> Unit,
) {
    /** A frame offered as the picture, on the elapsedRealtime clock; 0 is not known. */
    data class FirstFrame(
        /** Handed to the Surface. */
        val drawnAtMs: Long,
        /** The wait's first video data from the adapter, before any decoding (the gate's, across epochs, once it is the picture). */
        val videoInAtMs: Long,
        /** The decoder that drew it configured and started: earlier than [videoInAtMs] when it was built ahead. */
        val decoderReadyAtMs: Long,
    )

    // Written in this order on the renderer's thread, read in the other by the manager's watchdog (firstVideoAt).
    @Volatile private var videoInAt = 0L

    @Volatile private var videoInWait = PictureGate.NONE

    private var decoderReadyAt = 0L

    // The wait the last access unit was fed under, and the presentation time of the first one fed under it.
    private var fedWait = PictureGate.NONE
    private var fedWaitFirstPtsUs = 0L

    /** The gate's wait now, [PictureGate.NONE] while the picture is up: read once per step and handed to that step's calls. */
    fun now(): Long = waiting()

    /** Video data from the adapter reached the renderer: the first since the wait began is kept. */
    fun videoIn() {
        val wait = waiting()
        if (wait == PictureGate.NONE || wait == videoInWait) return
        val at = clock()
        videoInAt = at
        videoInWait = wait
        onVideoIn(wait, at)
    }

    /** A decoder was configured and started now: the latest is the one that draws. */
    fun decoderReady() {
        decoderReadyAt = clock()
    }

    /** A real frame, judged under [wait] ([stale]), went to the Surface now: offered to that wait, which the gate refuses once it is past. */
    fun drawn(wait: Long) {
        if (wait == PictureGate.NONE) return
        onFrame(wait, FirstFrame(clock(), if (videoInWait == wait) videoInAt else 0L, decoderReadyAt))
    }

    /** The gate waits: no frame of this epoch has been taken for its wait yet, so the next one drawn is the picture. */
    fun awaitingFrame(): Boolean = waiting() != PictureGate.NONE

    /** An access unit fed now, under [wait], would be its first: a kept decoder starts that wait's picture at a keyframe. */
    fun startsWait(wait: Long): Boolean = wait != PictureGate.NONE && wait != fedWait

    /** An access unit went to the decoder at presentation time [ptsUs], under [wait] (read before it was queued). */
    fun fed(
        ptsUs: Long,
        wait: Long,
    ) {
        if (wait == PictureGate.NONE || wait == fedWait) return
        fedWaitFirstPtsUs = ptsUs
        fedWait = wait
    }

    /**
     * A decoded frame at presentation time [ptsUs] isn't the picture of [wait] (read once for this frame): its access unit
     * was fed before the wait's first (the phone that left), or nothing has been fed under the wait yet. The renderer drops
     * it unseen. Never while the picture is up.
     */
    fun stale(
        ptsUs: Long,
        wait: Long,
    ): Boolean {
        if (wait == PictureGate.NONE) return false
        return wait != fedWait || ptsUs < fedWaitFirstPtsUs
    }

    /** When this epoch's first video for wait [wait] came (elapsedRealtime), 0 when none has: the watchdog's. Any thread. */
    fun firstVideoAt(wait: Long): Long = if (videoInWait == wait) videoInAt else 0L
}
