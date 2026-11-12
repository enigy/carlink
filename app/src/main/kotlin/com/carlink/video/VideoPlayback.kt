package com.carlink.video

import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.ocbm.Ocbm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Route A's play queue: the phone's video Playback channel (UrlFling `A6B27562`, LIVI's MatchPoint) offered inside
 * the CarPlay session. The box relays it both ways: the phone's messages arrive as `{"kind":"dataStream"}` records,
 * and the answers go back with [com.carlink.ocbm.OcbmClient.sendDataStream] (OCBM `receiver::playback`).
 *
 * The phone's messages, as JSON: `insertPlayQueueItem` (an `item` with its `uuid`, `Start-Position` and
 * `Content-Location`, the HLS address), `setProperty`, `setRate`, `seek` (to `time`), `stop`, `removePlayQueueItem`,
 * and `playbackInfo` (about once a second while a video is up). Each marked `kind: request` is answered: `kind:
 * response` with the request's `type`, `messageID`, and its `controlMessage` and `property` when present. A
 * `playbackInfo` answer carries the player's state in `value`. Those are protocol facts from LIVI's receiver, which
 * plays this channel. A time is seconds, or a CMTime (Safari's `Start-Position`, the Mac's 10 Nov bench).
 *
 * The [engine] plays the item; the screen ([VideoOverlay]) shows while [showing]. Video is parked only, and parked
 * must be known: unless [parked] says so, a play request leaves it paused, and losing it pauses the video and
 * closes the screen. The phone hears whether video may play ([announce], LIVI's `setVideoPlaybackAllowed`) at the
 * session's start, at play start and at each change. A pause the car makes presses the phone's Pause key too, as
 * LIVI pauses the phone: on the Mac's 10 Nov bench the phone didn't follow `playbackInfo`'s rate. Logs name
 * the address's host only.
 */
@Suppress("TooManyFunctions") // the phone's play-queue commands, the car's parking and Close: one entry point each
class VideoPlayback(
    /** Sends one JSON answer on the phone's stream; false when it was dropped. Any thread. */
    private val send: (stream: Int, json: String) -> Boolean,
    private val engine: VideoEngine,
    /** Whether the car is known to be parked now: false when it is moving or its state isn't known. */
    private val parked: () -> Boolean,
    /** Tells the phone, through the box, whether video may play now and the queue's [Status]. Any thread. */
    private val announce: (allowed: Boolean, status: Status) -> Unit,
    /** Presses Pause on the phone (its media key), so its player follows a pause the car made. Any thread. */
    private val pausePhone: () -> Unit,
    /**
     * Whether the phone offered its network (`setProxyParameters`): route A's video comes only through it, never over the
     * head unit's own, which could bill the car's data plan (owner, 12 Nov).
     */
    private val viaPhone: () -> Boolean,
) {
    /** The play queue's status as `setVideoPlaybackAllowed` names it (LIVI's words), with its wire byte. */
    enum class Status(
        val wire: Byte,
    ) {
        /** Nothing queued. */
        IDLE(Ocbm.VIDEO_STATUS_IDLE),

        /** A queued video that may show. */
        VIDEO(Ocbm.VIDEO_STATUS_VIDEO),

        /** A queued video that may not show now. */
        AUDIO_ONLY(Ocbm.VIDEO_STATUS_AUDIO_ONLY),
    }

    /** The item the phone queued: its `uuid`, where to start, and its address (kept, never logged past the host). */
    data class Item(
        val uuid: String?,
        val startS: Double,
        val url: String?,
    )

    /** The queued item, the position the phone last set, and the player's error code once it failed on the item. */
    data class State(
        val item: Item? = null,
        val positionS: Double = 0.0,
        val failed: String? = null,
    )

    @Volatile
    var state = State()
        private set

    /** The rate the phone last asked for. */
    @Volatile
    var askedRate = 0.0
        private set

    /**
     * Set by a player failure until the phone drops the item, or parking changes: the phone was told video may not play
     * here ([onPlayerFailed]) and hears that it may again then.
     */
    @Volatile
    private var refused = false

    /** The queued item is in the player: it loads only once it may play (parked, and through the phone). */
    @Volatile
    private var loaded = false

    private val showingState = MutableStateFlow(false)

    /** Whether the video screen is up ([VideoOverlay]). */
    val showing: StateFlow<Boolean> = showingState.asStateFlow()

    init {
        engine.onFailed = { code -> onPlayerFailed(code) }
    }

    /** The player failed on the item (DRM it can't play, a network failure): its screen closes. */
    @Synchronized
    private fun onPlayerFailed(code: String) {
        // The Mac's 11 Nov bench: after a FairPlay failure the phone ignored Pause and setVideoPlaybackAllowed(true,
        // audioOnly) and stayed "playing", asking for playbackInfo once a second. No source gives this channel a failure
        // report yet, so the phone now hears what a drive makes it hear, video not allowed here: the word it has
        // followed. Video is allowed again once the phone drops the item, or at the next parking change.
        val why = if (code == VideoEngine.FAIRPLAY) "the item is FairPlay-protected: declined" else "the player failed ($code): its screen closed"
        endHere(code, why)
    }

    /** One `{"kind":"dataStream"}` record from the box. Any thread. */
    @Synchronized
    fun onRecord(obj: JSONObject) {
        val stream = obj.optInt("stream", -1)
        val message = obj.optJSONObject("message") ?: return
        val client = obj.optString("client")
        if (!client.equals(PLAYBACK_CLIENT, ignoreCase = true)) {
            logInfo("[VIDEO] a ${message.optString("type").ifEmpty { "typeless" }} message on another channel ($client): not the play queue", tag = Logger.Tags.ADAPTR)
            return
        }
        state = apply(state, message)
        act(message)
        reply(stream, message)
    }

    /** Carry out the phone's [message] on the player and the screen. */
    private fun act(message: JSONObject) {
        when (val type = message.optString("type")) {
            "insertPlayQueueItem" -> onQueued()
            "setRate" -> playAt(message.optDouble("rate", 0.0))
            "seek" -> seconds(message.opt("time"))?.let { engine.seek(it) }
            "stop" -> drop("the phone stopped the video")
            "removePlayQueueItem" ->
                if (state.item == null) {
                    drop("the phone removed the queued item")
                } else {
                    logInfo("[VIDEO] the phone removed an item that isn't the queued one: it stays", tag = Logger.Tags.ADAPTR)
                }
            "property", "getProperty" -> logInfo("[VIDEO] the phone asks for ${message.optString("property").ifEmpty { "a nameless property" }}", tag = Logger.Tags.ADAPTR)
            "playbackInfo", "setProperty" -> Unit
            else -> logInfo("[VIDEO] the phone sent '$type' (not handled)", tag = Logger.Tags.ADAPTR)
        }
    }

    /**
     * The phone queued an item ([state] has it now): loaded at once only when it may play, through the phone's network
     * and parked. Without the phone's network it is declined (owner, 12 Nov: never over the head unit's); in Drive it
     * waits, unloaded, for a play request once parked (the Mac's 12 Nov bench: it began loading before the refusal).
     */
    private fun onQueued() {
        val item = state.item
        askedRate = 0.0 // a new item waits for its own setRate
        refused = false // its play start tells the phone video may play
        if (loaded) engine.stop() // the last item's player: this one loads when it may play
        loaded = false
        logInfo("[VIDEO] the phone queued an item: ${hostOf(item?.url) ?: "no address"}, from ${item?.startS} s", tag = Logger.Tags.ADAPTR)
        when {
            !viaPhone() -> endHere(NO_PHONE_NETWORK, "the phone hasn't offered its network, and route A's video never comes over the head unit's")
            parked() -> loadQueued()
            else -> logInfo("[VIDEO] not loaded: the car isn't known to be parked", tag = Logger.Tags.ADAPTR)
        }
    }

    /** Answer [message] on [stream] when it is a request; a poll's answer is logged only when it fails. */
    private fun reply(
        stream: Int,
        message: JSONObject,
    ) {
        // An item ended here answers as a failed player does, not ready and nothing buffered: the answer the phone let go
        // of its item for (the Mac's bench #5, 80 ms after it). A paused, ready player kept the phone asking to play (r3).
        val playing = if (state.failed != null) VideoEngine.Snapshot(positionS = state.positionS, failed = true) else engine.snapshot()
        val answer = answer(message, state, playing) ?: return
        val sent = send(stream, jsonText(answer))
        if (!sent || message.optString("type") != "playbackInfo") {
            logInfo("[VIDEO] answered ${message.optString("type")} on stream $stream: sent=$sent", tag = Logger.Tags.ADAPTR)
        }
    }

    /** Put the queued item in the player, once: true when it is there. */
    private fun loadQueued(): Boolean {
        if (loaded) return true
        val url = state.item?.url ?: return false
        engine.load(url, state.positionS)
        loaded = true
        return true
    }

    /**
     * End the hand-off here without playing it ([code], [why]): the screen closes, a phone that plays hears Pause, and it
     * is told video may not play here, the word it follows, so it keeps or drops the video; it may again once the phone
     * drops the item, or at the next parking change. A play request for the item leaves it closed.
     */
    private fun endHere(
        code: String,
        why: String,
    ) {
        val wasPlaying = phonePlays
        engine.stop() // nothing more is fetched for it
        loaded = false
        state = state.copy(failed = code)
        showingState.value = false
        if (wasPlaying) pausePhone()
        refused = true
        announce(false, statusOf(state, false))
        logInfo("[VIDEO] $why: the phone told video may not play here${if (wasPlaying) ", Pause sent" else ""}", tag = Logger.Tags.ADAPTR)
    }

    /** Stop the player and close the screen: the queue is empty. After a failure the phone hears video may play again. */
    private fun drop(why: String) {
        engine.stop()
        loaded = false
        askedRate = 0.0
        showingState.value = false
        logInfo("[VIDEO] $why", tag = Logger.Tags.ADAPTR)
        if (refused) {
            refused = false
            val nowParked = parked()
            announce(nowParked, statusOf(state, nowParked))
            logInfo("[VIDEO] the failed item is gone: the phone told video may ${if (nowParked) "" else "not "}play", tag = Logger.Tags.ADAPTR)
        }
    }

    /** Whether the phone thinks its video plays: it asked for a rate and hasn't asked for 0 since. */
    private val phonePlays get() = askedRate > 0.0 && state.item != null

    private fun playAt(rate: Double) {
        val starting = askedRate <= 0.0 && rate > 0.0
        askedRate = rate
        when {
            rate <= 0.0 -> engine.setRate(0.0)
            state.failed != null -> {
                engine.setRate(0.0)
                logInfo("[VIDEO] the phone asks to play an item ended here (${state.failed}): it stays closed", tag = Logger.Tags.ADAPTR)
                return
            }
            !parked() -> {
                engine.setRate(0.0)
                if (starting) announce(false, statusOf(state, false))
                pausePhone()
                logInfo("[VIDEO] the phone asks to play, but the car isn't known to be parked: video plays parked only, Pause sent", tag = Logger.Tags.ADAPTR)
                return
            }
            else -> {
                loadQueued()
                engine.setRate(rate)
                showingState.value = true
                if (starting) announce(true, statusOf(state, true))
            }
        }
        logInfo("[VIDEO] the phone asks for rate $rate", tag = Logger.Tags.ADAPTR)
    }

    /**
     * Whether the car is known to be parked changed, and the phone hears it; also called at the session's start, so
     * the phone hears it then. Losing it (moving, or the car's state no longer known) pauses the video, closes its
     * screen, and presses Pause on a phone that thinks it plays. Becoming parked plays nothing by itself.
     */
    @Synchronized // with onRecord: a play request decided just before this sees its screen closed here
    fun onParked(nowParked: Boolean) {
        if (!nowParked) {
            val wasPlaying = phonePlays
            engine.setRate(0.0)
            if (showingState.value) {
                showingState.value = false
                logInfo("[VIDEO] the car isn't known to be parked: the video paused and its screen closed", tag = Logger.Tags.ADAPTR)
            }
            if (wasPlaying) {
                pausePhone()
                logInfo("[VIDEO] Pause sent to the phone, so its player follows", tag = Logger.Tags.ADAPTR)
            }
        }
        refused = false
        announce(nowParked, statusOf(state, nowParked))
    }

    /** iOS asked for the car's video screen (`requestUI` with `videoplayback:`). True when there is one to show. */
    @Synchronized
    fun showRequested(): Boolean {
        val parkedNow = parked()
        val shown = state.item != null && state.failed == null && parkedNow && loadQueued()
        if (shown) showingState.value = true
        val why =
            when {
                shown -> "shown"
                !parkedNow -> "not unless parked"
                state.failed != null -> "not: the item was ended here (${state.failed})"
                else -> "nothing is queued"
            }
        logInfo("[VIDEO] iOS asks for the video screen: $why", tag = Logger.Tags.ADAPTR)
        return shown
    }

    /**
     * The screen's Close: it ends the hand-off whether the video plays or is paused (the Mac's 12 Nov bench: closed while
     * paused, the phone kept its item and reopened it at its next play). Pause for a phone that plays, and video may not
     * play here until the phone drops the item ([endHere]).
     */
    @Synchronized
    fun close() {
        if (state.item == null) {
            engine.setRate(0.0)
            showingState.value = false
            logInfo("[VIDEO] closed on the car's screen", tag = Logger.Tags.ADAPTR)
            return
        }
        endHere(CLOSED_HERE, "closed on the car's screen")
    }

    /** Forget the queue and free the player: the session ended. */
    @Synchronized
    fun reset() {
        engine.stop()
        state = State()
        askedRate = 0.0
        refused = false
        loaded = false
        showingState.value = false
    }

    companion object {
        /** The phone's Playback channel, by its `clientTypeUUID` (CarPlaySDK's UrlFling). */
        const val PLAYBACK_CLIENT = "A6B27562-B43A-4F2D-B75F-82391E250194"

        /** Why an item was ended here without playing ([State.failed]): no phone network for it, or the screen's Close. */
        const val NO_PHONE_NETWORK = "NO_PHONE_NETWORK"
        const val CLOSED_HERE = "CLOSED_ON_THE_CAR"

        /** CMTime's flags: valid, and the ones that make it not a number (+infinity, -infinity, indefinite). */
        private const val CMTIME_VALID = 0x1L
        private const val CMTIME_NOT_NUMERIC = 0x1CL

        /** The queue's status for the phone: nothing queued, or a queued video that may or may not show. */
        fun statusOf(
            state: State,
            allowed: Boolean,
        ): Status =
            when {
                state.item == null -> Status.IDLE
                allowed -> Status.VIDEO
                else -> Status.AUDIO_ONLY
            }

        /** [state] after the phone's [message]. */
        fun apply(
            state: State,
            message: JSONObject,
        ): State =
            when (message.optString("type")) {
                "insertPlayQueueItem" -> {
                    val item = message.optJSONObject("item")
                    val start = seconds(item?.opt("Start-Position")) ?: 0.0
                    State(Item(item?.optString("uuid")?.ifEmpty { null }, start, item?.optString("Content-Location")?.ifEmpty { null }), start)
                }
                "seek" -> state.copy(positionS = seconds(message.opt("time")) ?: state.positionS)
                "stop" -> State()
                "removePlayQueueItem" -> if (removes(message, state)) State() else state
                else -> state
            }

        /** Whether `removePlayQueueItem` [message] takes the queued item: it names that item's uuid, or none. */
        private fun removes(
            message: JSONObject,
            state: State,
        ): Boolean {
            val uuid = message.optJSONObject("item")?.optString("uuid")?.ifEmpty { null } ?: message.optString("uuid").ifEmpty { null }
            return uuid == null || uuid == state.item?.uuid
        }

        /**
         * A time in seconds: a number, or a CMTime dictionary (CoreMedia's `value` over `timescale`, only when its
         * `flags` say valid and numeric). Null for anything else.
         */
        fun seconds(time: Any?): Double? =
            when (time) {
                is Number -> time.toDouble()
                is String -> time.toDoubleOrNull()
                is JSONObject -> {
                    val flags = time.optLong("flags")
                    val scale = time.optLong("timescale")
                    val numeric = (flags and CMTIME_VALID) != 0L && (flags and CMTIME_NOT_NUMERIC) == 0L
                    if (numeric && scale > 0 && time.has("value")) time.optLong("value") / scale.toDouble() else null
                }
                else -> null
            }?.takeIf { it.isFinite() }

        /** The answer to [message] when it is a request (or carries a `messageID` and no `kind`), else null. */
        fun answer(
            message: JSONObject,
            state: State,
            playing: VideoEngine.Snapshot,
        ): JSONObject? {
            val kind = message.optString("kind")
            if (kind != "request" && !(kind.isEmpty() && message.has("messageID"))) return null
            val answer = JSONObject().put("kind", "response")
            for (key in listOf("type", "controlMessage", "property", "messageID")) {
                message.opt(key)?.let { answer.put(key, it) }
            }
            when (message.optString("type")) {
                "playbackInfo" -> answer.put("value", info(state, playing))
                "property", "getProperty" -> propertyValue(message.optString("property"))?.let { answer.put("value", it) }
            }
            return answer
        }

        /**
         * A `property` request's value. The player's access and error logs, which the phone asks for (the Mac's
         * 10 Nov bench: `playbackAccessLog`), are empty lists: Carlink keeps neither. Anything else is answered
         * without a value, as LIVI answers every property request.
         */
        fun propertyValue(name: String): Any? =
            when (name) {
                "playbackAccessLog", "playbackErrorLog" -> JSONArray()
                else -> null
            }

        /** `playbackInfo`'s value: the player's state, with the queued item's uuid. */
        fun info(
            state: State,
            playing: VideoEngine.Snapshot,
        ): JSONObject {
            val position = if (playing.ready || playing.positionS > 0.0) playing.positionS else state.positionS
            return JSONObject()
                .put("duration", playing.durationS)
                .put("position", position)
                .put("rate", playing.rate)
                .put("readyToPlay", playing.ready)
                .put("playbackBufferEmpty", !playing.ready)
                .put("playbackBufferFull", playing.durationS > 0.0 && playing.bufferedS >= playing.durationS)
                .put("playbackLikelyToKeepUp", playing.ready && !playing.buffering)
                .put("loadedTimeRanges", range(position, playing.bufferedS - position))
                .put("seekableTimeRanges", range(0.0, playing.durationS))
                .also { o -> state.item?.uuid?.let { o.put("uuid", it) } }
        }

        /** A one-range list `[{start, duration}]`, or an empty one when [durationS] isn't positive. */
        private fun range(
            startS: Double,
            durationS: Double,
        ): JSONArray =
            if (durationS > 0.0) {
                JSONArray().put(JSONObject().put("start", startS).put("duration", durationS))
            } else {
                JSONArray()
            }

        /**
         * [value] as JSON text, a whole real written with its point (`1.0`): the box makes a number with a point a
         * plist real and one without an integer, and `JSONObject.toString()` writes 1.0 as `1`, which reached the
         * phone as an integer (the Mac's 10 Nov bench). A real that isn't finite is `null`, which the box leaves out.
         */
        fun jsonText(value: Any?): String =
            when (value) {
                null, JSONObject.NULL -> "null"
                is JSONObject ->
                    value.keys().asSequence().joinToString(",", "{", "}") { key -> JSONObject.quote(key) + ":" + jsonText(value.opt(key)) }
                is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { jsonText(value.opt(it)) }
                is Double -> if (value.isFinite()) value.toString() else "null"
                is Float -> jsonText(value.toDouble())
                is Number, is Boolean -> value.toString()
                else -> JSONObject.quote(value.toString())
            }

        /** An address's host, for the log: never more of it. */
        fun hostOf(url: String?): String? = url?.let { runCatching { java.net.URI(it).host }.getOrNull() }
    }
}
