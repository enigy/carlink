package com.carlink.ledger

/**
 * One connect Carlink timed, for the connect ledger ([ConnectLedger], M2): when its origin was, what kind of connect it
 * was, how long each step took from that origin, and how it ended. Every time is milliseconds from the origin, null when
 * the step never came. It names no phone: no name, model, Bluetooth or Wi-Fi address, network name or place. Its only
 * texts are the adapter's preload word and its timeline line, each checked before it is kept ([AdapterStartup],
 * [AdapterTimeline]).
 */
data class ConnectRecord(
    /** The origin, on the head unit's clock (epoch ms). */
    val startedAtMs: Long,
    val kind: Kind,
    val outcome: Outcome,
    /** Origin to the end: the picture, the display going off, Close app, the last failure, or the end of the window. */
    val endMs: Long,
    /** Why it ended early; null unless [outcome] is [Outcome.ENDED_EARLY]. */
    val endReason: EndReason? = null,
    /** Origin to the last CT_SUBSCRIBE Carlink sent for it. */
    val subscribeMs: Long? = null,
    /**
     * Origin to the first video data from the adapter reaching the decoder's thread, before any decoding, in the video
     * epoch that drew the picture. Only with [Outcome.PICTURE]; null in a record kept before it was measured.
     */
    val videoInMs: Long? = null,
    /**
     * Origin to that epoch's decoder being ready (configured and started) for the frame it drew: before [videoInMs] when it
     * was built ahead from the last stream's parameter sets. Only with [Outcome.PICTURE]; null in an older record.
     */
    val decoderReadyMs: Long? = null,
    /** Origin to the first video frame on screen ([ConnectTimer.picture]); only with [Outcome.PICTURE]. */
    val pictureMs: Long? = null,
    /** Origin to STREAMING: the phone's first media once keyed, or its key when the media came first. */
    val streamingMs: Long? = null,
    /** Sessions Carlink started for it: 1, or more when one failed and it tried again. */
    val starts: Int = 0,
    /**
     * Origin to when Carlink's screen came to the front (0: it was in front already), as it stood at the picture, or at
     * the end without one; null when it wasn't in front then. A later time means the driver was in another app.
     */
    val frontMs: Long? = null,
    /** A wake's: how long the head unit was suspended while the display was off, 0 when it wasn't; null for the others. */
    val suspendedMs: Long? = null,
    /** The adapter's `[startup]` line, when it came (OCBM 2.6.0 and later, while its log stream is on). */
    val adapter: AdapterStartup? = null,
    /** The adapter's `[timeline]` line, verbatim, when it came in the v1 form ([AdapterTimeline]). */
    val timeline: String? = null,
) {
    /** What its times are counted from. */
    val origin: Origin get() = kind.origin

    enum class Origin(
        val code: String,
        val words: String,
    ) {
        /** The head unit's display came on: a wake. */
        DISPLAY_ON("display_on", "the display on"),

        /** Carlink's process started: an app start. */
        PROCESS_START("process_start", "the process start"),

        /** Carlink began a session with none in flight: a reconnect, timed from that start. */
        RECONNECT("reconnect", "the reconnect's start"),
    }

    enum class Kind(
        val code: String,
        val origin: Origin,
        val words: String,
    ) {
        /** A wake while the adapter was still listed on USB: powered while the display was off. */
        WAKE_LISTED("wake_listed", Origin.DISPLAY_ON, "wake, adapter listed"),

        /** A wake with the adapter not listed: it lost power, or USB came back after the display. */
        WAKE_NOT_LISTED("wake_not_listed", Origin.DISPLAY_ON, "wake, adapter not listed"),

        /** The process's first connect. */
        APP_START("app_start", Origin.PROCESS_START, "app start"),

        /** Any other connect: after a lost link, the adapter attached, Reset connection. */
        RECONNECT("reconnect", Origin.RECONNECT, "reconnect"),
    }

    enum class Outcome(
        val code: String,
    ) {
        /** The first video frame was on screen within the window. */
        PICTURE("picture"),

        /** No picture within the window ([ConnectTimer.WINDOW_MS]) while the session with the adapter was up. */
        NO_PHONE("no_phone"),

        /** It ended before a picture: [EndReason]. */
        ENDED_EARLY("ended_early"),
    }

    enum class EndReason(
        val code: String,
        val words: String,
    ) {
        DISPLAY_OFF("display_off", "the display off again"),
        APP_CLOSED("app_closed", "Carlink closed"),

        /** The window ended with no session with the adapter: its start or its session failed. */
        ERROR("error", "an error"),
    }
}
