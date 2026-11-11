package com.carlink.ui.settings

/**
 * Where the Fuel card's nearby stations come from: OpenStreetMap ([FREE], the default) or, in debug builds only,
 * made-up stations with prices ([DEMO]) for the bench. Google Places with the driver's own key joins in stage C.
 * Persisted by [code].
 */
enum class StationSource(
    override val label: String,
    val code: String,
) : Labelled {
    FREE("OpenStreetMap", "free"),
    DEMO("Demo (debug)", "demo"),
    ;

    companion object {
        /** The stored [code]'s source; an unknown or missing one is [FREE]. */
        fun of(code: String?): StationSource = entries.firstOrNull { it.code == code } ?: FREE
    }
}
