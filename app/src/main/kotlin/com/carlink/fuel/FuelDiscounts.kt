package com.carlink.fuel

import java.util.Locale

/**
 * What may follow a brand at the start of a station's name: the end, a number or sign, or a word stations add to it. Not an
 * apostrophe: "Shell's Garage" is someone's garage (the Mac's 14 Nov review, E2).
 */
private const val BRAND_TAIL =
    """(?=\s*$|\s*[^\p{L}\s'’]|\s+(?:gas|gasoline|station|food|mart|store|express|fuel|center|centre|select|stop|travel|plaza|truck|connect)\b)"""

/**
 * A brand's [names] (alternatives), matched at the start of a station's name and followed by [BRAND_TAIL]. File-level, not
 * in the enum's companion: the entries are built before a companion is.
 */
private fun brand(names: String) = Regex("""^\s*(?:$names)$BRAND_TAIL""", RegexOption.IGNORE_CASE)

/**
 * The stations whose discount program Settings › Vehicle › Fuel discounts lists (Scott, 14 Nov): a station of the brand
 * gets the driver's own amount off each gallon. Costco and Sam's Club are not here: their posted price is already the
 * member price. Matched on the source's station name, the only brand the sources give: the brand must START the name and
 * be followed by nothing, a number or sign, or a word stations add ("Gas", "Station", "Food Mart", "Express"), so an
 * ordinary word never takes a discount the pump won't give ("Murphy's Market", "Sea Shell Market", "Shell Rock Fuel";
 * the Mac's 13 Nov review). Kroger counts at its banners' fuel centers too (Scott, 13 Nov), matched only as "<banner> Fuel
 * Center" (or "<banner> Marketplace Fuel Center"), so a surname ("Smith's Auto Service", "Fry's Fuel & Food") never does.
 * "QT" alone counts only as the whole name or with a store number: "QT Mart" may be anyone's (the Mac's 14 Nov review).
 */
enum class DiscountBrand(
    val label: String,
    val code: String,
    private val names: Regex,
) {
    QUIKTRIP("QuikTrip", "quiktrip", Regex("""^\s*(?:quik\s*trip$BRAND_TAIL|qt\s*(?:#\s*)?\d*\s*$)""", RegexOption.IGNORE_CASE)),
    KROGER(
        "Kroger",
        "kroger",
        brand(
            """kroger(?:\s+marketplace)?|""" +
                """(?:king\s+soopers|fry['’]?s|dillon['’]?s|ralph['’]?s|smith['’]?s|fred\s+meyer|harris\s+teeter|qfc)""" +
                """(?:\s+marketplace)?\s+fuel\s+cent(?:er|re)""",
        ),
    ),
    SHELL("Shell", "shell", brand("""shell""")),
    EXXON_MOBIL("Exxon and Mobil", "exxon_mobil", brand("""exxon\s*mobil|exxon|mobil""")),
    BP("BP and Amoco", "bp", brand("""bp|amoco""")),
    CHEVRON_TEXACO("Chevron and Texaco", "chevron_texaco", brand("""chevron\s*texaco|chevron|texaco""")),
    CIRCLE_K("Circle K", "circle_k", brand("""circle[\s-]*k""")),
    MURPHY_USA("Murphy USA", "murphy_usa", brand("""murphy\s+(?:usa|express)""")),
    RACETRAC("RaceTrac", "racetrac", brand("""race\s*trac""")),
    ;

    fun matches(stationName: String): Boolean = names.containsMatchIn(stationName)

    companion object {
        fun of(code: String): DiscountBrand? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One program the driver is in: a listed [brand], or another station by [name] (the station's name, or the card's
 * "<name> on <street>", contains it), with [cents] off each US gallon and whether diesel gets it too ([allFuels], on
 * unless the driver turns it off: Scott, 13 Nov). No membership number or account: only that the driver is in it, and
 * how much it takes off.
 */
data class FuelDiscount(
    val brand: DiscountBrand?,
    val name: String,
    val cents: Int,
    val allFuels: Boolean = true,
) {
    /** What the settings row and the logs call it. */
    val label: String get() = brand?.label ?: name

    /**
     * Whether [station] is in the program: a listed brand by its name; another station by the name typed, found in the
     * station's name with punctuation-tolerant edges ("Gas (Main St)"), or the card's whole "<name> on <street>" typed
     * from its start. Never in a street alone: "76" would match "Shell on CA-76" (the Mac's 14 Nov review, E1). The two
     * apostrophes count as one ("Casey's", "Casey’s").
     */
    fun matches(station: Station): Boolean {
        if (brand != null) return brand.matches(station.name)
        val typed = Regex.escape(apostrophes(name.trim()))
        if (name.isBlank()) return false
        val inName = Regex("""(?<![\p{L}\p{N}])$typed(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
        val asTitle = Regex("""^\s*$typed(?![\p{L}\p{N}])""", RegexOption.IGNORE_CASE)
        return inName.containsMatchIn(apostrophes(station.name)) || asTitle.containsMatchIn(apostrophes(StationText.title(station)))
    }

    private fun apostrophes(s: String) = s.replace('’', '\'')
}

/** Taking the driver's discounts off a station's posted price, and keeping them as one preference string. */
object FuelDiscounts {
    private const val CENTS_PER_UNIT = 100.0
    private const val SEP_ITEM = '\n'
    private const val SEP_FIELD = '\t'

    private const val US_DOLLARS = "USD"

    /** The largest amount a program takes off, in cents: a typo of more is refused by the settings row. */
    const val MAX_CENTS = 200

    /** The longest name another station may have, typed or picked. */
    const val MAX_NAME = 40

    /** What a source calls a station with no name (GooglePlaces, OverpassSource): never a program's name. */
    const val UNNAMED = "Fuel station"

    /**
     * The names the "Another station" dialog offers from the stations around: not a listed brand, not the placeholder
     * for an unnamed station (which would match every one of them), not without a letter, and at most [MAX_NAME] long
     * (the Mac's 14 Nov review, E4).
     */
    fun pickable(stations: List<Station>): List<String> =
        stations
            .map { it.name.trim() }
            .filter { n -> n.any(Char::isLetter) && !n.equals(UNNAMED, ignoreCase = true) && DiscountBrand.entries.none { it.matches(n) } }
            .map { it.take(MAX_NAME).trim() }
            .distinct()

    /**
     * The program [station] is in for [fuel], the largest if more than one names it; null when none. Only on a US price per
     * gallon (USD): the programs are cents off a US gallon, and a Canadian station's price per litre would show a discount
     * it doesn't honour (the Mac's 13 Nov review, item 6).
     */
    fun forStation(
        station: Station,
        fuel: CarFuel,
        discounts: List<FuelDiscount>,
    ): FuelDiscount? =
        discounts
            .takeIf { station.priceFor(fuel)?.currency == US_DOLLARS }
            .orEmpty()
            .filter { it.cents > 0 && (it.allFuels || fuel.kind == CarFuel.Kind.GASOLINE) && it.matches(station) }
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
