package com.carlink.callui

import com.carlink.ocbm.Ocbm
import org.junit.Assert.assertEquals
import org.junit.Test

class DialPadTest {
    @Test
    fun `digits map onto TEL_DIGIT0 and up, star and pound to their own indices`() {
        assertEquals(Ocbm.TEL_DIGIT0, DialPad.index('0'))
        assertEquals((Ocbm.TEL_DIGIT0 + 9).toByte(), DialPad.index('9'))
        assertEquals(Ocbm.TEL_STAR, DialPad.index('*'))
        assertEquals(Ocbm.TEL_POUND, DialPad.index('#'))
    }

    @Test
    fun `the pad has the twelve keys in phone order`() {
        assertEquals("123456789*0#", DialPad.KEYS.joinToString(""))
        // Every key has an index, and no digit collides with star or pound.
        val indices = DialPad.KEYS.map { DialPad.index(it) }
        assertEquals(12, indices.toSet().size)
    }
}
