package com.carlink.bench

import com.carlink.CarlinkManager.State
import com.carlink.DisplayOff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bench receiver's guards (debug builds only; the Mac's 14 Nov review, D3). */
class BenchGuardsTest {
    @Test
    fun only_bench_flags_are_written() {
        assertTrue(BenchGuards.isFlag("bench_phantom_first"))
        assertTrue(BenchGuards.isFlag("bench_sdp_after_acl_ms"))
        for (bad in listOf("host_present", "bench_", "../etc/passwd", "bench_x/../y", "Bench_order", "bench order")) {
            assertFalse(bad, BenchGuards.isFlag(bad))
        }
    }

    @Test
    fun an_order_is_whole_addresses_each_once() {
        assertEquals(listOf("aa:bb:cc:dd:ee:ff", "11:22:33:44:55:66"), BenchGuards.order("AA:BB:CC:DD:EE:FF, 11:22:33:44:55:66"))
        assertNull("empty", BenchGuards.order(" , "))
        assertNull("not an address", BenchGuards.order("aa:bb:cc:dd:ee"))
        assertNull("twice", BenchGuards.order("aa:bb:cc:dd:ee:ff,AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun box_log_names_its_three_files() {
        assertEquals("/tmp/wl.log", BenchGuards.logPath("wl"))
        assertEquals("/tmp/carplayd_wl.log", BenchGuards.logPath("carplayd_wl"))
        assertEquals("/tmp/box.log", BenchGuards.logPath("box"))
        assertNull(BenchGuards.logPath("/etc/shadow"))
        assertTrue(BenchGuards.MAX_LINES < 4000)
    }

    @Test
    fun the_wait_has_a_floor_and_0_puts_back_30_s() {
        assertEquals(DisplayOff.GRACE_MS, BenchGuards.graceMs(0))
        assertEquals(BenchGuards.MIN_GRACE_MS, BenchGuards.graceMs(5))
        assertEquals(2_000L, BenchGuards.graceMs(2_000))
    }

    @Test
    fun a_flicker_runs_only_from_dormant() {
        assertTrue(BenchGuards.flickerAllowed(State.DISCONNECTED, dormant = true))
        assertFalse("streaming: it would only blip the session", BenchGuards.flickerAllowed(State.STREAMING, dormant = false))
        assertFalse("disconnected, not after the wait", BenchGuards.flickerAllowed(State.DISCONNECTED, dormant = false))
    }

    @Test
    fun wifi_addr_takes_an_IPv4_address_and_a_port_only() {
        val at = BenchGuards.wifiAddress("192.0.2.10", 7020)!!
        assertEquals("192.0.2.10", at.hostString)
        assertEquals(7020, at.port)
        assertNull("a name is never looked up", BenchGuards.wifiAddress("adapter.local", 7020))
        assertNull(BenchGuards.wifiAddress("192.0.2.256", 7020))
        assertNull(BenchGuards.wifiAddress("192.0.2", 7020))
        assertNull(BenchGuards.wifiAddress("192.0.2.10", 0))
        assertNull(BenchGuards.wifiAddress("192.0.2.10", 70000))
    }
}
