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
    private val sent = mutableListOf<Pair<Int, JSONObject>>()
    private val playback = VideoPlayback { stream, json -> sent.add(stream to JSONObject(json)) }

    private fun record(
        message: String,
        client: String = VideoPlayback.PLAYBACK_CLIENT,
    ) = JSONObject("""{"kind":"dataStream","stream":3,"client":"$client","message":$message}""")

    private val insert =
        """{"type":"insertPlayQueueItem","kind":"request","messageID":7,
           "item":{"uuid":"item-1","Start-Position":12.5,"Content-Location":"https://cdn.example.com/v/master.m3u8?sig=x"}}"""

    @Test
    fun `a queued item is kept and its request answered`() {
        playback.onRecord(record(insert))
        assertEquals(VideoPlayback.Item("item-1", 12.5, "https://cdn.example.com/v/master.m3u8?sig=x"), playback.state.item)
        assertEquals(12.5, playback.state.positionS, 0.0)
        val (stream, answer) = sent.single()
        assertEquals(3, stream)
        assertEquals("response", answer.getString("kind"))
        assertEquals("insertPlayQueueItem", answer.getString("type"))
        assertEquals(7, answer.getInt("messageID"))
        assertFalse("only playbackInfo carries a value", answer.has("value"))
    }

    @Test
    fun `playbackInfo says nothing plays yet, for the queued item`() {
        playback.onRecord(record(insert))
        playback.onRecord(record("""{"type":"setRate","kind":"request","messageID":8,"rate":1.0}"""))
        assertEquals(1.0, playback.askedRate, 0.0)
        playback.onRecord(record("""{"type":"playbackInfo","kind":"request","messageID":9,"property":"x"}"""))
        val answer = sent.last().second
        assertEquals("playbackInfo", answer.getString("type"))
        assertEquals("x", answer.getString("property"))
        val value = answer.getJSONObject("value")
        assertFalse(value.getBoolean("readyToPlay"))
        assertEquals(0.0, value.getDouble("rate"), 0.0)
        assertEquals(12.5, value.getDouble("position"), 0.0)
        assertEquals("item-1", value.getString("uuid"))
        assertEquals(0, value.getJSONArray("seekableTimeRanges").length())
    }

    @Test
    fun `seek moves, stop clears, and a message that isn't a request gets no answer`() {
        playback.onRecord(record(insert))
        sent.clear()
        playback.onRecord(record("""{"type":"seek","time":30.0}"""))
        assertEquals(30.0, playback.state.positionS, 0.0)
        assertTrue("no kind: request, no answer", sent.isEmpty())
        playback.onRecord(record("""{"type":"stop","kind":"request","messageID":10}"""))
        assertNull(playback.state.item)
        assertEquals("stop", sent.single().second.getString("type"))
    }

    @Test
    fun `another channel's messages are left alone, and the session's end forgets the queue`() {
        playback.onRecord(record(insert, client = "BB493F61-A6B8-4769-8D74-80C23A9F71C4"))
        assertNull(playback.state.item)
        assertTrue(sent.isEmpty())
        playback.onRecord(record(insert))
        playback.reset()
        assertNull(playback.state.item)
    }

    @Test
    fun `the log names an address by its host only`() {
        assertEquals("cdn.example.com", VideoPlayback.hostOf("https://user:pw@cdn.example.com:443/v/master.m3u8?sig=s3cret"))
        assertNull(VideoPlayback.hostOf(null))
        assertNull(VideoPlayback.hostOf("not a url"))
    }
}
