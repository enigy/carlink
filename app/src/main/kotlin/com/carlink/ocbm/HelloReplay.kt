package com.carlink.ocbm

/**
 * The adapter's state at SUBSCRIBE, held until the box says whether it is still a session.
 *
 * Right after CT_HELLO_ACK the box re-sends its state mirrors to the fresh SUBSCRIBE (F_REPLAY): CT_PROJ_MODE, the
 * last CT_PHONE_IDENT, and about a second of that session's keyed video. On the 9 Nov bench that was the LAST host's
 * session every time: PROJ_MODE NONE followed about 1.5 s later and the phone's real reconnect 15-20 s after. Taken
 * at face value it showed "Starting CarPlay" and then fell back to "Finding your phone", and it latched the old
 * phone's H.264 on the video epoch before the new phone's HEVC session (black screen, 13:19).
 *
 * So, from [start] (the SUBSCRIBE): until the first CT_PROJ_MODE the state is undecided, and a replayed mode other
 * than NONE is HELD. While undecided or holding, the mode, the phone's identity and the first video key are kept
 * back, not applied; the caller opens no video epoch. A live CT_PROJ_MODE (not F_REPLAY) decides: NONE means the
 * replayed session was the last host's and ended ([Release.live] false: what was held is dropped); any other mode, or
 * [onTimeout] while holding (a USB blip warm-reusing a session that really is live), releases what was held as live.
 * A replayed NONE decides at once: there is nothing to hold.
 *
 * Synchronized: CarlinkManager starts it on the bring-up thread and feeds it on its main scope; [holding] is read anywhere.
 */
class HelloReplay {
    private enum class Gate { CLEAR, UNDECIDED, HOLDING }

    /** What was held, released as live (applied now) or not ([live] false: the last host's session, ended). */
    data class Release(
        val live: Boolean,
        /** The projection mode to show; null when no CT_PROJ_MODE came at all. */
        val mode: Byte?,
        /** The phone identity held back, applied when [live]; null when none came. */
        val identity: String?,
        /** A video key raised the session-keyed edge while held. */
        val keyed: Boolean,
    )

    /** What to do with one CT_PROJ_MODE. */
    sealed interface Verdict {
        /** Apply it as always. */
        data object Apply : Verdict

        /** Held: show nothing of it yet. */
        data object Hold : Verdict

        /** It decided: apply the release. */
        data class Decided(
            val release: Release,
        ) : Verdict
    }

    @Volatile private var gate = Gate.CLEAR
    private var mode: Byte? = null
    private var identity: String? = null
    private var keyed = false

    /** Undecided or holding: no video epoch, and the session's first edges wait. */
    val holding: Boolean get() = gate != Gate.CLEAR

    /** A SUBSCRIBE went out: what the box says next may be the last host's. */
    @Synchronized
    fun start() {
        gate = Gate.UNDECIDED
        clearHeld()
    }

    /** The session ended: nothing to hold. */
    @Synchronized
    fun reset() {
        gate = Gate.CLEAR
        clearHeld()
    }

    @Synchronized
    fun onProjMode(
        m: Byte,
        replay: Boolean,
    ): Verdict =
        when (gate) {
            Gate.CLEAR -> Verdict.Apply
            Gate.UNDECIDED ->
                if (replay && m != Ocbm.PM_NONE) {
                    gate = Gate.HOLDING
                    mode = m
                    Verdict.Hold
                } else {
                    Verdict.Decided(release(live = true, m))
                }
            Gate.HOLDING ->
                when {
                    replay -> {
                        mode = m
                        Verdict.Hold
                    }
                    m == Ocbm.PM_NONE -> Verdict.Decided(release(live = false, m))
                    else -> Verdict.Decided(release(live = true, m))
                }
        }

    /** A CT_PHONE_IDENT: true to apply it now, false when held (the latest one is kept). */
    @Synchronized
    fun onIdentity(json: String): Boolean {
        if (gate == Gate.CLEAR) return true
        identity = json
        return false
    }

    /** The first video key of the generation: true to raise the edge now, false when held. */
    @Synchronized
    fun onKeyed(): Boolean {
        if (gate == Gate.CLEAR) return true
        keyed = true
        return false
    }

    /** The hold time ran out: a session still shown as live is live; undecided with no mode at all decides too. */
    @Synchronized
    fun onTimeout(): Release? = if (gate == Gate.CLEAR) null else release(live = true, mode)

    private fun release(
        live: Boolean,
        m: Byte?,
    ): Release {
        val r = Release(live, m, identity, keyed)
        gate = Gate.CLEAR
        clearHeld()
        return r
    }

    private fun clearHeld() {
        mode = null
        identity = null
        keyed = false
    }
}
