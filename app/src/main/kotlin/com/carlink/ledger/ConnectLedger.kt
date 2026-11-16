package com.carlink.ledger

import com.carlink.ledger.ConnectRecord.Origin
import com.carlink.ledger.ConnectRecord.Outcome

/**
 * The connect ledger (M2): the last [CAP] connects Carlink timed, oldest first, so a real truck can show how long the
 * CarPlay picture takes to appear. Always kept, small, on the head unit only ([ConnectLedgerStore]); it names no phone.
 * Immutable: [with] returns the next one.
 */
data class ConnectLedger(
    val records: List<ConnectRecord> = emptyList(),
) {
    /** With [record] as the newest, the oldest dropped once there are more than [CAP]. */
    fun with(record: ConnectRecord): ConnectLedger = ConnectLedger((records + record).takeLast(CAP))

    val newestFirst: List<ConnectRecord> get() = records.asReversed()

    companion object {
        /** How many connects are kept. */
        const val CAP = 200
    }
}

/**
 * The figure the project is judged by, as Settings shows it: display on to the first video frame on screen, over the
 * last [WINDOW] wakes that showed a picture. Their median, and the time 9 in 10 were within (nearest-rank 90th
 * percentile). Fewer than [MIN_COUNT] say nothing yet.
 */
object ConnectStats {
    const val WINDOW = 20
    const val MIN_COUNT = 5
    const val PERCENTILE = 90

    private const val HUNDRED = 100
    private const val HALF_TENTH_MS = 50
    private const val MS_PER_TENTH = 100
    private const val TENTHS = 10

    data class Summary(
        /** How many connects it covers: [MIN_COUNT] to [WINDOW]. */
        val count: Int,
        val medianMs: Long,
        /** Nearest-rank 90th percentile: 9 in 10 were within it. */
        val ninetiethMs: Long,
    )

    /** Display on to picture of the newest [WINDOW] wakes that showed one, newest first. */
    fun wakePictures(ledger: ConnectLedger): List<Long> =
        ledger.newestFirst
            .asSequence()
            .filter { it.origin == Origin.DISPLAY_ON && it.outcome == Outcome.PICTURE }
            .mapNotNull { it.pictureMs }
            .take(WINDOW)
            .toList()

    /** The summary of [ledger]'s [wakePictures], or null with fewer than [MIN_COUNT]. */
    fun summary(ledger: ConnectLedger): Summary? = summary(wakePictures(ledger))

    /** The summary of [times], in any order, or null with fewer than [MIN_COUNT]. */
    fun summary(times: List<Long>): Summary? {
        if (times.size < MIN_COUNT) return null
        val sorted = times.sorted()
        return Summary(sorted.size, median(sorted), nearestRank(sorted, PERCENTILE))
    }

    /** The middle of [sorted]; with an even count, the mean of the two middle ones, rounded half up. */
    fun median(sorted: List<Long>): Long {
        require(sorted.isNotEmpty()) { "no times" }
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid] + 1) / 2
    }

    /** The nearest-rank [percent]th percentile of [sorted]: the value at rank ceil(percent / 100 × n). */
    fun nearestRank(
        sorted: List<Long>,
        percent: Int,
    ): Long {
        require(sorted.isNotEmpty()) { "no times" }
        val rank = (percent * sorted.size + HUNDRED - 1) / HUNDRED
        return sorted[rank.coerceIn(1, sorted.size) - 1]
    }

    /** [ms] as seconds with one decimal, rounded half up: 3450 is "3.5 s". */
    fun seconds(ms: Long): String {
        val tenths = (ms.coerceAtLeast(0) + HALF_TENTH_MS) / MS_PER_TENTH
        return "${tenths / TENTHS}.${tenths % TENTHS} s"
    }
}
