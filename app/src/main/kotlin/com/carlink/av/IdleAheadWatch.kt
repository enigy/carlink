package com.carlink.av

/**
 * A decoder built ahead with no phone ([VideoRenderer.idleAheadSince]). Built at display on or at app start from the
 * last stream's setup, it holds one of the head unit's hardware decoders until the phone's video comes, and on a drive
 * without a phone that is hours. [release] says when to let it go: [idleMs] with no video since it was built, or since
 * the adapter last said a phone is coming ([phoneComing]: a Bluetooth phase of its connect, the phone on its bus),
 * whichever is later. [EpochKeeper] builds it again at the next such sign, seconds before the phone's video, and the
 * manager at the next video epoch it opens.
 *
 * Not thread-safe: one caller ([EpochKeeper], on CarlinkManager's main scope).
 */
class IdleAheadWatch(
    /**
     * A phone's video came 8 to 25 s after its session's SUBSCRIBE on the 16 Nov bench (a slow pairing round, an adapter
     * just rebooted), so a minute keeps the gain for every real connect; past it no phone is on its way.
     */
    val idleMs: Long = 60_000L,
) {
    private var lastSignAt = 0L

    /** The adapter says a phone is coming, at [now]: the idle clock starts again. */
    fun phoneComing(now: Long) {
        lastSignAt = now
    }

    /** At [now], the epoch's decoder built ahead has waited with no video since [idleSince] (0: it isn't one): release it? */
    fun release(
        now: Long,
        idleSince: Long,
    ): Boolean = idleSince != 0L && now - maxOf(idleSince, lastSignAt) >= idleMs
}
