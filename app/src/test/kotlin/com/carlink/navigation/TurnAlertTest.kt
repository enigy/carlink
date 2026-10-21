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
    fun `warning time grows from 15 s at 25 mph to 30 s at 60 mph`() {
        assertEquals(15.0, TurnAlert.leadSeconds(10.0, AlertTiming.STANDARD), 1e-9)
        assertEquals(15.0, TurnAlert.leadSeconds(25.0, AlertTiming.STANDARD), 1e-9)
        assertEquals(22.5, TurnAlert.leadSeconds(42.5, AlertTiming.STANDARD), 1e-9)
        assertEquals(30.0, TurnAlert.leadSeconds(60.0, AlertTiming.STANDARD), 1e-9)
        assertEquals(30.0, TurnAlert.leadSeconds(80.0, AlertTiming.STANDARD), 1e-9)
        assertEquals(39.0, TurnAlert.leadSeconds(60.0, AlertTiming.EARLIER), 1e-9)
        assertEquals(22.5, TurnAlert.leadSeconds(60.0, AlertTiming.LATER), 1e-9)
    }

    @Test
    fun `standard timing gives the owner's two examples`() {
        val mi = TurnAlert.METERS_PER_MILE
        assertEquals(0.50, TurnAlert.startMeters(60 * TurnAlert.MPS_PER_MPH, AlertTiming.STANDARD) / mi, 0.005)
        assertEquals(0.10, TurnAlert.startMeters(25 * TurnAlert.MPS_PER_MPH, AlertTiming.STANDARD) / mi, 0.005)
    }

    @Test
    fun `normal outside the zone, flashing inside, solid for the last 3 s`() {
        // 60 mph = 26.8 m/s from 1200 m out: the alert starts 805 m out, the solid frame 80 m out.
        var t = drive(60.0, fromM = 1200.0, fromS = 0, toS = 7) // 1012 m
        assertEquals(Phase.NORMAL, TurnAlert.phase(on, t))
        t = drive(60.0, fromM = 1200.0, fromS = 8, toS = 22) // 610 m
        assertEquals(Phase.FLASH, TurnAlert.phase(on, t))
        t = drive(60.0, fromM = 1200.0, fromS = 23, toS = 42) // 73 m, 2.7 s
        assertEquals(Phase.SOLID, TurnAlert.phase(on, t))
    }

    @Test
    fun `without the solid option the last 3 s keep flashing`() {
        val t = drive(60.0, fromM = 1200.0, fromS = 0, toS = 42)
        assertEquals(Phase.FLASH, TurnAlert.phase(on.copy(solidLast = false), t))
    }

    @Test
    fun `the distance is carried forward between updates`() {
        // Last update 839 m out at 60 mph: two seconds later the car is inside the 805 m zone.
        val t = drive(60.0, fromM = 920.0, fromS = 0, toS = 3)
        assertEquals(Phase.NORMAL, TurnAlert.phase(on, t))
        assertEquals(Phase.FLASH, TurnAlert.phase(on, t + 2_000))
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
}
