package com.carlink.ocbm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * MGMT_WIFI_LINK's body, laid out as ocbmd parses it, and the WPA key pinned by IEEE 802.11i's test vectors; v2 (the AP
 * link) and its answer, pinned by the ADDENDUM 24 wire proposal's vectors.
 */
class WifiLinkProvisioningTest {
    private val key = ByteArray(32) { 0x11 }

    @Test
    fun `the WPA key matches IEEE 802_11i's test vectors`() {
        assertEquals("f42c6fc52df0ebef9ebb4b90b38a5f902e83fe1b135a70e23aed762e9710a12e", WifiLinkProvisioning.wpaPsk("password", "IEEE"))
        assertEquals("0dc0d6eb90555ed6419756b9a15ec3e3209b63df707dd508d14581f8982721af", WifiLinkProvisioning.wpaPsk("ThisIsAPassword", "ThisIsASSID"))
        assertEquals("becb93866bb8c3832cb777c2f559807c8c59afcb6eae734885001300a981cc62", WifiLinkProvisioning.wpaPsk("a".repeat(32), "Z".repeat(32)))
        // A name with the iPhone's curly apostrophe (3 UTF-8 bytes); the value is Python's hashlib.pbkdf2_hmac.
        assertEquals("5d4c12cad9e8f86d6f2ee86ad184545bc82853ad25fc6a6032c24452ee2c6203", WifiLinkProvisioning.wpaPsk("correct horse 9!", "Sam\u2019s iPhone"))
    }

    @Test
    fun `the body is version, key, the name and the hashed key, each with its length`() {
        val body = WifiLinkProvisioning.body(key, "MyCar", "password")
        assertEquals(1, body[0].toInt())
        assertArrayEquals(key, body.copyOfRange(1, 33))
        assertEquals(5, body[33].toInt())
        assertEquals("MyCar", String(body, 34, 5))
        assertEquals(64, body[39].toInt())
        assertEquals(WifiLinkProvisioning.wpaPsk("password", "MyCar"), String(body, 40, 64))
        assertEquals(104, body.size)
        // A 32-byte name, the longest, in UTF-8.
        val long = "é".repeat(16)
        assertEquals(32, WifiLinkProvisioning.body(key, long, "password")[33].toInt())
    }

