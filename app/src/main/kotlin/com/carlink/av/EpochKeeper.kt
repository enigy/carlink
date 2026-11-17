package com.carlink.av

/**
 * CarlinkManager's watches over its current video epoch, as plain Kotlin: the picture watchdog's looks while the gate
 * waits ([PictureWatchdog]), and a decoder built ahead with no phone ([IdleAheadWatch]). The keeper decides; [Epochs], the
 * manager's side, retires and opens epochs, and only the epoch a decision was made on, while it is still the current one
 * (an epoch the read thread swapped meanwhile, for a codec change, is left alone). So every path from a look to a
 * rebuild, a fallback, a release or a decoder built ahead again is tested off the head unit, with made-up epochs and
 * times.
 *
 * Not thread-safe: one caller, the manager's main scope.
 */
class EpochKeeper(
    private val gate: PictureGate,
    private val epochs: Epochs,
    val dog: PictureWatchdog = PictureWatchdog(),
    val idle: IdleAheadWatch = IdleAheadWatch(),
) {
    /** One video epoch, as the keeper reads it. */
    interface Epoch {
        val seq: Int

        /** When it was opened (elapsedRealtime). */
        val openedAtMs: Long

        /** The wait the picture watchdog's rebuild opened it for, [PictureGate.NONE] when something else did. */
        val rebuiltFor: Long

        /** Opened, building nothing ahead, to release an idle decoder built ahead ([Reopen.IDLE]). */
        val idleDropped: Boolean

        /** Since when its decoder, built ahead, has waited with no video (elapsedRealtime), 0 when it isn't one. */
        val idleAheadSince: Long

        /** Video reached it: read by its renderer, or still unread in its pipe. */
        val hasVideo: Boolean

        /** Its first video for wait [wait], 0 for none yet. */
        fun firstVideoAt(wait: Long): Long
    }

    /** Why an epoch is retired and the next one opened. */
    enum class Reopen {
        /** The picture watchdog's rebuild: the next decoder at the stream's parameter sets, not ahead, for the wait. */
        PICTURE,

        /** An idle decoder built ahead released: the next epoch builds nothing ahead until a phone is coming. */
        IDLE,

        /** A phone is coming: the next epoch builds its decoder ahead again. */
        AHEAD,
    }

    /** The manager's side: its epochs, and what it does for the keeper. */
    interface Epochs {
        /** The current epoch, or null with none. */
        val current: Epoch?

        /** A keyed phone's session is up ([com.carlink.StreamingStamp.phoneKeyedIn]). */
        val phoneKeyed: Boolean

        /** Building ahead is on, and a last stream's setup is there to build from. */
        val canBuildAhead: Boolean

        /**
         * Retire [epoch] if it is still the current one, and open the next for [why] ([forWait]: the wait a picture rebuild
         * is for). False when [epoch] was no longer current, or no next epoch opened.
         */
        fun reopen(
            epoch: Epoch,
            why: Reopen,
            forWait: Long,
        ): Boolean

        /** [epoch]'s decoder, if built ahead, proven and fed, drew nothing: building ahead goes off for the process. */
        fun blame(epoch: Epoch)

        /** The session's delayed keyframe again: 2.5 s on, then every 30 s. */
        fun keyframeSoon()

        /** The watchdog fell back for wait [wait]: the screen lifts by 3.20's rule. For the manager's log. */
        fun fellBack(wait: Long)
    }

    /** The wait the watchdog is done with (it fell back): the manager's loop sleeps until the next one. */
    var settled = PictureGate.NONE
        private set

    /** How long until the next look: often while a keyed phone is here, seldom while none is. */
    fun lookEveryMs(): Long = if (epochs.phoneKeyed) dog.timing.sampleMs else dog.timing.idleSampleMs

    /**
     * One look of the picture watchdog at [now], while the gate waits. The gate's wait is read here, at the look, never
     * before the delay that led to it: a picture drawn meanwhile is answered with nothing, not with a rebuild.
     */
    fun look(now: Long) {
        val wait = gate.waiting
        if (wait == PictureGate.NONE || wait == settled) return
        val e = epochs.current
        val seen = e?.let { PictureWatchdog.Epoch(it.seq, it.openedAtMs, it.rebuiltFor, it.firstVideoAt(wait)) }
        when (dog.sample(now, wait, epochs.phoneKeyed, seen)) {
            PictureWatchdog.Action.NONE -> Unit
            PictureWatchdog.Action.REBUILD -> if (e != null) rebuild(e, wait)
            PictureWatchdog.Action.FALL_BACK -> {
                settled = wait
                if (gate.fallBack(wait)) epochs.fellBack(wait)
            }
        }
    }

    /** The watchdog's rebuild of [e] for [wait]: blamed if built ahead, reopened, and given the delayed keyframe again. */
    private fun rebuild(
        e: Epoch,
        wait: Long,
    ) {
        epochs.blame(e)
        if (epochs.reopen(e, Reopen.PICTURE, wait)) epochs.keyframeSoon()
    }

    /**
     * The stall watch's look at epoch [e] at [now]: true when its decoder built ahead had waited [IdleAheadWatch.idleMs]
     * with no video and no phone on its way, and was released. Video still unread in its pipe keeps it.
     */
    fun idleLook(
        now: Long,
        e: Epoch,
    ): Boolean {
        if (!idle.release(now, e.idleAheadSince) || e.hasVideo) return false
        return epochs.reopen(e, Reopen.IDLE, PictureGate.NONE)
    }

    /**
     * At [now] the adapter says a phone is coming (a Bluetooth phase of its connect, the phone on its bus): the idle clock
     * starts again, and an epoch opened to release a decoder built ahead, still with no video, builds one ahead again,
     * seconds before the phone's video (its Bluetooth link came 4 to 6 s before the first frame on the 16 Nov bench).
     */
    fun phoneComing(now: Long) {
        idle.phoneComing(now)
        val e = epochs.current ?: return
        if (!e.idleDropped || e.hasVideo || !epochs.canBuildAhead) return
        epochs.reopen(e, Reopen.AHEAD, PictureGate.NONE)
    }
}
