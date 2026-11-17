package com.carlink.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The adapter's two lines the ledger keeps: its `[startup]` figures, read from the forms OCBM prints, and its `[timeline]`
 * line, kept verbatim only in the v1 form. The startup lines are the shapes enigy/ocbm's receiver `startup.rs` prints
 * (2.6.0, e472eb1, and the pre-release bench build before it), with made-up figures.
 */
class AdapterLinesTest {
    private val released =
        "[startup] host present +0 ms (adapter up 29.7 s, preload done), Bluetooth up +1314 ms, phone connected +2300 ms, " +
            "Wi-Fi handoff +4476 ms, first frame +11602 ms; since the host came: absent pages 2, resets 1 (1 cut, no SDP channel), " +
            "PSK mismatches 3; this session: nudges 1+0"

    private val preRelease =
        "[startup] host present +0 ms (adapter up 644.4 s, preload done), Bluetooth up +1166 ms, phone connected +22028 ms, " +
            "Wi-Fi handoff +23939 ms, first frame +27523 ms; this session: absent pages 0, resets 6 (6 cut, no SDP channel), " +
            "nudges 0+2, PSK mismatches 0"

    @Test
    fun `the released startup line gives the uptime, the preload and every count`() {
        assertEquals(
            AdapterStartup(
                uptimeMs = 29_700,
                preload = "done",
                absentPages = 2,
                resets = 1,
                firstNudges = 1,
                secondNudges = 0,
                pskMismatches = 3,
                pskAtLeast = false,
            ),
            AdapterStartup.parse(released),
        )
    }

    @Test
    fun `the pre-release startup line, with every count under this session, reads the same`() {
        assertEquals(
            AdapterStartup(uptimeMs = 644_400, preload = "done", absentPages = 0, resets = 6, firstNudges = 0, secondNudges = 2, pskMismatches = 0),
            AdapterStartup.parse(preRelease),
        )
    }

    @Test
    fun `a startup line without the counts or the uptime keeps what it has`() {
        val bare = "[startup] host present +0 ms (preload running), Bluetooth up -, phone connected -, Wi-Fi handoff +4000 ms, first frame +8000 ms"
        assertEquals(AdapterStartup(preload = "running"), AdapterStartup.parse(bare))
        val none = "[startup] host present +0 ms, Bluetooth up +10900 ms, phone connected +19200 ms, Wi-Fi handoff +21800 ms, first frame +26100 ms"
        assertEquals(AdapterStartup(), AdapterStartup.parse(none))
    }

    @Test
    fun `PSK mismatches the adapter couldn't count stay unknown, and a cut log's count is a lower bound`() {
        val unknown = AdapterStartup.parse(released.replace("PSK mismatches 3", "PSK mismatches -"))!!
        assertNull(unknown.pskMismatches)
        assertFalse(unknown.pskAtLeast)
        val cut = AdapterStartup.parse(released.replace("PSK mismatches 3", "PSK mismatches >=4 (box.log cut)"))!!
        assertEquals(4, cut.pskMismatches)
        assertTrue(cut.pskAtLeast)
    }

    @Test
    fun `only a short lower-case preload word is kept`() {
        assertEquals("off_guard", AdapterStartup.parse(released.replace("preload done", "preload off_guard"))?.preload)
        assertNull("not a lower-case word", AdapterStartup.parse(released.replace("preload done", "preload Test iPhone"))?.preload)
        assertNull("too long", AdapterStartup.parse(released.replace("preload done", "preload " + "a".repeat(25)))?.preload)
    }

    @Test
    fun `other lines are not the startup line`() {
        assertNull(AdapterStartup.parse("[startup] carlink: head unit up 41.2 s at the process start; adapter seen +1770 ms"))
        assertNull(AdapterStartup.parse("[screen] carlink :9001 connected"))
        assertNull(AdapterStartup.parse(" $released"))
    }

    @Test
    fun `a timeline line in the v1 form is kept verbatim, keys unread`() {
        // The shape of the line enigy/ocbm's box_common::timeline prints for 2.6.1 (its own test's, in progress 16 Nov).
        val line =
            "[timeline] v1 sup_radios=406 bt_power=1246 btd_start=1276 page_start=1296 acl=1556 sdp=1716 rfcomm=1866 " +
                "cert_req=1946 sign1_start=2066 sign1_end=2376 ident=2566 wifi_req=2666 wifi_rsp=2696 ap_start=1666 " +
                "ap_up=2366 sta_assoc=- dhcp_ack=- carplayd_start=3166 svc_seen=3866 dial=3916 pv_done=4366 auth_in=4416 " +
                "auth_out=4766 record=5066 screen_setup=5116 first_frame=5466 chip1_ms=298 cert1_ms=41 chip2_ms=301 " +
                "cert2_ms=40 usb_edge_s=11"
        assertEquals(line, AdapterTimeline.accept(line))
        assertEquals(line.replace("first_frame=5466", "first_frame=-"), AdapterTimeline.accept(line.replace("first_frame=5466", "first_frame=-")))
        assertEquals("v1a", "[timeline] v1a some_new_key=7", AdapterTimeline.accept("[timeline] v1a some_new_key=7"))
        assertEquals("v1b", "[timeline] v1b k=-", AdapterTimeline.accept("[timeline] v1b k=-"))
    }

