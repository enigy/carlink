package com.carlink.ocbm

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** MGMT_WIFI_LINK's body, laid out as ocbmd parses it, and the WPA key pinned by IEEE 802.11i's test vectors. */
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
        assertNotNull(WifiLinkProvisioning.problem("MyCar", "short"))
        assertNotNull(WifiLinkProvisioning.problem("MyCar", "x".repeat(64)))
        assertNull(WifiLinkProvisioning.problem("MyCar", "x".repeat(63)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a body is never built for a refused password`() {
        WifiLinkProvisioning.body(key, "MyCar", "short")
    }
}
