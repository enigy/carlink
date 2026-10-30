package com.carlink.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Phone priority: what is stored, the order on screen, and what the box is told. */
class PhonePriorityTest {
    private fun dev(
        mac: String,
        last: Long?,
    ) = KnownDevice(mac = mac, name = mac.uppercase(), firstSeenMs = 1L, lastConnectedMs = last)

    @Test
    fun prioritySurvivesTheCodec() {
        val snap = KnownDeviceSnapshot(devices = mapOf("aa" to dev("aa", 5L)), priority = listOf("bb", "aa"))
        val back = KnownDeviceCodec.decode(KnownDeviceCodec.encode(snap))
        assertEquals(listOf("bb", "aa"), back.priority)
    }

    @Test
    fun anOlderDocumentWithAutoConnectOffStillReads() {
        val old = """{"v":1,"priority":["bb","aa"],"autoConnect":false,"devices":[{"mac":"aa","firstSeenMs":1}]}"""
        val back = KnownDeviceCodec.decode(old)
        assertEquals(listOf("bb", "aa"), back.priority)
        assertEquals(setOf("aa"), back.devices.keys)
        assertFalse("written back without it", "autoConnect" in KnownDeviceCodec.encode(back))
    }

    @Test
    fun defaultsAddNothingToTheDocument() {
        val doc = KnownDeviceCodec.encode(KnownDeviceSnapshot())
        assertEquals("""{"v":1,"devices":[]}""", doc)
    }

    @Test
    fun explicitOrderWinsThenRecency() {
        val known = mapOf("aa" to dev("aa", 300L), "bb" to dev("bb", 100L), "cc" to dev("cc", 200L))
        val list = mergeDeviceList(known, setOf("aa", "bb", "cc"), emptySet(), order = listOf("bb")) { "t" }
        assertEquals(listOf("bb", "aa", "cc"), list.map { it.btMac })
    }

    @Test
    fun singlePreferredPhoneStillCountsAsTheOrder() {
        assertEquals(listOf("cc"), KnownDeviceSnapshot(preferredMac = "cc").order)
        assertEquals(listOf("bb", "aa"), KnownDeviceSnapshot(preferredMac = "cc", priority = listOf("bb", "aa")).order)
    }

    @Test
    fun policyBodyIsWhatBtdParses() {
        assertEquals(
            """{"autoConnect":true,"order":["AA:BB:CC:DD:EE:01","AA:BB:CC:DD:EE:02"]}""",
            phonePolicyJson(listOf("aa:bb:cc:dd:ee:01", "aa:bb:cc:dd:ee:02")),
        )
        assertEquals("auto-connect is always on", """{"autoConnect":true,"order":[]}""", phonePolicyJson(emptyList()))
    }
}
