package com.carlink.av

/**
 * The media track has ONE volume authority: [effective] of the focus-driven gain (what AAOS told us —
 * 1.0 GAIN, 0.2 LOSS_TRANSIENT_CAN_DUCK, 0 LOSS/LOSS_TRANSIENT) and the software duck `VoiceRouter`
 * commands while a voice stream is audible (1.0 or 0.2).
 *
 * `min`, not a product: both are "duck to 0.2" for the SAME event (a nav prompt ducks us through
 * AAOS focus AND through the seam's energy gate), and multiplying them gives 0.04 — inaudible for
 * every prompt. `min` reproduces `carlink_native`'s `mediaVolume * min(adapterDuck, focusDuck)`.
 * Both inputs are logged together at every change so a restore on one path can never be silently
 * masked by the other (that masking was the 2026-09-25 "silent after Siri" bug: duck restored to 1.0
 * while focus still held 0.0 for 15 s).
 */
object MediaGain {
    fun effective(
        focusGain: Float,
        duckGain: Float,
    ): Float = minOf(focusGain, duckGain).coerceIn(0f, 1f)
}

/**
 * When a voice sink's TRANSIENT focus is given back — pure so the timing is unit-tested.
 *
 * The end-of-Siri signals, in the order iOS emits them (measured 2026-09-25, chevy12):
 *  1. the Siri audio goes quiet (last energetic frame);
 *  2. `UPLINK OFF` — iOS closes the mic stream (02:17:34.08, ~1.8 s after "assistant done");
 *  3. the MEDIA stream resumes with audible content (02:17:34.96, 0.9 s after UPLINK OFF).
 * A Siri turn never resumes audible media, so (3) is decisive and releases immediately; (2) releases
 * after [uplinkGraceMs] so a re-open inside the window (a follow-up question iOS asks without ending
 * the session) reuses the held focus instead of flapping it; (1) is the per-purpose idle backstop
 * (`VoiceRouter.Purpose.idleMs`). Back-to-back USER-initiated turns were 7-9 s apart
 * (UPLINK OFF 02:17:34 → next turn 02:17:41), far outside the grace: those are separate Siri
 * sessions and a fresh focus request between them is the correct behaviour (media is audible in the
 * gap, as on a native head unit).
 */
class TransientFocusPolicy(
    private val uplinkGraceMs: Long = UPLINK_GRACE_MS,
) {
    @Volatile private var uplinkOffAt = 0L

    /** The mic uplink is open: Siri (or a call) is still listening, whatever the downlink's energy says. */
    @Volatile var uplinkOn = false
        private set

    fun uplinkChanged(
        on: Boolean,
        now: Long,
    ) {
        uplinkOn = on
        uplinkOffAt = if (on) 0L else now
    }

    /** True exactly once when the grace after UPLINK OFF has elapsed; an UPLINK ON inside it cancels. */
    fun uplinkReleaseDue(now: Long): Boolean {
        val t = uplinkOffAt
        if (t == 0L || now - t < uplinkGraceMs) return false
        uplinkOffAt = 0L
        return true
    }

    companion object {
        /** Grace after UPLINK OFF before the assistant/call focus is abandoned. */
        const val UPLINK_GRACE_MS: Long = 1_000L

        /** A sink must have been quiet this long before "media is audible again" may release it. */
        const val MEDIA_RESUME_MIN_QUIET_MS: Long = 250L
    }
}

/**
 * When media asks for `AUDIOFOCUS_GAIN` — pure so the rule is unit-tested; `MediaFocus` acts on it.
 *
 * Only audible music asks. A paused iPhone opens a silent media stream every 10 s for about 4 s, and
 * asking on each stream's first frame (v28-v31) took the car's audio from whatever was playing every
 * time: 18 times in 5 minutes on 2026-10-23 with nothing playing on the phone. After a permanent loss
 * nothing asks until a new stream starts, so music still in flight cannot take the audio back from the
 * source that just took it. A refused request is retried after [refusedRetryMs].
 */
class MediaFocusAsk(
    private val refusedRetryMs: Long = REFUSED_RETRY_MS,
) {
    /** True after a permanent loss, until the next stream starts. */
    @Volatile var lost = false
        private set

    /** When a request was last refused, or -1. */
    @Volatile private var refusedAt = -1L

    /** The first frame after a gap: the music started or resumed. Clears a loss. */
    fun onStream() {
        lost = false
    }

    fun onLost() {
        lost = true
    }

    fun onRefused(now: Long) {
        refusedAt = now
    }

    /** Whether decoded media that is [audible] should ask for focus now, when not already [held]. */
    fun shouldAsk(
        held: Boolean,
        audible: Boolean,
        now: Long,
    ): Boolean = audible && !held && !lost && (refusedAt < 0 || now - refusedAt >= refusedRetryMs)

    companion object {
        const val REFUSED_RETRY_MS: Long = 2_000L
    }
}

/**
 * The aftermath of a steering-wheel skip the car gave to two sources at once — pure so the window is
 * unit-tested; `CarlinkManager` acts on it.
 *
 * On 10-25 at 11:26:03 (and once on 10-24 at 11:36:17) a Next press reached this app as the raw key,
 * which skipped the iPhone's track — and GM's own handler, whose source was not this app, gave the
 * same press to the radio. The radio started and took the media focus 58 ms after the key; the car
 * then stopped this session (`stop from com.android.car`, 430 ms after). Treated as a source change,
 * the loss paused the phone and the stop paused it again: the track advanced, the music fell silent
 * and the radio played, which is not what one press of Next means.
 *
 * So a focus loss or a car-issued stop inside [windowMs] of a skip THIS APP SENT is that press's
 * aftermath, not the driver choosing another source: keep the music and ask for the audio back. A
 * loss at any other time is a real source change and pauses the phone as before.
 */
class SkipAftermath(
    private val windowMs: Long = WINDOW_MS,
) {
    @Volatile private var skipAt = -1L

    /** A Next or Previous was sent to the phone at [now]. */
    fun skipSent(now: Long) {
        skipAt = now
    }

    /** Whether an event at [now] falls inside the window after the last skip. */
    fun covers(now: Long): Boolean = skipAt >= 0 && now - skipAt in 0..windowMs

    companion object {
        /** The radio's focus grab came 58 ms after the key, the car's stop 430 ms; the phone's own metadata for the new track within 700 ms. */
        const val WINDOW_MS: Long = 1_500L
    }
}
