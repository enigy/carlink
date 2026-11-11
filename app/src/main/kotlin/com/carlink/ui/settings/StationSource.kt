package com.carlink.ui.settings

/**
 * Settings › Vehicle › Price source: where the Fuel card's stations come from. [FREE] (the default) is OpenStreetMap,
 * stations without prices; [GOOGLE] is Google Places with the driver's own key, prices included; [DEMO], in debug
 * builds only, is made-up stations with prices for the bench. Persisted by [code].
 */
enum class StationSource(
    override val label: String,
    val code: String,
) : Labelled {
    FREE("Free (OpenStreetMap)", "free"),
    GOOGLE("Google Places (your key)", "google"),
    DEMO("Demo (debug)", "demo"),
    ;

    companion object {
        /** The stored [code]'s source; an unknown or missing one is [FREE]. */
        fun of(code: String?): StationSource = entries.firstOrNull { it.code == code } ?: FREE

        /** The choices this build offers: Demo in debug builds only. */
        fun offered(debug: Boolean): List<StationSource> = if (debug) entries else entries - DEMO
    }
}
