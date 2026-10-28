package com.carlink.navigation.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Roundabout arms where CarPlay's card draws them (owner, 10-28). */
class RoundaboutSlotsTest {
    @Test
    fun `the raw angles from the drive logs land in CarPlay's slots`() {
        // cp28 76°, cp30 −76°, cp29 −3° / −2° / −175°: square arms sent as the map's raw angles.
        assertEquals(listOf(90, -90, 0, 0, 180), listOf(76, -76, -3, -2, -175).map(RoundaboutSlots::snap))
    }

    @Test
    fun `every angle goes to the nearest 45 degree slot, back being 180`() {
        assertEquals(listOf(0, 0, 45, -45, -45, 135, 180, 180, 180), listOf(22, -22, 23, -23, -67, 150, 180, -180, 181).map(RoundaboutSlots::snap))
    }

    @Test
    fun `the grey arms take a slot once and leave the entry and the exit to the arrow`() {
        assertEquals(listOf(-90, 45), RoundaboutSlots.arms(listOf(-80, -95, 172, 76, 40), exitSlot = 90))
    }

    @Test
    fun `the table's arrow follows the angle alone, so any exit number can use it`() {
        for (deg in -180..170 step 10) {
            val arrows = (2..5).flatMap { arms -> (1 until arms).mapNotNull { AppleManeuverPaths.roundaboutPaths(arms, it, deg.toDouble())?.arrow } }
            assertEquals("one arrow at $deg°", 1, arrows.distinct().size)
        }
    }

    @Test
    fun `each arrow points into its slot, the diagonals too`() {
        for (slot in listOf(0, 45, 90, 135, 180, -45, -90, -135)) {
            val arrow = RoundaboutSlots.arrow(slot)
            assertNotNull("slot $slot", arrow)
            val tip = pointing(arrow!!)
            val off = abs(((tip - slot) % 360 + 540) % 360 - 180)
            assertTrue("slot $slot drawn at $tip", off < 1.0)
        }
    }

    @Test
    fun `only paths of one shape blend`() {
        assertEquals("M 1.0000 2.0000 L 3.0000 4.0000", RoundaboutSlots.blend("M 0.0000 2.0000 L 2.0000 4.0000", "M 2.0000 2.0000 L 4.0000 4.0000"))
        assertNull(RoundaboutSlots.blend("M 0.0000 2.0000 L 2.0000 4.0000", "M 2.0000 2.0000 C 4.0000 4.0000"))
    }

    /** The direction of the arrow's farthest point from the ring's centre (28, 28): the head. */
    private fun pointing(svg: String): Double {
        val n = Regex("-?\\d+\\.\\d+").findAll(svg).map { it.value.toDouble() }.toList()
        val head = n.chunked(2).filter { it.size == 2 }.maxBy { (x, y) -> hypot(x - CENTRE, y - CENTRE) }
        return Math.toDegrees(atan2(head[0] - CENTRE, CENTRE - head[1]))
    }

    private companion object {
        const val CENTRE = 28.0
    }
}
