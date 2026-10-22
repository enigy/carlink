package com.carlink.navigation

import com.carlink.navigation.TurnAlert.Phase
import com.carlink.ui.settings.AlertTiming
import com.carlink.ui.settings.TurnAlertConfig
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TurnAlertTest {
    private val on = TurnAlertConfig(enabled = true)

    @Before
    fun setUp() = TurnAlert.reset()

    private fun state(
        meters: Int,
        type: Int = 2,
        road: String = "Main St",
    ) = NavigationState(status = 1, maneuverType = type, roadName = road, remainDistance = meters)

    /**
     * One update a second of a car at [mph] that was [fromM] meters out at second 0, for seconds
     * [fromS]..[toS]; returns the time of the last update.
     */
    private fun drive(
        mph: Double,
        fromM: Double,
        fromS: Int,
        toS: Int,
        type: Int = 2,
    ): Long {
        val mps = mph * TurnAlert.MPS_PER_MPH
        for (s in fromS..toS) TurnAlert.observe(state((fromM - mps * s).toInt(), type), s * 1000L)
        return toS * 1000L
    }

    @Test
    fun `warning time is a straight line between the 25 and 60 mph settings, held outside them`() {
        assertEquals(36.0, TurnAlert.leadSeconds(10.0, AlertTiming.STANDARD), 1e-9)
        assertEquals(36.0, TurnAlert.leadSeconds(25.0, AlertTiming.STANDARD), 1e-9)
        assertEquals(48.0, TurnAlert.leadSeconds(42.5, AlertTiming.STANDARD), 1e-9)
        assertEquals(60.0, TurnAlert.leadSeconds(60.0, AlertTiming.STANDARD), 1e-9)
        assertEquals(60.0, TurnAlert.leadSeconds(80.0, AlertTiming.STANDARD), 1e-9)
        assertEquals(72.0, TurnAlert.leadSeconds(25.0, AlertTiming.EARLIER), 1e-9)
        assertEquals(120.0, TurnAlert.leadSeconds(60.0, AlertTiming.EARLIER), 1e-9)
        assertEquals(14.4, TurnAlert.leadSeconds(25.0, AlertTiming.LATER), 1e-9)
        assertEquals(30.0, TurnAlert.leadSeconds(60.0, AlertTiming.LATER), 1e-9)
    }

    @Test
    fun `each timing starts at the owner's distances at 25 and 60 mph`() {
        val mi = TurnAlert.METERS_PER_MILE
        val expected =
            mapOf(
                AlertTiming.EARLIER to (0.5 to 2.0),
                AlertTiming.STANDARD to (0.25 to 1.0),
                AlertTiming.LATER to (0.1 to 0.5),
            )
        for ((timing, miles) in expected) {
            assertEquals(miles.first, TurnAlert.startMeters(25 * TurnAlert.MPS_PER_MPH, timing) / mi, 0.001)
            assertEquals(miles.second, TurnAlert.startMeters(60 * TurnAlert.MPS_PER_MPH, timing) / mi, 0.001)
        }
    }

    @Test
    fun `normal outside the zone, flashing inside, solid for the last 3 s`() {
        // Standard, 60 mph = 26.8 m/s from 2000 m out: the alert starts 1 mi (1609 m) out, the solid frame 80 m out.
        var t = drive(60.0, fromM = 2000.0, fromS = 0, toS = 7) // 1812 m
        assertEquals(Phase.NORMAL, TurnAlert.phase(on, t))
        t = drive(60.0, fromM = 2000.0, fromS = 8, toS = 22) // 1410 m
        assertEquals(Phase.FLASH, TurnAlert.phase(on, t))
        t = drive(60.0, fromM = 2000.0, fromS = 23, toS = 72) // 69 m, 2.6 s
        assertEquals(Phase.SOLID, TurnAlert.phase(on, t))
    }

    @Test
    fun `without the solid option the last 3 s keep flashing`() {
        val t = drive(60.0, fromM = 2000.0, fromS = 0, toS = 72)
        assertEquals(Phase.FLASH, TurnAlert.phase(on.copy(solidLast = false), t))
    }

    @Test
    fun `the distance is carried forward between updates`() {
        // Last update 1669 m out at ~60 mph (the alert starts ~1620 m out): three seconds later the
        // car is ~80 m closer, inside the zone, with no update in between.
        val t = drive(60.0, fromM = 1750.0, fromS = 0, toS = 3)
        assertEquals(Phase.NORMAL, TurnAlert.phase(on, t))
        assertEquals(Phase.FLASH, TurnAlert.phase(on, t + 3_000))
    }

    @Test
    fun `stopped inside the zone does not flash`() {
        var t = 0L
        repeat(6) { s ->
            t = s * 1000L
            TurnAlert.observe(state(200), t)
        }
        assertEquals(Phase.NORMAL, TurnAlert.phase(on, t))
    }

    @Test
    fun `a new step starts over until its speed is known`() {
        val t = drive(60.0, fromM = 700.0, fromS = 0, toS = 3)
        assertEquals(Phase.FLASH, TurnAlert.phase(on, t))
        TurnAlert.observe(state(600, type = 1, road = "Oak Ave"), t + 1000)
        assertEquals(Phase.NORMAL, TurnAlert.phase(on, t + 1000))
    }

    @Test
    fun `arrival and no-turn steps never flash, and nothing flashes when off`() {
        var t = drive(60.0, fromM = 700.0, fromS = 0, toS = 3, type = 12)
        assertEquals(Phase.NORMAL, TurnAlert.phase(on, t))
        TurnAlert.reset()
        t = drive(60.0, fromM = 700.0, fromS = 0, toS = 3, type = 0)
        assertEquals(Phase.NORMAL, TurnAlert.phase(on, t))
        TurnAlert.reset()
        t = drive(60.0, fromM = 700.0, fromS = 0, toS = 3)
        assertEquals(Phase.NORMAL, TurnAlert.phase(TurnAlertConfig(enabled = false), t))
    }

    @Test
    fun `straight arrows never flash`() {
        for (type in listOf(3, 5)) {
            TurnAlert.reset()
            val t = drive(60.0, fromM = 700.0, fromS = 0, toS = 3, type = type)
            assertEquals("cpType $type", Phase.NORMAL, TurnAlert.phase(on, t))
        }
        TurnAlert.reset()
        val t = drive(60.0, fromM = 700.0, fromS = 0, toS = 3, type = 49) // slight left still flashes
        assertEquals(Phase.FLASH, TurnAlert.phase(on, t))
    }
}
