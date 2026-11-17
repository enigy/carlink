package com.carlink.av

/**
 * One video epoch's side of the picture ([VideoRenderer]): while [PictureGate] waits for a picture, every frame the decoder
 * hands to the Surface is offered to [onFrame] with the wait it answers, when this epoch's first video after that wait
 * began reached it, and when the decoder that drew it was ready. The gate takes the first and stops waiting, so a frame
 * is offered only until the picture is up: a volatile read per message and per frame, nothing more, while it is.
 *
 * It also says which decoded frames belong to the wait at all ([stale]): only those of access units fed after it began
 * ([fed]). A decoder kept from the last phone can still hand over that phone's last frame after a new wait begins, and
 * that frame must never be the picture.
 *
 * Pulled, not pushed: each call asks the gate for its current wait ([waiting]), so a renderer opened or retired while the
 * gate starts a new wait can never be left watching for an old one. Plain Kotlin on an injected [clock], so its rules
 * are tested off the head unit. Not thread-safe: the renderer's own thread makes every call.
 */
class PictureWatch(
    /** The gate's current wait, or [PictureGate.NONE] while the picture is up. */
    private val waiting: () -> Long,
    private val clock: () -> Long,
    private val onFrame: (wait: Long, frame: FirstFrame) -> Unit,
) {
    /** A frame offered as the picture, on the elapsedRealtime clock; 0 is not known. */
    data class FirstFrame(
        /** Handed to the Surface. */
        val drawnAtMs: Long,
        /** This epoch's first video data from the adapter after the wait began, before any decoding. */
        val videoInAtMs: Long,
        /** The decoder that drew it configured and started: earlier than [videoInAtMs] when it was built ahead. */
        val decoderReadyAtMs: Long,
    )

    private var videoInAt = 0L
    private var videoInWait = PictureGate.NONE

    private var decoderReadyAt = 0L

    // The wait the last access unit was fed under, and the presentation time of the first one fed under it.
    private var fedWait = PictureGate.NONE
    private var fedWaitFirstPtsUs = 0L

    /** Video data from the adapter reached the renderer: the first since the wait began is kept. */
    fun videoIn() {
        val wait = waiting()
        if (wait == PictureGate.NONE || wait == videoInWait) return
        videoInAt = clock()
        videoInWait = wait
    }

    /** A decoder was configured and started now: the latest is the one that draws. */
    fun decoderReady() {
        decoderReadyAt = clock()
    }

    /** A real frame went to the Surface now: offered while the gate waits. */
    fun drawn() {
        val wait = waiting()
        if (wait == PictureGate.NONE) return
        onFrame(wait, FirstFrame(clock(), if (videoInWait == wait) videoInAt else 0L, decoderReadyAt))
    }

    /** The gate waits: no frame of this epoch has been taken for its wait yet, so the next one drawn is the picture. */
    fun awaitingFrame(): Boolean = waiting() != PictureGate.NONE

    /** An access unit went to the decoder at presentation time [ptsUs]: the first fed under a wait marks where its frames begin. */
    fun fed(ptsUs: Long) {
        val wait = waiting()
        if (wait == PictureGate.NONE || wait == fedWait) return
        fedWaitFirstPtsUs = ptsUs
        fedWait = wait
    }

    /** The gate waits and nothing has been fed under its wait yet: the next access unit fed begins it. */
    fun waitUnfed(): Boolean {
        val wait = waiting()
        return wait != PictureGate.NONE && wait != fedWait
    }

    /**
     * A decoded frame at presentation time [ptsUs] isn't the waiting picture's: its access unit was fed before the wait's
     * first (the phone that left), or nothing has been fed under the wait yet. The renderer drops it unseen. Never while
     * the picture is up.
     */
    fun stale(ptsUs: Long): Boolean {
        val wait = waiting()
        if (wait == PictureGate.NONE) return false
        return wait != fedWait || ptsUs < fedWaitFirstPtsUs
    }
}
