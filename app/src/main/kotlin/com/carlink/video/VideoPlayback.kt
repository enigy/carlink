package com.carlink.video

import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Route A's play queue: the phone's video Playback channel (UrlFling `A6B27562`, LIVI's MatchPoint) offered inside
 * the CarPlay session. The box relays it both ways: the phone's messages arrive as `{"kind":"dataStream"}` records,
 * and the answers go back with [com.carlink.ocbm.OcbmClient.sendDataStream] (OCBM `receiver::playback`).
 *
 * The phone's messages, as JSON: `insertPlayQueueItem` (an `item` with its `uuid`, `Start-Position` and
 * `Content-Location`, the HLS address), `setRate`, `seek` (to `time`), `stop`, and `playbackInfo`. Each marked
 * `kind: request` is answered: `kind: response` with the request's `type`, `messageID`, and its `controlMessage` and
 * `property` when present. A `playbackInfo` answer carries the playback state in `value`. Those are protocol facts
 * from LIVI's receiver, which plays this channel.
 *
 * Nothing plays yet: the state says so (not ready, rate 0), so the phone sees a car that took the item but hasn't
 * started it. The player is the next step. Logs name the address's host only.
 */
class VideoPlayback(
    /** Sends one JSON answer on the phone's stream; false when it was dropped. Any thread. */
    private val send: (stream: Int, json: String) -> Boolean,
) {
    /** The item the phone queued: its `uuid`, where to start, and its address (kept, never logged past the host). */
    data class Item(
        val uuid: String?,
        val startS: Double,
        val url: String?,
    )

    /** What the car reports: the queued item and the position. Nothing plays, so the rate is always 0. */
    data class State(
        val item: Item? = null,
        val positionS: Double = 0.0,
    )

    @Volatile
    var state = State()
        private set

    /** The rate the phone last asked for, for the log: the player that honours it is the next step. */
    @Volatile
    var askedRate = 0.0
        private set

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
        when (val type = message.optString("type")) {
            "insertPlayQueueItem" -> {
                val item = state.item
                logInfo("[VIDEO] the phone queued an item: ${hostOf(item?.url) ?: "no address"}, from ${item?.startS} s (nothing plays yet)", tag = Logger.Tags.ADAPTR)
            }
            "setRate" -> {
                askedRate = message.optDouble("rate", 0.0)
                logInfo("[VIDEO] the phone asks for rate $askedRate (nothing plays yet)", tag = Logger.Tags.ADAPTR)
            }
            "seek", "stop", "playbackInfo" -> Unit
            else -> logInfo("[VIDEO] the phone sent '$type' (not handled)", tag = Logger.Tags.ADAPTR)
        }
        val answer = answer(message, state) ?: return
        val sent = send(stream, answer.toString())
        logInfo("[VIDEO] answered ${message.optString("type")} on stream $stream: sent=$sent", tag = Logger.Tags.ADAPTR)
    }

    /** Forget the queue: the session ended. */
    @Synchronized
    fun reset() {
        state = State()
        askedRate = 0.0
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
        ): JSONObject? {
            if (message.optString("kind") != "request") return null
            val answer = JSONObject().put("kind", "response")
            for (key in listOf("type", "controlMessage", "property", "messageID")) {
                message.opt(key)?.let { answer.put(key, it) }
            }
            if (message.optString("type") == "playbackInfo") answer.put("value", info(state))
            return answer
        }

        /** `playbackInfo`'s value: where the car stands. Nothing is loaded or playing, so not ready, at rate 0. */
        fun info(state: State): JSONObject =
            JSONObject()
                .put("duration", 0.0)
                .put("position", state.positionS)
                .put("rate", 0.0)
                .put("readyToPlay", false)
                .put("playbackBufferEmpty", true)
                .put("playbackBufferFull", false)
                .put("playbackLikelyToKeepUp", false)
                .put("loadedTimeRanges", JSONArray())
                .put("seekableTimeRanges", JSONArray())
                .also { o -> state.item?.uuid?.let { o.put("uuid", it) } }

        /** An address's host, for the log: never more of it. */
        fun hostOf(url: String?): String? = url?.let { runCatching { java.net.URI(it).host }.getOrNull() }
    }
}
