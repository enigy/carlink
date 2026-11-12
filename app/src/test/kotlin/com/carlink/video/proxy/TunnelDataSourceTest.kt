package com.carlink.video.proxy

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/** ExoPlayer's GETs over the phone's network, against canned responses in place of the tunnel. */
@RunWith(RobolectricTestRunner::class) // android.net.Uri
class TunnelDataSourceTest {
    /** One connection asked for: where to, the request it was sent, and the response it gave. */
    private class Asked(
        val host: String,
        val port: Int,
        val https: Boolean,
        val request: ByteArrayOutputStream = ByteArrayOutputStream(),
    )

    private val asked = mutableListOf<Asked>()
    private val responses = ArrayDeque<String>()

    private val source =
        TunnelDataSource { host, port, https ->
            val a = Asked(host, port, https)
            asked += a
            val response = responses.removeFirstOrNull() ?: throw IOException("no tunnel")
            Connection(ByteArrayInputStream(response.toByteArray(Charsets.ISO_8859_1)), a.request) {}
        }

    private fun spec(
        url: String,
        position: Long = 0,
        length: Long = C.LENGTH_UNSET.toLong(),
    ) = DataSpec
        .Builder()
        .setUri(Uri.parse(url))
        .setPosition(position)
        .setLength(length)
        .build()

    private fun readAll(): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(7)
        while (true) {
            val n = source.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.ISO_8859_1.name())
    }

    @Test
    fun `a playlist comes whole, its request a plain GET to the origin`() {
        responses += "HTTP/1.1 200 OK\r\nContent-Length: 18\r\nContent-Type: application/vnd.apple.mpegurl\r\n\r\n#EXTM3U\n#EXT-X-END"
        assertEquals(18L, source.open(spec("https://devstreaming-cdn.apple.com/videos/master.m3u8?a=1")))
        assertEquals("#EXTM3U\n#EXT-X-END", readAll())
        source.close()
        val a = asked.single()
        assertEquals("devstreaming-cdn.apple.com", a.host)
        assertEquals(443, a.port)
        assertTrue(a.https)
        val request = a.request.toString()
        assertTrue(request, request.startsWith("GET /videos/master.m3u8?a=1 HTTP/1.1\r\nHost: devstreaming-cdn.apple.com\r\n"))
        assertFalse("no range for the whole of it", request.contains("Range:"))
        assertTrue(request.contains("Connection: close\r\n"))
        assertEquals("application/vnd.apple.mpegurl", source.responseHeaders["Content-Type"]?.single())
    }

    @Test
    fun `a part comes as a range, and a server that sends it all is skipped to the part`() {
        responses += "HTTP/1.1 206 Partial Content\r\nContent-Length: 4\r\n\r\nWXYZ"
        assertEquals(4L, source.open(spec("http://cdn.example.com:8080/seg.ts", position = 100, length = 4)))
        assertEquals("WXYZ", readAll())
        source.close()
        val request = asked.last().request.toString()
        assertTrue(request.contains("Range: bytes=100-103\r\n"))
        assertTrue(request.contains("Host: cdn.example.com:8080\r\n"))
        assertFalse(asked.last().https)
        responses += "HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\n0123456789"
        assertEquals("what is left past the skip", 4L, source.open(spec("http://cdn.example.com/seg.ts", position = 6)))
        assertEquals("6789", readAll())
        source.close()
    }

    @Test
    fun `a redirect is followed, and the final address is the source's`() {
        responses += "HTTP/1.1 302 Found\r\nLocation: /elsewhere/seg.ts\r\nContent-Length: 0\r\n\r\n"
        responses += "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n"
        assertEquals(C.LENGTH_UNSET.toLong(), source.open(spec("https://a.example.com/seg.ts")))
        assertEquals("abc", readAll())
        assertEquals("https://a.example.com/elsewhere/seg.ts", source.uri.toString())
        source.close()
        assertEquals(2, asked.size)
    }

    @Test
    fun `an error status and a tunnel that won't open come out as Media3's HTTP errors`() {
        responses += "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n"
        try {
            source.open(spec("https://cdn.example.com/key"))
            fail("a 403")
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            assertEquals(403, e.responseCode)
        }
        source.close()
        try {
            source.open(spec("https://cdn.example.com/seg.ts"))
            fail("no tunnel")
        } catch (e: HttpDataSource.HttpDataSourceException) {
            assertTrue(e.cause is IOException)
        }
        source.close()
        assertNull(TunnelDataSource.rangeOf(spec("https://x/y")))
        assertEquals("bytes=5-", TunnelDataSource.rangeOf(spec("https://x/y", position = 5)))
        assertEquals("/", TunnelDataSource.pathOf(Uri.parse("https://x")))
    }
}
