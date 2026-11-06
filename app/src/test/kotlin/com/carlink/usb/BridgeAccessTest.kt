package com.carlink.usb

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bridge API 6 as Carlink reads it: getAccess's document, Settings' words and rules, and the handover's order. */
class BridgeAccessTest {
    @Test
    fun `getAccess's document reads in full`() {
        val access = BridgeAccess.parse(DOCUMENT, apiVersion = 6, bridgeVersion = "1.0.8")
        assertTrue(access.supported)
        assertTrue(access.on)
        assertTrue(access.carlinkVerified)
        val app = access.approved.single()
        assertEquals("com.example.probe", app.packageName)
        assertEquals("Probe", app.label)
        assertEquals("com.android.vending", app.installer)
        assertEquals(3, app.sessions)
        val request = access.requests.single()
        assertNull(request.installer)
        assertEquals(2, request.count)
        assertTrue(request.keyChanged)
        assertEquals("com.example.probe", access.handedTo?.packageName)
        assertEquals(1_700_000_000_000, access.handedTo?.since)
        assertEquals("Probe", access.handedToLabel)
        assertEquals("com.example.probe", access.holder)
    }

    @Test
    fun `an unreadable document or an older bridge reads as off, with nothing handed over`() {
        val broken = BridgeAccess.parse("{not json", apiVersion = 6, bridgeVersion = "1.0.8")
        assertFalse(broken.on)
        assertNull(broken.handedTo)
        val old = BridgeAccess(apiVersion = 5, bridgeVersion = "1.0.7")
        assertFalse(old.supported)
        assertNull(AdapterHandover.connectBlockedBy(old))
        val none = BridgeAccess.parse("""{"v":1,"on":false,"approved":[],"requests":[],"handedTo":null,"holder":null}""", 6, "1.0.8")
        assertNull(none.holder)
        assertNull(none.handedTo)
    }

    @Test
    fun `the version comes off the bridge's status line`() {
        assertEquals("1.0.8", BridgeAccess.versionFromStatus("bridge api=6 v=1.0.8 user=0 handler=x devices=[]"))
        assertNull(BridgeAccess.versionFromStatus(null))
    }

    @Test
    fun `the switch when off, on, with an older bridge, and driving`() {
        val off = BridgeAccess(apiVersion = 6)
        assertEquals(BridgeAccessText.SwitchState(false, BridgeAccessText.SWITCH_OFF, enabled = true), BridgeAccessText.switchState(off, driving = false))
        val drivingOff = BridgeAccessText.switchState(off, driving = true)
        assertFalse("turning on is for parked", drivingOff.enabled)
        assertTrue(drivingOff.subtitle.startsWith(BridgeAccessText.SWITCH_OFF))
        val on = BridgeAccess(apiVersion = 6, on = true)
        assertEquals(BridgeAccessText.SwitchState(true, BridgeAccessText.SWITCH_ON, enabled = true), BridgeAccessText.switchState(on, driving = true))
        assertEquals(
            BridgeAccessText.SwitchState(false, BridgeAccessText.SWITCH_OLD_BRIDGE, enabled = false),
            BridgeAccessText.switchState(BridgeAccess(apiVersion = 5), driving = false),
        )
    }

    @Test
    fun `the pane's summary, holder line and certificate`() {
        val access = BridgeAccess.parse(DOCUMENT, 6, "1.0.8")
        assertEquals("1 approved · Probe has the adapter · 1 asking", BridgeAccessText.summary(access))
        assertEquals("Probe since 10:42", BridgeAccessText.holderLine(access) { "10:42" })
        assertEquals("Carlink", BridgeAccessText.holderLine(BridgeAccess(apiVersion = 6, holder = "com.enigy.carlink3")) { "" })
        assertEquals("Nobody", BridgeAccessText.holderLine(BridgeAccess(apiVersion = 6)) { "" })
        assertEquals("0 approved · Carlink has the adapter", BridgeAccessText.summary(BridgeAccess(apiVersion = 6, on = true)))
        assertEquals("11:11:11:11…11:11:11:11", BridgeAccessText.shortCert(KEY))
        assertEquals("Play", BridgeAccessText.installerText("com.android.vending"))
        assertEquals("sideloaded", BridgeAccessText.installerText(null))
    }

    @Test
    fun `while the adapter is handed over Carlink opens nothing, and says who has it`() {
        val access = BridgeAccess.parse(DOCUMENT, 6, "1.0.8")
        assertEquals("Probe is using the adapter", AdapterHandover.connectBlockedBy(access))
        assertNull(AdapterHandover.connectBlockedBy(access.copy(handedTo = null)))
    }

    @Test
    fun `hand over stops Carlink's session first, and a refusal connects again`() =
        runBlocking {
            val steps = ArrayList<String>()
            var refusal: String? = null
            val handover =
                AdapterHandover(
                    stopSession = { steps += "stop" },
                    bridgeHandOver = {
                        steps += "handOver $it"
                        refusal
                    },
                    bridgeTakeBack = {
                        steps += "takeBack"
                        null
                    },
                    connect = { steps += "connect" },
                )
            assertNull(handover.handOver("com.example.probe"))
            assertEquals(listOf("stop", "handOver com.example.probe"), steps)

            steps.clear()
            refusal = "Carlink's session on /dev/bus/usb/001/002 is still open"
            assertEquals(refusal, handover.handOver("com.example.probe"))
            assertEquals(listOf("stop", "handOver com.example.probe", "connect"), steps)

            steps.clear()
            assertNull(handover.takeBack())
            assertEquals(listOf("takeBack", "connect"), steps)
        }

    @Test
    fun `take back and a refused hand over answer at once, while the connect they start is still running`() =
        runBlocking {
            val manager = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val connects = ArrayList<Job>()
            val connect: () -> Unit = { connects += manager.launch { awaitCancellation() } }
            try {
                val refused = AdapterHandover({}, { "Carlink's session is still open" }, { null }, connect)
                assertEquals("Carlink's session is still open", withTimeout(ANSWER_MS) { refused.handOver("com.example.probe") })
                assertNull(withTimeout(ANSWER_MS) { refused.takeBack() })
                assertEquals(2, connects.size)
                assertTrue("the connects run on, apart from the caller", connects.all { it.isActive })
            } finally {
                manager.cancel()
            }
        }

    @Test
    fun `a refused take-back doesn't connect`() =
        runBlocking {
            var connected = false
            val handover = AdapterHandover({}, { null }, { "the USB bridge isn't responding" }, { connected = true })
            assertEquals("the USB bridge isn't responding", handover.takeBack())
            assertFalse(connected)
        }

    private companion object {
        const val ANSWER_MS = 2_000L
        val KEY = List(32) { "11" }.joinToString(":")
        val DOCUMENT =
            """
            {"v":1,"on":true,"carlinkVerified":true,
             "approved":[{"package":"com.example.probe","label":"Probe","sha256":"$KEY","installer":"com.android.vending",
                          "approvedAt":1699999990000,"lastUsedAt":1700000000000,"sessions":3}],
             "requests":[{"package":"com.example.other","label":"Other","sha256":"$KEY","installer":null,
                          "firstAt":1699999000000,"lastAt":1699999500000,"count":2,"keyChanged":true}],
             "handedTo":{"package":"com.example.probe","since":1700000000000},"holder":"com.example.probe"}
            """.trimIndent()
    }
}
