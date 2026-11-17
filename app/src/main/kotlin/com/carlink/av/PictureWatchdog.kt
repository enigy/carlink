package com.carlink.av

/**
 * The loading screen's last resort ([EpochKeeper], for CarlinkManager). The screen lifts, and touch goes through, only on
 * the phone's first frame ([PictureGate]), so a decoder that takes the phone's video and draws nothing would keep both
 * locked. On a still CarPlay screen (the home screen, a parked map) the video after the first keyframe is only the
 * keyframes Carlink asks for, 2.5 s after the key and then every 30 s, so the stall watch, which wants new input in every
 * 2 s sample for 6 s, never fires; and a touch that would make CarPlay draw isn't forwarded. Sampled while the gate waits:
 *
 * - [Timing.rebuildMs] after the epoch's first video for this wait, a keyed phone here and still no frame, or
 *   [Timing.noVideoMs] with a keyed phone and no video into the epoch at all (its consumer gone, its pipe closed): rebuild
 *   the epoch, its decoder built at the stream's parameter sets, a keyframe asked at once and again 2.5 s on
 *   ([Action.REBUILD]).
 * - [Timing.fallBackMs] after that rebuild, on the epoch it opened, with still no frame: fall back to 3.20's rule until
 *   the next wait, the loading screen lifting at STREAMING and touch going through ([Action.FALL_BACK]). A frame that
 *   comes later is still the picture.
 *
 * The clocks are the epoch's, not only the wait's: an epoch something else opened in the middle of a wait (the surface
 * back after a display off, the app back in front, a re-subscribe) has its evidence from its own open on, so it counts
 * from there and gets a rebuild and a fallback of its own; only the epoch the rebuild opened carries the rebuild's clock.
 *
 * Not thread-safe: one caller (CarlinkManager's main scope, through [EpochKeeper]).
 */
class PictureWatchdog(
    val timing: Timing = Timing(),
) {
    data class Timing(
        /** How often it looks while a keyed phone is here; [idleSampleMs] while none is (waiting for a phone). */
        val sampleMs: Long = 250,
        val idleSampleMs: Long = 1_000,
        /**
         * After the epoch's first video. The session's 2.5 s keyframe comes back about 2.6 s after the video (key to first
         * video 0.04 to 0.22 s on the 16 Nov wake benches, a keyframe round trip 0.1 to 0.2 s on the 15 and 16 Nov ones), and
         * this head unit's first decoder can draw nothing until that second keyframe: 6 s leaves it over 3 s more, so a
         * decoder about to draw is not torn down. The truck's decoder has never run this code; err long.
         */
        val rebuildMs: Long = 6_000,
        /**
         * After the rebuild: its own keyframe at once and the re-armed 2.5 s one, back about 2.7 s on, with over 2 s for a
         * fresh decoder that needs the second, or one frame of output latency, to draw.
         */
        val fallBackMs: Long = 5_000,
        /**
         * Keyed with no video into the epoch at all, counted from the later of the key and the epoch's open: twenty times
         * the 0.04 to 0.22 s key to first video, with nothing in it about to draw, so it is rebuilt sooner than [rebuildMs].
         */
        val noVideoMs: Long = 4_000,
    )

    /**
     * The current epoch as one look sees it: [seq] tells it from the last; [openedAtMs] when it was opened; [rebuiltFor],
     * the wait the watchdog's own rebuild opened it for ([PictureGate.NONE] when something else did); [videoInAtMs], its
     * first video for the wait, 0 for none yet.
     */
    data class Epoch(
        val seq: Int,
        val openedAtMs: Long,
        val rebuiltFor: Long,
        val videoInAtMs: Long,
    )

    enum class Action { NONE, REBUILD, FALL_BACK }

    private var wait = PictureGate.NONE
    private var keyedSince = 0L
    private var done = false
    private var watched = NO_EPOCH
    private var since = 0L
    private var rebuiltAt = 0L

    /**
     * One look at [now]: the gate's current [wait] ([PictureGate.NONE] while the picture is up), whether a keyed phone's
     * session is up ([keyed]: its key came and it hasn't left since, not the state alone, which outlives a phone that
     * left), and the current [epoch], null with none.
     */
    fun sample(
        now: Long,
        wait: Long,
        keyed: Boolean,
        epoch: Epoch?,
    ): Action {
        if (wait == PictureGate.NONE) return Action.NONE
        if (wait != this.wait) begin(wait)
        if (done || !keyed || epoch == null) return Action.NONE
        if (keyedSince == 0L) keyedSince = now
        watch(epoch)
        return when {
            rebuiltAt != 0L -> if (now - rebuiltAt >= timing.fallBackMs) fallBack() else Action.NONE
            epoch.videoInAtMs != 0L -> if (now - epoch.videoInAtMs >= timing.rebuildMs) rebuild(now) else Action.NONE
            now - since >= timing.noVideoMs -> rebuild(now)
            else -> Action.NONE
        }
    }

    /** A new wait: everything starts over. */
    private fun begin(wait: Long) {
        this.wait = wait
        keyedSince = 0L
        done = false
        watched = NO_EPOCH
        rebuiltAt = 0L
    }

    /** [epoch] is the current one: a new one counts from its own open, and only the rebuild's own keeps the rebuild's clock. */
    private fun watch(epoch: Epoch) {
        if (epoch.seq == watched) return
        watched = epoch.seq
        since = maxOf(epoch.openedAtMs, keyedSince)
        if (epoch.rebuiltFor != wait) rebuiltAt = 0L
    }

    private fun rebuild(now: Long): Action {
        rebuiltAt = now
        return Action.REBUILD
    }

    private fun fallBack(): Action {
        done = true
        return Action.FALL_BACK
    }

    private companion object {
        const val NO_EPOCH = -1
    }
}
