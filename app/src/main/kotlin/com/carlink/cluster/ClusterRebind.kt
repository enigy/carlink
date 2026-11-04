package com.carlink.cluster

/**
 * What to do when the cluster's primary session ends. Carlink ends it on every phone connect and on
 * Reconnect cluster, and puts it back itself; any other end is the car's. On the 11-03 drive the car
 * removed the hidden cluster task three times in six minutes, the cluster and HUD went blank each
 * time, and only a Reconnect cluster press brought them back. Now Carlink rebinds by itself, up to
 * [limit] times in [windowMs], so a car that keeps dropping the session cannot keep the screen
 * flashing the hidden activity.
 */
class ClusterRebind(
    private val limit: Int = 3,
    private val windowMs: Long = 5 * 60_000L,
) {
    enum class Answer {
        /** Carlink's own teardown: its relaunch is already on the way. */
        OURS,

        /** No phone, or Cluster Navigation off: the next phone connect binds it again. */
        NOT_NEEDED,

        REBIND,

        /** [limit] rebinds inside [windowMs]: left for Reconnect cluster. */
        GIVE_UP,
    }

    private val recent = ArrayDeque<Long>()

    /** Rebinds in the window, after the last [lost]. */
    val rebinds: Int get() = recent.size

    /** The session ended at [nowMs]; Carlink last finished the cluster task at [ourTeardownAtMs] (0 = never). */
    fun lost(
        nowMs: Long,
        ourTeardownAtMs: Long,
        wanted: Boolean,
    ): Answer {
        if (ourTeardownAtMs > 0 && nowMs - ourTeardownAtMs < OURS_MS) return Answer.OURS
        if (!wanted) return Answer.NOT_NEEDED
        while (recent.isNotEmpty() && nowMs - recent.first() >= windowMs) recent.removeFirst()
        if (recent.size >= limit) return Answer.GIVE_UP
        recent.addLast(nowMs)
        return Answer.REBIND
    }

    private companion object {
        /** Carlink's finish to the session's end took about 0.1-0.3 s on the 11-03 drive. */
        const val OURS_MS = 3_000L
    }
}
