package com.carlink.audio

import com.carlink.audio.MicRecovery.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class MicRecoveryTest {
    @Test
    fun `a capture that dies on a named input moves the call to the system choice`() {
        assertEquals(Action.SYSTEM_CHOICE, MicRecovery.after(gateOn = true, onNamedInput = true, restarts = 0))
        assertEquals("even after a restart", Action.SYSTEM_CHOICE, MicRecovery.after(gateOn = true, onNamedInput = true, restarts = 1))
    }

    @Test
    fun `the system choice opens again once, then stays off`() {
        assertEquals(Action.RESTART, MicRecovery.after(gateOn = true, onNamedInput = false, restarts = 0))
        assertEquals(Action.NOTHING, MicRecovery.after(gateOn = true, onNamedInput = false, restarts = MicRecovery.MAX_RESTARTS))
    }

    @Test
    fun `nothing restarts once the box closed the gate`() {
        assertEquals(Action.NOTHING, MicRecovery.after(gateOn = false, onNamedInput = true, restarts = 0))
        assertEquals(Action.NOTHING, MicRecovery.after(gateOn = false, onNamedInput = false, restarts = 0))
    }

    /** Deaths in a row within one gate: at most two restarts, then the mic stays off rather than looping. */
    @Test
    fun `repeated deaths end after the system choice and one restart`() {
        var restarts = 0
        var onNamedInput = true
        val steps = mutableListOf<Action>()
        repeat(3) {
            val action = MicRecovery.after(gateOn = true, onNamedInput = onNamedInput, restarts = restarts)
            steps += action
            when (action) {
                Action.SYSTEM_CHOICE -> onNamedInput = false
                Action.RESTART -> restarts++
                Action.NOTHING -> Unit
            }
        }
        assertEquals("named input, then one restart, then off", listOf(Action.SYSTEM_CHOICE, Action.RESTART, Action.NOTHING), steps)
    }
}
