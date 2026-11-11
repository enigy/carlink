package com.carlink.video

import com.carlink.logging.Logger
import com.carlink.logging.logInfo
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
 * `Content-Location`, the HLS address), `setProperty`, `setRate`, `seek` (to `time`), `stop`, and `playbackInfo`
 * (about once a second while a video is up). Each marked `kind: request` is answered: `kind: response` with the
 * request's `type`, `messageID`, and its `controlMessage` and `property` when present. A `playbackInfo` answer
 * carries the player's state in `value`. Those are protocol facts from LIVI's receiver, which plays this channel.
 *
 * The [engine] plays the item; the screen ([VideoOverlay]) shows while [showing]. Video is parked only: while
 * [driving], a play request leaves it paused, and the drive starting pauses it and closes the screen. Logs name
 * the address's host only.
 */
class VideoPlayback(
    /** Sends one JSON answer on the phone's stream; false when it was dropped. Any thread. */
    private val send: (stream: Int, json: String) -> Boolean,
    private val engine: VideoEngine,
    /** Whether the car is moving now. */
    private val driving: () -> Boolean,
) {
    /** The item the phone queued: its `uuid`, where to start, and its address (kept, never logged past the host). */
    data class Item(
        val uuid: String?,
        val startS: Double,
        val url: String?,
    )

    /** The queued item and the position the phone last set. */
    data class State(
        val item: Item? = null,
        val positionS: Double = 0.0,
    )

    @Volatile
    var state = State()
        private set

    /** The rate the phone last asked for. */
    @Volatile
    var askedRate = 0.0
        private set

    private val showingState = MutableStateFlow(false)

    /** Whether the video screen is up ([VideoOverlay]). */
    val showing: StateFlow<Boolean> = showingState.asStateFlow()

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
            "insertPlayQueueItem" -> {
                val item = state.item
                logInfo("[VIDEO] the phone queued an item: ${hostOf(item?.url) ?: "no address"}, from ${item?.startS} s", tag = Logger.Tags.ADAPTR)
                item?.url?.let { engine.load(it, item.startS) }
            }
            "setRate" -> playAt(message.optDouble("rate", 0.0))
            "seek" -> message.optDouble("time", Double.NaN).takeUnless { it.isNaN() }?.let { engine.seek(it) }
            "stop" -> {
                engine.stop()
                showingState.value = false
                logInfo("[VIDEO] the phone stopped the video", tag = Logger.Tags.ADAPTR)
            }
            "playbackInfo", "setProperty" -> Unit
            else -> logInfo("[VIDEO] the phone sent '$type' (not handled)", tag = Logger.Tags.ADAPTR)
        }
    }

    /** Answer [message] on [stream] when it is a request; a poll's answer is logged only when it fails. */
    private fun reply(
        stream: Int,
        message: JSONObject,
    ) {
        val answer = answer(message, state, engine.snapshot()) ?: return
        val sent = send(stream, answer.toString())
        if (!sent || message.optString("type") != "playbackInfo") {
            logInfo("[VIDEO] answered ${message.optString("type")} on stream $stream: sent=$sent", tag = Logger.Tags.ADAPTR)
        }
    }

    private fun playAt(rate: Double) {
        askedRate = rate
        when {
            rate <= 0.0 -> engine.setRate(0.0)
            driving() -> {
                engine.setRate(0.0)
                logInfo("[VIDEO] the phone asks to play, but the car is moving: video plays parked only", tag = Logger.Tags.ADAPTR)
                return
            }
            else -> {
                engine.setRate(rate)
                showingState.value = true
            }
        }
        logInfo("[VIDEO] the phone asks for rate $rate", tag = Logger.Tags.ADAPTR)
    }

    /** The car started or stopped moving: moving pauses the video and closes its screen. */
    fun onDriving(moving: Boolean) {
        if (!moving || !showingState.value) return
        engine.setRate(0.0)
        showingState.value = false
        logInfo("[VIDEO] the car is moving: the video paused and its screen closed", tag = Logger.Tags.ADAPTR)
    }

    /** iOS asked for the car's video screen (`requestUI` with `videoplayback:`). True when there is one to show. */
    fun showRequested(): Boolean {
        val shown = state.item != null && !driving()
        if (shown) showingState.value = true
        val why =
            when {
                shown -> "shown"
                driving() -> "not while moving"
                else -> "nothing is queued"
            }
        logInfo("[VIDEO] iOS asks for the video screen: $why", tag = Logger.Tags.ADAPTR)
        return shown
    }

    /** The screen's Close: pause and close; the phone sees the rate drop to 0 at its next poll. */
    fun close() {
        engine.setRate(0.0)
        showingState.value = false
        logInfo("[VIDEO] closed on the car's screen", tag = Logger.Tags.ADAPTR)
    }

    /** Forget the queue and free the player: the session ended. */
    @Synchronized
    fun reset() {
        engine.stop()
        state = State()
        askedRate = 0.0
        showingState.value = false
    }

    companion object {
        /** The phone's Playback channel, by its `clientTypeUUID` (CarPlaySDK's UrlFling). */
        const val PLAYBACK_CLIENT = "A6B27562-B43A-4F2D-B75F-82391E250194"

        /** [state] after the phone's [message]. */
        fun apply(
            state: State,
            message: JSONObject,
        ): State =
            when (message.optString("type")) {
                "insertPlayQueueItem" -> {
                    val item = message.optJSONObject("item")
                    val start = item?.optDouble("Start-Position", 0.0)?.takeUnless { it.isNaN() } ?: 0.0
                    State(Item(item?.optString("uuid")?.ifEmpty { null }, start, item?.optString("Content-Location")?.ifEmpty { null }), start)
                }
                "seek" -> state.copy(positionS = message.optDouble("time", state.positionS).takeUnless { it.isNaN() } ?: state.positionS)
                "stop" -> State()
                else -> state
            }

        /** The answer to [message] when it is a request, else null. */
        fun answer(
            message: JSONObject,
            state: State,
            playing: VideoEngine.Snapshot,
        ): JSONObject? {
            if (message.optString("kind") != "request") return null
            val answer = JSONObject().put("kind", "response")
            for (key in listOf("type", "controlMessage", "property", "messageID")) {
                message.opt(key)?.let { answer.put(key, it) }
            }
            if (message.optString("type") == "playbackInfo") answer.put("value", info(state, playing))
            return answer
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

        /** An address's host, for the log: never more of it. */
        fun hostOf(url: String?): String? = url?.let { runCatching { java.net.URI(it).host }.getOrNull() }
    }
}
