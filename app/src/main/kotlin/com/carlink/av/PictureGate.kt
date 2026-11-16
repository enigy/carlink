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
 * first one offered for the current wait is the picture, which [picture] carries to the screen. Thread-safe.
 */
class PictureGate {
    private var wait = FIRST // guarded by this

    @Volatile private var open = FIRST

    private val state = MutableStateFlow<PictureWatch.FirstFrame?>(null)

    /** The first frame of the phone's session now on, or null while there is none and the loading screen stays. */
    val picture: StateFlow<PictureWatch.FirstFrame?> = state.asStateFlow()

    /** The wait frames are offered to, or [NONE] while the picture is up. A volatile read: renderers ask per frame. */
    val waiting: Long get() = open

    /** A new wait: no picture until a frame drawn from now on. */
    @Synchronized
    fun expect() {
        wait++
        state.value = null
        open = wait
    }

    /** A frame drawn for wait [forWait]: true when it is the picture, the first offered while that wait is open. */
    @Synchronized
    fun drawn(
        forWait: Long,
        frame: PictureWatch.FirstFrame,
    ): Boolean {
        if (forWait != wait || open == NONE) return false
        open = NONE
        state.value = frame
        return true
    }

    companion object {
        /** No wait: the picture is up. */
        const val NONE = 0L
        private const val FIRST = 1L
    }
}
