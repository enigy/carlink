package com.carlink.cluster

import com.carlink.cluster.ClusterRebind.Answer
import org.junit.Assert.assertEquals
import org.junit.Test

/** When the cluster session ends: Carlink's own teardown, the car's, and the cap on rebinds. */
class ClusterRebindTest {
    @Test
    fun `Carlink's own teardown is left to its relaunch`() {
        val r = ClusterRebind()
        // 11-03 18:00:40.701 finish -> 18:00:40.808 session destroyed.
        assertEquals(Answer.OURS, r.lost(nowMs = 40_808, ourTeardownAtMs = 40_701, wanted = true))
    }

    @Test
    fun `the car's drop during a phone session rebinds`() {
        val r = ClusterRebind()
        // 18:00:45.355: 4.6 s after Carlink's last finish, nothing of Carlink's ended it.
        assertEquals(Answer.REBIND, r.lost(nowMs = 45_355, ourTeardownAtMs = 40_701, wanted = true))
        assertEquals(Answer.REBIND, r.lost(nowMs = 600_000, ourTeardownAtMs = 0, wanted = true))
    }

    @Test
    fun `no phone or the cluster off needs nothing`() {
        assertEquals(Answer.NOT_NEEDED, ClusterRebind().lost(nowMs = 60_000, ourTeardownAtMs = 0, wanted = false))
    }

    @Test
    fun `three rebinds in five minutes, then Reconnect cluster`() {
        val r = ClusterRebind()
        assertEquals(Answer.REBIND, r.lost(10_000, 0, true))
        assertEquals(Answer.REBIND, r.lost(100_000, 0, true))
        assertEquals(Answer.REBIND, r.lost(200_000, 0, true))
        assertEquals(Answer.GIVE_UP, r.lost(250_000, 0, true))
        // The first one ages out of the window.
        assertEquals(Answer.REBIND, r.lost(310_001, 0, true))
        assertEquals(3, r.rebinds)
    }
}
