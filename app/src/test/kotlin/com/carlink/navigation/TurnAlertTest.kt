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

    private fun feetAt(
        mph: Double,
        timing: AlertTiming = AlertTiming.STANDARD,
    ) = TurnAlert.startMeters(mph * TurnAlert.MPS_PER_MPH, timing) / TurnAlert.METERS_PER_FOOT

    @Test
    fun `standard starts at the owner's distance for each speed band`() {
        assertEquals(500.0, feetAt(10.0), 1e-6)
        assertEquals(500.0, feetAt(35.0), 1e-6)
        assertEquals(1000.0, feetAt(36.0), 1e-6)
        assertEquals(1000.0, feetAt(59.0), 1e-6)
        assertEquals(1500.0, feetAt(60.0), 1e-6)
        assertEquals(1500.0, feetAt(69.0), 1e-6)
        assertEquals(2600.0, feetAt(70.0), 1e-6)
        assertEquals(2600.0, feetAt(85.0), 1e-6)
        // Band edges by the whole mph: 35.4 is still 35, 35.6 is 36.
        assertEquals(500.0, feetAt(35.4), 1e-6)
        assertEquals(1000.0, feetAt(35.6), 1e-6)
    }

    @Test
    fun `earlier is double and later is 0_6 of standard, so they keep their order`() {
        for (mph in listOf(20.0, 45.0, 65.0, 75.0)) {
            val standard = feetAt(mph)
            assertEquals(standard * 2, feetAt(mph, AlertTiming.EARLIER), 1e-6)
            assertEquals(standard * 0.6, feetAt(mph, AlertTiming.LATER), 1e-6)
        }
        assertEquals(
            "Starts 500 ft before a turn up to 35 mph, 1,000 ft to 59, 1,500 ft to 69, 2,600 ft at 70+",
            AlertTiming.STANDARD.hint,
        )
    }

    @Test
    fun `normal outside the zone, flashing inside, solid for the last 3 s`() {
        // Standard, 60 mph = 26.8 m/s from 800 m out: the alert starts 1,500 ft (457 m) out, the solid frame 80 m out.
        var t = drive(60.0, fromM = 800.0, fromS = 0, toS = 7) // 612 m
        assertEquals(Phase.NORMAL, TurnAlert.phase(on, t))
        t = drive(60.0, fromM = 800.0, fromS = 8, toS = 18) // 317 m
        assertEquals(Phase.FLASH, TurnAlert.phase(on, t))
        t = drive(60.0, fromM = 800.0, fromS = 19, toS = 27) // 76 m, 2.8 s
        assertEquals(Phase.SOLID, TurnAlert.phase(on, t))
    }

    @Test
    fun `without the solid option the last 3 s keep flashing`() {
        val t = drive(60.0, fromM = 800.0, fromS = 0, toS = 27)
        assertEquals(Phase.FLASH, TurnAlert.phase(on.copy(solidLast = false), t))
    }

    @Test
    fun `the distance is carried forward between updates`() {
        // Last update 520 m out at ~60 mph (the alert starts 457 m out): three seconds later the
        // car is ~80 m closer, inside the zone, with no update in between.
        val t = drive(60.0, fromM = 600.0, fromS = 0, toS = 3)
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
        val t = drive(60.0, fromM = 500.0, fromS = 0, toS = 3)
        assertEquals(Phase.FLASH, TurnAlert.phase(on, t))
        TurnAlert.observe(state(600, type = 1, road = "Oak Ave"), t + 1000)
        assertEquals(Phase.NORMAL, TurnAlert.phase(on, t + 1000))
    }

    @Test
    fun `arrival and no-turn steps never flash, and nothing flashes when off`() {
        var t = drive(60.0, fromM = 500.0, fromS = 0, toS = 3, type = 12)
        assertEquals(Phase.NORMAL, TurnAlert.phase(on, t))
        TurnAlert.reset()
        t = drive(60.0, fromM = 500.0, fromS = 0, toS = 3, type = 0)
        assertEquals(Phase.NORMAL, TurnAlert.phase(on, t))
        TurnAlert.reset()
        t = drive(60.0, fromM = 500.0, fromS = 0, toS = 3)
        assertEquals(Phase.NORMAL, TurnAlert.phase(TurnAlertConfig(enabled = false), t))
    }

    @Test
    fun `straight arrows never flash`() {
        for (type in listOf(3, 5)) {
            TurnAlert.reset()
            val t = drive(60.0, fromM = 500.0, fromS = 0, toS = 3, type = type)
            assertEquals("cpType $type", Phase.NORMAL, TurnAlert.phase(on, t))
        }
        TurnAlert.reset()
        val t = drive(60.0, fromM = 500.0, fromS = 0, toS = 3, type = 49) // slight left still flashes
        assertEquals(Phase.FLASH, TurnAlert.phase(on, t))
    }
}