    /** Every key the firmware's lines carry today (27 to 33 of them a line), and the two coming next (the 16 Nov review's list). */
    private val firmwareKeys =
        listOf(
            "sup_radios bt_power btd_start bt_up page_start acl sdp rfcomm cert_req sign1_start sign1_end ident",
            "wifi_req wifi_rsp ap_start ap_up ap_enabled sta_assoc dhcp_ack carplayd_start svc_seen dial pv_done auth_in",
            "auth_out record_in record screen_setup first_frame chip1_ms cert1_ms chip2_ms cert2_ms usb_edge_s bt_armed dial_ok",
        ).flatMap { it.split(' ') }

    @Test
    fun `every key the firmware sends today passes, alone and all together`() {
        assertEquals(36, firmwareKeys.toSet().size)
        for ((i, key) in firmwareKeys.withIndex()) {
            for (value in listOf("0", "-", "${1_000 + i * 97}", "9999999999999")) {
                val line = "[timeline] v1 $key=$value"
                assertEquals(line, AdapterTimeline.accept(line))
            }
        }
        // All 36 in one line, with values like the firmware's (milliseconds from the host's arrival, a dash for a stage not seen).
        val all = "[timeline] v1 " + firmwareKeys.withIndex().joinToString(" ") { (i, k) -> if (i % 9 == 4) "$k=-" else "$k=${406 + i * 151}" }
        assertTrue("${all.length} characters", all.length <= AdapterTimeline.MAX_CHARS)
        assertEquals(all, AdapterTimeline.accept(all))
    }

    @Test
    fun `a key must be a short lower-case word, a value at most 13 digits, and no key may hold an address`() {
        assertEquals("24 characters", "[timeline] v1 ${"k".repeat(24)}=1", AdapterTimeline.accept("[timeline] v1 ${"k".repeat(24)}=1"))
        assertEquals("13 digits", "[timeline] v1 dial=1234567890123", AdapterTimeline.accept("[timeline] v1 dial=1234567890123"))
        assertEquals("11 hex characters in a row", "[timeline] v1 abcdef01234=1", AdapterTimeline.accept("[timeline] v1 abcdef01234=1"))
        val refused =
            listOf(
                "a key of 25 characters" to "[timeline] v1 ${"k".repeat(25)}=1",
                "a value of 14 digits" to "[timeline] v1 dial=12345678901234",
                "a key starting with a digit" to "[timeline] v1 1st_frame=7195",
                "a key starting with an underscore" to "[timeline] v1 _frame=7195",
                "an address as a key" to "[timeline] v1 a4c3f085ac2d=1",
                "an address inside a key" to "[timeline] v1 bt_02000000a0b1=1",
                "a serial inside a key" to "[timeline] v1 dev_0123456789abcdef=1",
                "the last of several keys" to "[timeline] v1 dial=1 first_frame=2 dc0a5f3e9b71=3",
            )
        for ((why, line) in refused) assertNull(why, AdapterTimeline.accept(line))
    }

    @Test
    fun `a timeline line naming a phone, carrying an address or in another form is dropped`() {
        val rejected =
            listOf(
                "a name" to "[timeline] v1 phone=Test iPhone",
                "a name as a word" to "[timeline] v1 phone=test_iphone",
                "an address" to "[timeline] v1 bt=02:00:00:00:0a:0b",
                "an IP address" to "[timeline] v1 ip=192.168.50.2",
                "upper case" to "[timeline] v1 First_Frame=7195",
                "a space inside a value" to "[timeline] v1 first_frame=71 95",
                "a negative value" to "[timeline] v1 first_frame=-5",
                "no pairs" to "[timeline] v1",
                "another version" to "[timeline] v2 first_frame=7195",
                "two spaces" to "[timeline] v1  first_frame=7195",
                "a trailing space" to "[timeline] v1 first_frame=7195 ",
                "a trailing newline" to "[timeline] v1 first_frame=7195\n",
                "a prefix" to "box: [timeline] v1 first_frame=7195",
                "a write-time stamp ocbmd should have stripped" to "@1763300000123 [timeline] v1 first_frame=7195",
                "a decimal" to "[timeline] v1 first_frame=1.5",
                "an empty value" to "[timeline] v1 first_frame=",
                "no key" to "[timeline] v1 =1",
                "a digit that isn't 0-9 (ARABIC-INDIC DIGIT ONE)" to "[timeline] v1 first_frame=7${Char(0x0661)}",
            )
        for ((why, line) in rejected) assertNull(why, AdapterTimeline.accept(line))
    }

    @Test
    fun `a timeline line of 800 characters is kept and one of 801 dropped`() {
        val head = "[timeline] v1"

        fun ofLength(n: Int): String {
            val pairs = StringBuilder(head)
            while (pairs.length + " k=0".length <= n) pairs.append(" k=0")
            // Pad the last value to land exactly on n.
            while (pairs.length < n) pairs.append('0')
            return pairs.toString()
        }
        val eightHundred = ofLength(800)
        assertEquals(800, eightHundred.length)
        assertEquals(eightHundred, AdapterTimeline.accept(eightHundred))
        val eightHundredOne = ofLength(801)
        assertEquals(801, eightHundredOne.length)
        assertTrue("the same form", Regex("\\[timeline] v1( k=[0-9]+)+").matches(eightHundredOne))
        assertNull(AdapterTimeline.accept(eightHundredOne))
    }
}
