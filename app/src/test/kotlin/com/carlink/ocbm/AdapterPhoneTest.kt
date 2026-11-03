package com.carlink.ocbm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The phone Settings marks Connected follows the adapter, and nothing keeps the last one (11-03 bench: a switch). */
class AdapterPhoneTest {
    private val scott = "68:a7:29:00:25:10"
    private val yoda = "aa:bb:cc:00:6a:de"

    @Test
    fun `an identity names the phone, and a cleared one leaves none connected`() {
        val phone = AdapterPhone()
        phone.identity(scott)
        assertEquals(scott, phone.shown)
        assertEquals(LivePhone(scott, 1), phone.live)
        phone.identity(null) // the adapter cleared CT_PHONE_IDENT: Scott's session ended
        assertNull("the old phone is no longer Connected", phone.shown)
        assertEquals(LivePhone(null, 1), phone.live)
        phone.identity(yoda)
        assertEquals(yoda, phone.shown)
        assertEquals(2L, phone.identities)
    }

    @Test
    fun `no phone on the adapter leaves none connected`() {
        val phone = AdapterPhone()
        phone.identity(scott)
        phone.absent()
        assertNull(phone.shown)
        assertNull(phone.identified)
    }

    @Test
    fun `adapter info keeps the identified phone, else guesses only from one bonded phone`() {
        val phone = AdapterPhone()
        phone.info(phoneOn = true, bonded = setOf(scott))
        assertEquals("one bonded phone and a phone on the adapter: that one", scott, phone.shown)
        phone.clearShown()
        phone.info(phoneOn = true, bonded = setOf(scott, yoda))
        assertNull("two bonded and no identity: unknown", phone.shown)
        phone.identity(yoda)
        phone.info(phoneOn = true, bonded = setOf(scott))
        assertEquals("the identity wins over the guess", yoda, phone.shown)
    }

    @Test
    fun `adapter info after a cleared identity does not bring the old phone back`() {
        val phone = AdapterPhone()
        phone.identity(scott)
        phone.identity(null)
        phone.info(phoneOn = true, bonded = setOf(scott, yoda))
        assertNull(phone.shown)
    }
}
