package com.carlink.ledger

/**
 * What the ledger keeps of the adapter's `[startup]` line, which OCBM 2.6.0 prints at its first video frame to the head
 * unit: its uptime when the head unit arrived, its boot radio preload then, and its counts. As 2.6.0 prints it:
 *
 *     [startup] host present +0 ms (adapter up 29.7 s, preload done), Bluetooth up +1314 ms, phone connected +2300 ms,
 *     Wi-Fi handoff +4476 ms, first frame +11602 ms; since the host came: absent pages 0, resets 0 (0 cut, no SDP
 *     channel), PSK mismatches 0; this session: nudges 1+0
 *
 * A pre-release build put every count under "this session"; both read the same. What a line lacks stays null.
 */
data class AdapterStartup(
    /** The adapter's uptime when the head unit arrived ("adapter up 29.7 s"), to the tenth of a second it gives. */
    val uptimeMs: Long? = null,
    /** Its boot radio preload then: done, running, off_guard. Only a short lower-case word is kept ([isPreloadWord]). */
    val preload: String? = null,
    /** Since the head unit came: Bluetooth pages that found no phone. */
    val absentPages: Int? = null,
    /** Since the head unit came: Bluetooth resets. */
    val resets: Int? = null,
    /** This session's screen nudges at 0.5 s ("nudges 1+0": the 1). */
    val firstNudges: Int? = null,
    /** This session's screen nudges at 3 s ("nudges 1+0": the 0). */
    val secondNudges: Int? = null,
    /** The phone's PSK-mismatch tries at the adapter's access point; null when it couldn't count them ("-"). */
    val pskMismatches: Int? = null,
    /** [pskMismatches] is a lower bound: the adapter's log was cut since the head unit came (">=3 (box.log cut)"). */
    val pskAtLeast: Boolean = false,
) {
    companion object {
        /** How the line starts; only such a box line is read. */
        const val PREFIX = "[startup] host present"

        private const val MAX_WORD = 24
        private const val MS_PER_TENTH = 100
        private const val MS_PER_S = 1000

        // `[0-9]`, never `\d`: Android's ICU engine takes \d as any Unicode digit, the JVM's only 0-9.
        private val UP = Regex("adapter up ([0-9]{1,9})\\.([0-9]) s")
        private val PRELOAD = Regex("preload ([a-z_]+)")
        private val WORD = Regex("[a-z_]{1,$MAX_WORD}")
        private val ABSENT = Regex("absent pages ([0-9]{1,9})")
        private val RESETS = Regex("resets ([0-9]{1,9})")
        private val NUDGES = Regex("nudges ([0-9]{1,9})\\+([0-9]{1,9})")
        private val PSK = Regex("PSK mismatches (>=)?([0-9]{1,9}|-)")

        /** A preload word the ledger may keep: lower-case letters and underscores, at most [MAX_WORD]. */
        fun isPreloadWord(word: String): Boolean = WORD.matches(word)

        /** The line's figures, or null when it isn't the adapter's `[startup]` line. */
        fun parse(line: String): AdapterStartup? {
            if (!line.startsWith(PREFIX)) return null
            val up = UP.find(line)?.groupValues
            val nudges = NUDGES.find(line)?.groupValues
            val psk = PSK.find(line)?.groupValues
            return AdapterStartup(
                uptimeMs = up?.let { it[1].toLong() * MS_PER_S + it[2].toLong() * MS_PER_TENTH },
                preload = firstGroup(PRELOAD, line)?.takeIf(::isPreloadWord),
                absentPages = firstGroup(ABSENT, line)?.toIntOrNull(),
                resets = firstGroup(RESETS, line)?.toIntOrNull(),
                firstNudges = nudges?.get(1)?.toIntOrNull(),
                secondNudges = nudges?.get(2)?.toIntOrNull(),
                pskMismatches = psk?.get(2)?.toIntOrNull(),
                pskAtLeast = psk?.get(1) == ">=",
            )
        }

        private fun firstGroup(
            pattern: Regex,
            line: String,
        ): String? = pattern.find(line)?.groupValues?.get(1)
    }
}

/**
 * The adapter's `[timeline]` line (OCBM 2.6.1 and later, sent just after its `[startup]` line), kept verbatim only when
 * the whole line is the v1 form and at most [MAX_CHARS] long; anything else is dropped. A key is a lower-case word of at
 * most 24 characters starting with a letter, a value a dash or at most 13 digits (the firmware's own cap), and a key with
 * 12 hex characters in a row (an address or a serial) is refused, so no name, address or free text can ride in it. Its
 * keys are never read: the set may change from one adapter build to the next.
 */
object AdapterTimeline {
    const val PREFIX = "[timeline]"
    const val MAX_CHARS = 800

    // `^\[timeline\] v1[ab]?( [a-z][a-z0-9_]{0,23}=(-|\d{1,13}))+$`, matched against the whole line, with `[0-9]` for `\d`:
    // Android's ICU engine takes \d as any Unicode digit, the JVM's only 0-9.
    private val V1 = Regex("\\[timeline\\] v1[ab]?( [a-z][a-z0-9_]{0,23}=(-|[0-9]{1,13}))+")

    // Twelve hex characters in a row, in a key: a Bluetooth or Wi-Fi address without its colons, or a serial.
    private val HEX_RUN = Regex("[0-9a-f]{12}")

    /** [line] when it may be kept, else null. */
    fun accept(line: String): String? {
        if (line.length > MAX_CHARS || !V1.matches(line)) return null
        val keys = line.splitToSequence(' ').drop(2).map { it.substringBefore('=') }
        return line.takeIf { keys.none(HEX_RUN::containsMatchIn) }
    }
}
