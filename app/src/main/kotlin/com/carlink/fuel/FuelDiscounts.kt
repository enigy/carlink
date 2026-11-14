package com.carlink.fuel

import java.util.Locale

/**
 * The stations whose discount program Settings › Vehicle › Fuel discounts lists (Scott, 14 Nov): a station of the brand
 * gets the driver's own amount off each unit of fuel. Costco and Sam's Club are not here: their posted price is already
 * the member price. Matched on the source's station name, the only brand the sources give.
 */
enum class DiscountBrand(
    val label: String,
    val code: String,
    private val names: Regex,
) {
    QUIKTRIP("QuikTrip", "quiktrip", Regex("""\bquik\s*trip\b|\bQT\b""", RegexOption.IGNORE_CASE)),
    KROGER("Kroger", "kroger", Regex("""\bkroger\b""", RegexOption.IGNORE_CASE)),
    SHELL("Shell", "shell", Regex("""\bshell\b""", RegexOption.IGNORE_CASE)),
    EXXON_MOBIL("Exxon and Mobil", "exxon_mobil", Regex("""\bexxon\b|\bmobil\b""", RegexOption.IGNORE_CASE)),
    BP("BP and Amoco", "bp", Regex("""\bbp\b|\bamoco\b""", RegexOption.IGNORE_CASE)),
    CHEVRON_TEXACO("Chevron and Texaco", "chevron_texaco", Regex("""\bchevron\b|\btexaco\b""", RegexOption.IGNORE_CASE)),
    CIRCLE_K("Circle K", "circle_k", Regex("""\bcircle\s*k\b""", RegexOption.IGNORE_CASE)),
    MURPHY_USA("Murphy USA", "murphy_usa", Regex("""\bmurphy\b""", RegexOption.IGNORE_CASE)),
    RACETRAC("RaceTrac", "racetrac", Regex("""\bracetrac\b""", RegexOption.IGNORE_CASE)),
    ;

    fun matches(stationName: String): Boolean = names.containsMatchIn(stationName)

    companion object {
        fun of(code: String): DiscountBrand? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One program the driver is in: a listed [brand], or another station by [name] (the station's name contains it), with
 * [cents] off each gallon (each litre outside the US: the unit the price is in) and whether diesel gets it too
 * ([allFuels]). No membership number or account: only that the driver is in it, and how much it takes off.
 */
data class FuelDiscount(
    val brand: DiscountBrand?,
    val name: String,
    val cents: Int,
    val allFuels: Boolean = false,
) {
    /** What the settings row and the logs call it. */
    val label: String get() = brand?.label ?: name

    /** Whether a station of this name is in the program. */
    fun matches(stationName: String): Boolean =
        brand?.matches(stationName)
            ?: (name.isNotBlank() && Regex("""\b${Regex.escape(name.trim())}\b""", RegexOption.IGNORE_CASE).containsMatchIn(stationName))
}

/** Taking the driver's discounts off a station's posted price, and keeping them as one preference string. */
object FuelDiscounts {
    private const val CENTS_PER_UNIT = 100.0
    private const val SEP_ITEM = '\n'
    private const val SEP_FIELD = '\t'

    /** The largest amount a program takes off, in cents: a typo of more is refused by the settings row. */
    const val MAX_CENTS = 200

    /** The program [station] is in for [fuel], the largest if more than one names it; null when none. */
    fun forStation(
        station: Station,
        fuel: CarFuel,
        discounts: List<FuelDiscount>,
    ): FuelDiscount? =
        discounts
            .filter { it.cents > 0 && (it.allFuels || fuel.kind == CarFuel.Kind.GASOLINE) && it.matches(station.name) }
            .maxByOrNull { it.cents }

    /** [posted] less [discount], never below zero; [posted] itself without one. */
    fun apply(
        posted: FuelPrice,
        discount: FuelDiscount?,
    ): FuelPrice = if (discount == null) posted else posted.copy(amount = (posted.amount - discount.cents / CENTS_PER_UNIT).coerceAtLeast(0.0))

    /** The settings row's words: "10¢ off gasoline", "10¢ off every fuel". */
    fun describe(discount: FuelDiscount): String = "${discount.cents}¢ off ${if (discount.allFuels) "every fuel" else "gasoline"}"

    /** One line per program: brand code (or empty), name, cents, all fuels. Names lose any tab or newline. */
    fun encode(discounts: List<FuelDiscount>): String =
        discounts.joinToString(SEP_ITEM.toString()) { d ->
            listOf(d.brand?.code.orEmpty(), d.name.replace(Regex("[\t\n]"), " "), d.cents.toString(), d.allFuels.toString())
                .joinToString(SEP_FIELD.toString())
        }

    /** The programs [text] holds; a line it can't read is left out. */
    fun decode(text: String?): List<FuelDiscount> =
        text.orEmpty().split(SEP_ITEM).mapNotNull { line ->
            val f = line.split(SEP_FIELD)
            val cents = f.getOrNull(2)?.toIntOrNull() ?: return@mapNotNull null
            val brand = f[0].takeIf { it.isNotEmpty() }?.let { DiscountBrand.of(it) ?: return@mapNotNull null }
            val name = f.getOrNull(1).orEmpty()
            if (brand == null && name.isBlank()) return@mapNotNull null
            FuelDiscount(brand, name, cents.coerceIn(0, MAX_CENTS), f.getOrNull(3)?.lowercase(Locale.ROOT) == "true")
        }
}