    @Test
    fun `what can't be sent is refused in plain words`() {
        assertNull(WifiLinkProvisioning.problem("MyCar", "password"))
        assertNotNull(WifiLinkProvisioning.problem("", "password"))
        assertNotNull(WifiLinkProvisioning.problem("x".repeat(33), "password"))
        assertNotNull(WifiLinkProvisioning.problem("é".repeat(17), "password"))
        assertNotNull(WifiLinkProvisioning.problem("My\nCar", "password"))
        // A Wi-Fi password is printable ASCII: a keyboard's long dash or curly quote would make another key.
        assertNull(WifiLinkProvisioning.problem("MyCar", "abcd-efgh-1234 ~!"))
        assertNotNull(WifiLinkProvisioning.problem("MyCar", "abcd\u2013efgh"))
        assertNotNull(WifiLinkProvisioning.problem("MyCar", "abcd\u2019efgh"))
        assertNotNull(WifiLinkProvisioning.problem("MyCar", "abcd\tefgh"))
        assertNotNull(WifiLinkProvisioning.problem("MyCar", "short"))
        assertNotNull(WifiLinkProvisioning.problem("MyCar", "x".repeat(64)))
        assertNull(WifiLinkProvisioning.problem("MyCar", "x".repeat(63)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a body is never built for a refused password`() {
        WifiLinkProvisioning.body(key, "MyCar", "short")
    }

    // ---- v2, the AP link (notes/carlink.md, "ADDENDUM 24 wire proposal"): the vectors both sides pin ----

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `v2 is the version, the key and the band, byte for byte the proposal's vectors`() {
        val verb = byteArrayOf(Ocbm.MGMT_WIFI_LINK)
        assertArrayEquals(
            hex("0e02111111111111111111111111111111111111111111111111111111111111111102"),
            verb + WifiLinkProvisioning.apBody(key, WifiLinkProvisioning.BAND_2_4),
        )
        assertArrayEquals(
            hex("0e02111111111111111111111111111111111111111111111111111111111111111105"),
            verb + WifiLinkProvisioning.apBody(key, WifiLinkProvisioning.BAND_5),
        )
        assertEquals(WifiLinkProvisioning.AP_BODY_LEN, WifiLinkProvisioning.apBody(key, WifiLinkProvisioning.BAND_5).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `v2 is never built for a band the adapter doesn't name`() {
        WifiLinkProvisioning.apBody(key, 0x06)
    }

    @Test
    fun `v2's answer gives the adapter's network, byte for byte the proposal's vector`() {
        val a = WifiLinkProvisioning.answer(hex("820e000c4361724c696e6b2d303030300c6578616d706c652d70617373"))!!
        assertEquals(0, a.status)
        assertEquals(ApNetwork("CarLink-0000", "example-pass"), a.network)
        assertFalse("the password never shows in a log line", a.network.toString().contains("example-pass"))
        assertFalse(a.network.toString().contains("CarLink-0000"))
    }

    @Test
    fun `a refusal, a bare ACK and another verb's ACK carry no network`() {
        val refused = WifiLinkProvisioning.answer(byteArrayOf(Ocbm.MGMT_ACK, Ocbm.MGMT_WIFI_LINK, 1))!!
        assertEquals(1, refused.status)
        assertNull(refused.network)
        // A refusal with a tail is still a refusal, and its tail is not read.
        assertNull(WifiLinkProvisioning.answer(ack(1, "CarLink-0000", "example-pass"))!!.network)
        val bare = WifiLinkProvisioning.answer(byteArrayOf(Ocbm.MGMT_ACK, Ocbm.MGMT_WIFI_LINK, 0))!!
        assertEquals(0, bare.status)
        assertNull("v1's ACK, or an adapter that names nothing", bare.network)
        assertNull(WifiLinkProvisioning.answer(byteArrayOf(Ocbm.MGMT_ACK, Ocbm.MGMT_UPDATE, 0)))
        assertNull(WifiLinkProvisioning.answer(byteArrayOf(Ocbm.MGMT_INFO, '{'.code.toByte())))
        assertNull(WifiLinkProvisioning.answer(byteArrayOf(Ocbm.MGMT_ACK, Ocbm.MGMT_WIFI_LINK)))
    }

    @Test
    fun `a network is taken only whole, as hostapd can hold it`() {
        assertEquals("an open network", "", WifiLinkProvisioning.answer(ack(0, "CarLink-0000", ""))!!.network!!.password)
        assertNotNull(WifiLinkProvisioning.answer(ack(0, "x".repeat(32), "p".repeat(63)))!!.network)
        assertNotNull("a raw key", WifiLinkProvisioning.answer(ack(0, "CarLink-0000", "0123456789abcdefABCDEF".padEnd(64, '0')))!!.network)
        assertNotNull(WifiLinkProvisioning.answer(ack(0, "Café", "example-pass"))!!.network)
        val broken =
            listOf(
                ack(0, "", "example-pass"), // no name
                ack(0, "x".repeat(33), "example-pass"), // too long a name
                ack(0, "Car\nLink", "example-pass"), // a line break
                ack(0, "CarLink-0000", "short"), // 5 characters
                ack(0, "CarLink-0000", "p".repeat(65)),
                ack(0, "CarLink-0000", "g".repeat(64)), // 64, not hex
                ack(0, "CarLink-0000", "example\u0001pass"), // not printable
                ack(0, "CarLink-0000", "example-pass") + byteArrayOf(0), // a byte past the end
                ack(0, "CarLink-0000", "example-pass").dropLast(1).toByteArray(), // one short
                byteArrayOf(Ocbm.MGMT_ACK, Ocbm.MGMT_WIFI_LINK, 0, 2, 0xC3.toByte(), 0x28, 8) + "password".toByteArray(), // not UTF-8
            )
        for (b in broken) {
            val a = WifiLinkProvisioning.answer(b)!!
            assertEquals(0, a.status)
            assertNull(b.joinToString(" ") { "%02x".format(it) }, a.network)
        }
    }

    private fun ack(
        status: Int,
        name: String,
        password: String,
    ): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        val p = password.toByteArray(Charsets.ISO_8859_1)
        return byteArrayOf(Ocbm.MGMT_ACK, Ocbm.MGMT_WIFI_LINK, status.toByte(), n.size.toByte()) + n + byteArrayOf(p.size.toByte()) + p
    }
}
