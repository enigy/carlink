package com.carlink.av

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What lifts the loading screen and lets touch through (CarlinkManager, MainScreen): the first frame the decoder drew for
 * the phone's session now on. Nothing else does, STREAMING included: STREAMING (the phone's first media) came 50 to 200 ms
 * before the first frame in 3 of the 11 connects the 16 Nov ledger kept, and in more of the 14 and 15 Nov captures, and
 * the 1 s fade began over a blank surface; and the screen's 100 ms poll of the frame counter added up to a tenth of a
 * second after the frame (rp260-cut, 14 Nov: 108 ms).
 *
 * [expect] starts a new wait, taking the picture down: at a session's start, at the phone's first video key (on the USB
 * read thread, before any of its video can be decoded, so the last session's or the last host's frames never count), and
 * when the phone leaves. Each renderer's [PictureWatch] offers its frames to [drawn] while [waiting] says it waits; the
 * first one offered for the current wait is the picture, which [picture] carries to the screen, with the wait's first
 * video ([videoIn]) whichever epoch it reached. Thread-safe.
 *
 * A wait the decoder never answers is CarlinkManager's picture watchdog's ([PictureWatchdog]): its last resort,
 * [fallBack], lets the screen lift by 3.20's rule until the next wait, at STREAMING, so a session with the phone's media
 * can't stay behind the loading screen with touch blocked. A session that never reaches STREAMING (no media metadata)
 * stays covered, as it did under 3.20, with Settings and Reset reachable.
 */
class PictureGate {
    private var wait = FIRST // guarded by this

    // The current wait's first video into any epoch (elapsedRealtime), 0 for none yet. Guarded by this.
    private var waitVideoIn = 0L

    private val waitState = MutableStateFlow(FIRST)

    private val state = MutableStateFlow<PictureWatch.FirstFrame?>(null)

    private val fallen = MutableStateFlow(false)

    /** The first frame of the phone's session now on, or null while there is none and the loading screen stays. */
    val picture: StateFlow<PictureWatch.FirstFrame?> = state.asStateFlow()

    /** [waiting] as it changes: the watchdog sleeps while the picture is up, and wakes at the next wait. */
    val waits: StateFlow<Long> = waitState.asStateFlow()

    /** The wait frames are offered to, or [NONE] while the picture is up. A volatile read: renderers ask per frame. */
    val waiting: Long get() = waitState.value

    /** The watchdog gave up on a frame for this wait: the screen lifts by 3.20's rule, at STREAMING, until the next wait. */
    val fallenBack: StateFlow<Boolean> = fallen.asStateFlow()

    /** A new wait: no picture until a frame drawn from now on, and no fallback. */
    @Synchronized
    fun expect() {
        wait++
        waitVideoIn = 0L
        state.value = null
        fallen.value = false
        waitState.value = wait
    }

    /**
     * An epoch's first video for wait [forWait] came at [atMs]: the wait's first is kept, so the picture's video in is the
     * wait's even when a later epoch (the watchdog's rebuild, a surface back) draws it, as the ledger's step counts it.
     */
    @Synchronized
    fun videoIn(
        forWait: Long,
        atMs: Long,
    ) {
        if (forWait == wait && waitState.value != NONE && waitVideoIn == 0L) waitVideoIn = atMs
    }

    /** A frame drawn for wait [forWait]: true when it is the picture, the first offered while that wait is open. */
    @Synchronized
    fun drawn(
        forWait: Long,
        frame: PictureWatch.FirstFrame,
    ): Boolean {
        if (forWait != wait || waitState.value == NONE) return false
        waitState.value = NONE
        state.value = if (waitVideoIn != 0L) frame.copy(videoInAtMs = waitVideoIn) else frame
        return true
    }

    /**
     * The watchdog's last resort for wait [forWait], still open with no frame: true when it falls back now. The wait stays
     * open, so a frame that comes later is still the picture (and the ledger's).
     */
    @Synchronized
    fun fallBack(forWait: Long): Boolean {
        if (forWait != wait || waitState.value == NONE || fallen.value) return false
        fallen.value = true
        return true
    }

    companion object {
        /** No wait: the picture is up. */
        const val NONE = 0L
        private const val FIRST = 1L
    }
}
