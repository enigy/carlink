package com.carlink.ui.settings

import com.carlink.ocbm.ConnectAttempt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a phone's Connect works (owner, 11-01: a second iPhone connected and the owner's couldn't get in), and which rank badge is lit. */
class PhonePriorityTest {
    private fun ranking(
        phoneConnected: Boolean,
        switchable: Boolean = false,
    ) = PhoneRanking(enabled = true, connectedMac = null, phoneConnected = phoneConnected, switchable = switchable)

    @Test
    fun `with no phone projecting any bonded phone can be called`() {
        assertTrue(connectEnabled(bonded = true, live = false, ranking(phoneConnected = false)))
        assertFalse("not bonded: nothing to page", connectEnabled(bonded = false, live = false, ranking(phoneConnected = false)))
    }

    @Test
    fun `another phone projecting blocks Connect unless the box can switch from it`() {
        assertFalse("a box without the switch never ends a session", connectEnabled(bonded = true, live = false, ranking(phoneConnected = true)))
        assertTrue("over Wi-Fi, on a box that switches", connectEnabled(bonded = true, live = false, ranking(phoneConnected = true, switchable = true)))
        assertFalse("the live phone itself", connectEnabled(bonded = true, live = true, ranking(phoneConnected = true, switchable = true)))
    }

    @Test
    fun `while a press works no other Connect does`() {
        val working = PhoneRanking(enabled = true, connectedMac = null, phoneConnected = false, attempt = ConnectAttempt("68:a7:29:00:25:10"))
        assertFalse(connectEnabled(bonded = true, live = false, working))
        val done = PhoneRanking(true, null, false, attempt = ConnectAttempt("68:a7:29:00:25:10", ConnectAttempt.Outcome.NOT_REACHED))
        assertTrue("an outcome is not working", connectEnabled(bonded = true, live = false, done))
    }

    @Test
    fun `while a press for another phone works or has connected it, the old phone's row is not live`() {
        val scott = "68:a7:29:00:25:10"
        val yoda = "aa:bb:cc:00:6a:de"

        fun on(attempt: ConnectAttempt?) = PhoneRanking(true, scott, phoneConnected = true, switchable = true, attempt = attempt)
        assertTrue("the phone the adapter names", on(null).isLive(scott))
        assertFalse(on(null).isLive(yoda))
        assertFalse("a switch to Yoda's is working (11-03 bench)", on(ConnectAttempt(yoda)).isLive(scott))
        assertFalse("Yoda's has connected", on(ConnectAttempt(yoda, ConnectAttempt.Outcome.CONNECTED)).isLive(scott))
        assertTrue("Yoda's was not reached", on(ConnectAttempt(yoda, ConnectAttempt.Outcome.NOT_REACHED)).isLive(scott))
    }

    @Test
    fun `the lit rank badge is the phone connected now, else the one called first`() {
        val idle = ranking(phoneConnected = false)
        assertTrue("nothing connected: the phone called first", idle.badgeLit(index = 0, live = false, mac = A))
        assertFalse(idle.badgeLit(index = 1, live = false, mac = B))
        val busy = ranking(phoneConnected = true)
        assertTrue("the first phone connected", busy.badgeLit(index = 0, live = true, mac = A))
        assertTrue("the second phone connected (owner, 11-03)", busy.badgeLit(index = 1, live = true, mac = B))
        assertFalse("the first phone while the second is connected", busy.badgeLit(index = 0, live = false, mac = A))
    }

    @Test
    fun `while a press works or has connected, the accent stays on the phone pressed`() {
        // Bench 11-03: a switch to the first-called phone lit, unlit and lit its badge again as the phones changed.
        fun on(attempt: ConnectAttempt) = PhoneRanking(true, null, phoneConnected = true, switchable = true, attempt = attempt)
        val working = on(ConnectAttempt(A))
        assertTrue(working.badgeLit(index = 0, live = false, mac = A.uppercase()))
        assertFalse("the phone leaving", working.badgeLit(index = 1, live = true, mac = B))
        assertTrue(on(ConnectAttempt(A, ConnectAttempt.Outcome.CONNECTED)).badgeLit(index = 0, live = false, mac = A))
        val missed = on(ConnectAttempt(A, ConnectAttempt.Outcome.NOT_REACHED))
        assertTrue("not reached: the phone connected now again", missed.badgeLit(index = 1, live = true, mac = B))
    }

    private companion object {
        const val A = "68:a7:29:00:25:10"
        const val B = "aa:bb:cc:00:6a:de"
    }
}
