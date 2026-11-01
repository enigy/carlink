package com.carlink.ui.settings

import com.carlink.ocbm.ConnectAttempt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a phone's Connect works (owner, 11-01: a second iPhone connected and the owner's couldn't get in). */
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
}
