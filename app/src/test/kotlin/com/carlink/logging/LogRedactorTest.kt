package com.carlink.logging

import com.carlink.callui.CallState
import org.junit.Assert.assertEquals
import org.junit.Test

/** What leaves the head unit in a drive log or Save logs (security review M4). */
class LogRedactorTest {
    @Test
    fun theBoxIdentityLineLosesTheNameAndMostOfTheMac() {
        val box = """[ocbmd] phone identity -> host: {"name":"Scott’s iPhone","deviceID":"68:a7:29:00:25:10","model":"iPhone18,1"}"""
        assertEquals(
            """[ocbmd] phone identity -> host: {"name":"<phone>","deviceID":"..25:10","model":"iPhone18,1"}""",
            LogRedactor.line(box),
        )
    }

    @Test
    fun macsKeepTheirLastTwoOctetsInAnyCase() {
        assertEquals("policy order=[..29:69, ..EE:FF]", LogRedactor.line("policy order=[64:31:35:8C:29:69, AA:BB:CC:DD:EE:FF]"))
        assertEquals("peer ..0a:0b connected", LogRedactor.line("peer 00-01-02-03-0a-0b connected"))
    }

    @Test
    fun fingerprintsAndOrdinaryLinesAreLeftAlone() {
        val cert = "[APP] signing certificate SHA-256 C3:FC:31:9F:65:9D:C5:C0:38:0F:06:B4:48:93:49:C1:1C:27:AF:44:27:40:9D:B3:61:13:5C:64:EB:E5:61:D5"
        assertEquals(cert, LogRedactor.line(cert))
        val time = "10-21 09:47:41.657 I [NAVI] [LANES] rx block=0 lane=0 status=0"
        assertEquals(time, LogRedactor.line(time))
        assertEquals("""{"name":"carlink"}""", LogRedactor.line("""{"name":"carlink"}"""))
    }

    @Test
    fun wholeFilesAreRedactedLineByLine() {
        val file = "a 68:a7:29:00:25:10\nb\n".toByteArray()
        assertEquals("a ..25:10\nb\n", String(LogRedactor.text(file)))
    }

    @Test
    fun callersAreNeverLoggedByNameOrFullNumber() {
        fun call(
            name: String?,
            number: String?,
        ) = CallState(status = 2, direction = 1, displayName = name, remoteId = number, callUuid = null, label = null, startTimestamp = null)
        assertEquals("<contact>", call("Mom", "+15551234567").logLabel)
        assertEquals("<number …4567>", call(null, "+15551234567").logLabel)
        assertEquals("Unknown caller", call(null, null).logLabel)
    }
}
