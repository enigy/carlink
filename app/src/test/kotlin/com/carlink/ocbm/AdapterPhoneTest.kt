package com.carlink.ocbm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The phone Settings marks Connected follows the adapter, and nothing keeps the last one (11-03 bench: a switch). */
class AdapterPhoneTest {
    private val phoneA = "0a:00:00:00:25:10"
    private val phoneB = "aa:bb:cc:00:6a:de"

    @Test
    fun `an identity names the phone, and a cleared one leaves none connected`() {
        val phone = AdapterPhone()
        phone.identity(phoneA)
        assertEquals(phoneA, phone.shown)
        assertEquals(LivePhone(phoneA, 1), phone.live)
        phone.identity(null) // the adapter cleared CT_PHONE_IDENT: phone A's session ended
        assertNull("the old phone is no longer Connected", phone.shown)
        assertEquals(LivePhone(null, 1), phone.live)
        phone.identity(phoneB)
        assertEquals(phoneB, phone.shown)
        assertEquals(2L, phone.identities)
    }

    @Test
    fun `no phone on the adapter leaves none connected`() {
        val phone = AdapterPhone()
        phone.identity(phoneA)
        phone.absent()
        assertNull(phone.shown)
        assertNull(phone.identified)
    }

    @Test
    fun `adapter info keeps the identified phone, else guesses only from one bonded phone`() {
        val phone = AdapterPhone()
        phone.info(phoneOn = true, bonded = setOf(phoneA))
        assertEquals("one bonded phone and a phone on the adapter: that one", phoneA, phone.shown)
        phone.clearShown()
        phone.info(phoneOn = true, bonded = setOf(phoneA, phoneB))
        assertNull("two bonded and no identity: unknown", phone.shown)
        phone.identity(phoneB)
        phone.info(phoneOn = true, bonded = setOf(phoneA))
        assertEquals("the identity wins over the guess", phoneB, phone.shown)
    }

    @Test
    fun `adapter info after a cleared identity does not bring the old phone back`() {
        val phone = AdapterPhone()
        phone.identity(phoneA)
        phone.identity(null)
        phone.info(phoneOn = true, bonded = setOf(phoneA, phoneB))
        assertNull(phone.shown)
    }
}
