package com.carlink.av

/**
 * The loading screen's last resort (CarlinkManager). The screen lifts, and touch goes through, only on the phone's first
 * frame ([PictureGate]), so a decoder that takes the phone's video and draws nothing would keep both locked. On a still
 * CarPlay screen (the home screen, a parked map) the video after the first keyframe is only the keyframes Carlink asks
 * for, 2.5 s after the key and then every 30 s, so the stall watch, which wants new input in every 2 s sample for 6 s,
 * never fires; and a touch that would make CarPlay draw isn't forwarded. Sampled while the gate waits, once per wait:
 *
 * - [Timing.rebuildMs] after this wait's first video into the epoch, the session keyed and still no frame: rebuild the
 *   epoch, its decoder built at the stream's parameter sets, and ask for a keyframe ([Action.REBUILD]).
 * - [Timing.fallBackMs] after that rebuild with still no frame, or [Timing.noVideoMs] keyed with no video at all: fall
 *   back to 3.20's rule until the next wait, the loading screen lifting at STREAMING and touch going through
 *   ([Action.FALL_BACK]). A frame that comes later is still the picture.
 *
 * Not thread-safe: one caller (CarlinkManager's main scope).
 */
class PictureWatchdog(
    val timing: Timing = Timing(),
) {
    data class Timing(
        /** How often it looks while the session is keyed; [idleSampleMs] while it isn't (waiting for a phone). */
        val sampleMs: Long = 250,
        val idleSampleMs: Long = 1_000,
        /**
         * After the wait's first video: past the 2.5 s keyframe the session asks for at its key (this head unit's first
         * decoder can come up drawing nothing until a second keyframe, which that one is for), with 1.5 s for it to come
         * back and decode (a keyframe round trip was 0.1 to 0.2 s on the 15 and 16 Nov benches, a first frame 43 to 48 ms
         * after its video on b7351ff's), so the cheap cure has had its chance before the rebuild.
         */
        val rebuildMs: Long = 4_000,
        /** After the rebuild: its own keyframe request, a fresh decoder built at the stream's parameter sets, many times over. */
        val fallBackMs: Long = 3_000,
        /** Keyed with no video into the epoch at all: the rebuild's whole budget, with nothing to rebuild for. */
        val noVideoMs: Long = 7_000,
    )

    enum class Action { NONE, REBUILD, FALL_BACK }

    private var wait = PictureGate.NONE
    private var keyedSince = 0L
    private var rebuiltAt = 0L
    private var done = false

    /**
     * One look at [now]: the gate's current [wait] ([PictureGate.NONE] while the picture is up), whether a keyed phone's
     * session is up ([keyed]: its key came and it hasn't left since, not the state alone, which outlives a phone that
     * left), and when the epoch's first video for that wait came ([videoInAtMs], 0 for none yet).
     */
    fun sample(
        now: Long,
        wait: Long,
        keyed: Boolean,
        videoInAtMs: Long,
    ): Action {
        if (wait == PictureGate.NONE) return Action.NONE
        if (wait != this.wait) {
            this.wait = wait
            keyedSince = 0L
            rebuiltAt = 0L
            done = false
        }
        if (done || !keyed) return Action.NONE
        if (keyedSince == 0L) keyedSince = now
        return when {
            rebuiltAt != 0L -> if (now - rebuiltAt >= timing.fallBackMs) fallBack() else Action.NONE
            videoInAtMs != 0L -> if (now - videoInAtMs >= timing.rebuildMs) rebuild(now) else Action.NONE
            now - keyedSince >= timing.noVideoMs -> fallBack()
            else -> Action.NONE
        }
    }

    private fun rebuild(now: Long): Action {
        rebuiltAt = now
        return Action.REBUILD
    }

    private fun fallBack(): Action {
        done = true
        return Action.FALL_BACK
    }
}
