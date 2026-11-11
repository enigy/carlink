package com.carlink.video

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The phone's Playback-channel messages as the box relays them (OCBM receiver::playback). */
@RunWith(RobolectricTestRunner::class) // VideoPlayback logs through android.util.Log
class VideoPlaybackTest {
    /** A player that records what it was told and reports [now]. */
    private class FakeEngine : VideoEngine {
        val calls = mutableListOf<String>()
        var now = VideoEngine.Snapshot()

        override fun load(
            url: String,
            startS: Double,
        ) {
            calls += "load $startS"
        }

        override fun setRate(rate: Double) {
            calls += "rate $rate"
        }

        override fun seek(toS: Double) {
            calls += "seek $toS"
        }

        override fun stop() {
            calls += "stop"
        }

        override fun snapshot() = now
    }

    private val sent = mutableListOf<Pair<Int, JSONObject>>()
    private val engine = FakeEngine()
    private var moving = false
    private val playback = VideoPlayback({ stream, json -> sent.add(stream to JSONObject(json)) }, engine, { moving })

    private fun record(
        message: String,
        client: String = VideoPlayback.PLAYBACK_CLIENT,
    ) = JSONObject("""{"kind":"dataStream","stream":3,"client":"$client","message":$message}""")

    private val insert =
        """{"type":"insertPlayQueueItem","kind":"request","messageID":7,
           "item":{"uuid":"item-1","Start-Position":12.5,"Content-Location":"https://cdn.example.com/v/master.m3u8?sig=x"}}"""

    private fun rate(r: Double) = record("""{"type":"setRate","kind":"request","messageID":8,"rate":$r}""")

    @Test
    fun `a queued item is loaded and its request answered`() {
        playback.onRecord(record(insert))
        assertEquals(VideoPlayback.Item("item-1", 12.5, "https://cdn.example.com/v/master.m3u8?sig=x"), playback.state.item)
        assertEquals(listOf("load 12.5"), engine.calls)
        val (stream, answer) = sent.single()
        assertEquals(3, stream)
        assertEquals("response", answer.getString("kind"))
        assertEquals("insertPlayQueueItem", answer.getString("type"))
        assertEquals(7, answer.getInt("messageID"))
        assertFalse("only playbackInfo carries a value", answer.has("value"))
        assertFalse("nothing shows before play", playback.showing.value)
    }

    @Test
    fun `play when parked plays and shows the screen, and playbackInfo reports the player`() {
        playback.onRecord(record(insert))
        playback.onRecord(rate(1.0))
        assertEquals("rate 1.0", engine.calls.last())
        assertTrue(playback.showing.value)
        engine.now = VideoEngine.Snapshot(ready = true, rate = 1.0, positionS = 20.0, durationS = 90.0, bufferedS = 50.0)
        playback.onRecord(record("""{"type":"playbackInfo","kind":"request","messageID":9,"property":"x"}"""))
        val answer = sent.last().second
        assertEquals("x", answer.getString("property"))
        val value = answer.getJSONObject("value")
        assertTrue(value.getBoolean("readyToPlay"))
        assertEquals(1.0, value.getDouble("rate"), 0.0)
        assertEquals(20.0, value.getDouble("position"), 0.0)
        assertEquals(90.0, value.getDouble("duration"), 0.0)
        assertEquals("item-1", value.getString("uuid"))
        assertEquals(30.0, value.getJSONArray("loadedTimeRanges").getJSONObject(0).getDouble("duration"), 0.0)
        assertEquals(90.0, value.getJSONArray("seekableTimeRanges").getJSONObject(0).getDouble("duration"), 0.0)
    }

    @Test
    fun `before the player is ready, playbackInfo says not ready at the item's start`() {
        playback.onRecord(record(insert))
        playback.onRecord(record("""{"type":"playbackInfo","kind":"request","messageID":9}"""))
        val value = sent.last().second.getJSONObject("value")
        assertFalse(value.getBoolean("readyToPlay"))
        assertEquals(12.5, value.getDouble("position"), 0.0)
        assertEquals(0, value.getJSONArray("seekableTimeRanges").length())
    }

    @Test
    fun `video plays parked only`() {
        playback.onRecord(record(insert))
        moving = true
        playback.onRecord(rate(1.0))
        assertEquals("rate 0.0", engine.calls.last())
        assertFalse(playback.showing.value)
        assertFalse(playback.showRequested())
        moving = false
        playback.onRecord(rate(1.0))
        assertTrue(playback.showing.value)
        moving = true
        playback.onDriving(true)
        assertEquals("rate 0.0", engine.calls.last())
        assertFalse("the drive closes the screen", playback.showing.value)
    }

    @Test
    fun `seek moves, stop frees the player, and a message that isn't a request gets no answer`() {
        playback.onRecord(record(insert))
        sent.clear()
        playback.onRecord(record("""{"type":"seek","time":30.0}"""))
        assertEquals("seek 30.0", engine.calls.last())
        assertTrue("no kind: request, no answer", sent.isEmpty())
        playback.onRecord(record("""{"type":"stop","kind":"request","messageID":10}"""))
        assertEquals("stop", engine.calls.last())
        assertNull(playback.state.item)
        assertEquals("stop", sent.single().second.getString("type"))
    }

    @Test
    fun `iOS's request for the video screen, and the screen's Close`() {
        assertFalse("nothing queued", playback.showRequested())
        playback.onRecord(record(insert))
        assertTrue(playback.showRequested())
        assertTrue(playback.showing.value)
        playback.close()
        assertFalse(playback.showing.value)
        assertEquals("rate 0.0", engine.calls.last())
    }

    @Test
    fun `another channel's messages are left alone, and the session's end frees the player`() {
        playback.onRecord(record(insert, client = "BB493F61-A6B8-4769-8D74-80C23A9F71C4"))
        assertNull(playback.state.item)
        assertTrue(sent.isEmpty() && engine.calls.isEmpty())
        playback.onRecord(record(insert))
        playback.reset()
        assertNull(playback.state.item)
        assertEquals("stop", engine.calls.last())
    }

    @Test
    fun `the log names an address by its host only, and HLS is told by its path`() {
        assertEquals("cdn.example.com", VideoPlayback.hostOf("https://user:pw@cdn.example.com:443/v/master.m3u8?sig=s3cret"))
        assertNull(VideoPlayback.hostOf(null))
        assertNull(VideoPlayback.hostOf("not a url"))
        assertTrue(ExoVideoEngine.isHls("https://cdn.example.com/v/master.m3u8?sig=x"))
        assertTrue("no extension: AirPlay video's usual playlist", ExoVideoEngine.isHls("https://linear.tv.apple.com/stream/abc"))
        assertFalse(ExoVideoEngine.isHls("https://cdn.example.com/v/clip.mp4"))
    }
}
