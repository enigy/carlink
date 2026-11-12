package com.carlink.fuel

/**
 * The Fuel card's Google lookups this calendar month, against the driver's cap (the brief of 11 Nov: default 900, under
 * the free 1,000 a month Google gives the Nearby Search Enterprise + Atmosphere rate at the time of writing). At the cap
 * the card shows the free stations instead and says so. [month] is "2026-11"; a new month starts at zero. Plain Kotlin.
 */
data class MonthlyBudget(
    val month: String,
    val count: Int,
    val cap: Int,
) {
    /** This month's count, from zero when [now] is a new month. */
    fun used(now: String): Int = if (now == month) count else 0

    /** Room for one more lookup in [now]. */
    fun allows(now: String): Boolean = used(now) < cap

    /** The budget after one lookup in [now]. */
    fun spend(now: String): MonthlyBudget = MonthlyBudget(now, used(now) + 1, cap)

    companion object {
        const val DEFAULT_CAP = 900

        /** The caps Settings › Vehicle offers. */
        val CAPS = listOf(300, 900, 2_000, 5_000)
    }
}
