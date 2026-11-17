package com.carlink.av

import android.media.MediaCodec
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowMediaCodec

/**
 * Robolectric's MediaCodec, scripted where a hardware decoder differs from its fake, which decodes inside
 * queueInputBuffer: output that comes [lateMs] after its input, as a real decoder's does (so a drain right after a feed
 * finds nothing yet); a codec in error at a frame's release ([failReleases]); and hooks where the manager's thread may
 * start a new wait in the middle of a step, at a queue ([onQueued]) and at a frame handed to the Surface ([onRendered]).
 * It also counts the drain's timed looks ([timedLooks], with the longest [longestLookUs]).
 *
 * The knobs are static, for the one codec a renderer test has at a time: [reset] before and after each test.
 */
@Implements(MediaCodec::class)
class ScriptedShadowMediaCodec : ShadowMediaCodec() {
    // When each input still waiting for its output was queued (System.nanoTime): outputs come in the order inputs went.
    private val queuedAt = ArrayDeque<Long>()

    @Implementation
    override fun native_queueInputBuffer(
        index: Int,
        offset: Int,
        size: Int,
        presentationTimeUs: Long,
        flags: Int,
    ) {
        super.native_queueInputBuffer(index, offset, size, presentationTimeUs, flags)
        queuedAt.addLast(System.nanoTime())
        queued++
        onQueued?.invoke(queued)
    }

    @Implementation
    override fun native_dequeueOutputBuffer(
        info: MediaCodec.BufferInfo,
        timeoutUs: Long,
    ): Int {
        if (timeoutUs > 0) {
            timedLooks++
            longestLookUs = maxOf(longestLookUs, timeoutUs)
            onTimedLook?.invoke()
        }
        val head = queuedAt.firstOrNull()
        if (head != null && lateMs > 0) {
            val readyAt = head + lateMs * NANOS_PER_MS
            val waitNs = minOf(readyAt - System.nanoTime(), timeoutUs * NANOS_PER_US)
            if (waitNs > 0) Thread.sleep(waitNs / NANOS_PER_MS, (waitNs % NANOS_PER_MS).toInt())
            if (System.nanoTime() < readyAt) return MediaCodec.INFO_TRY_AGAIN_LATER
        }
        // Nothing pending: Robolectric's own wait, so a look with nothing to find takes its time instead of spinning.
        val idx = super.native_dequeueOutputBuffer(info, if (head == null) timeoutUs else 0L)
        if (idx >= 0) queuedAt.removeFirstOrNull()
        return idx
    }

    @Implementation
    override fun releaseOutputBuffer(
        index: Int,
        render: Boolean,
    ) {
        if (failReleases > 0) {
            failReleases--
            error("made-up codec error at a frame's release")
        }
        super.releaseOutputBuffer(index, render)
        if (render) onRendered?.invoke()
    }

    companion object {
        private const val NANOS_PER_MS = 1_000_000L
        private const val NANOS_PER_US = 1_000L

        /** Each output comes this long after its input was queued; 0 is at once, as Robolectric's own. */
        var lateMs = 0L

        /** The next this many frames' releases throw, as a codec in error does. */
        var failReleases = 0

        /** Called after each queue, with how many access units have been queued in the test so far. */
        var onQueued: ((Int) -> Unit)? = null

        /** Called after a frame went to the Surface: between the renderer's judging it and its offer to the gate. */
        var onRendered: (() -> Unit)? = null

        /** Called at each timed look for output (a keyframe's look for its frame), before it waits. */
        var onTimedLook: (() -> Unit)? = null

        var queued = 0
            private set

        var timedLooks = 0
            private set

        var longestLookUs = 0L
            private set

        fun reset() {
            lateMs = 0L
            failReleases = 0
            onQueued = null
            onRendered = null
            onTimedLook = null
            queued = 0
            timedLooks = 0
            longestLookUs = 0L
        }
    }
}
