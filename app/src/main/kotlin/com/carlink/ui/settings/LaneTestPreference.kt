package com.carlink.ui.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How the cluster turn icon shows lanes (Settings › Cluster › Lanes in the turn icon). */
enum class LaneIconMode(
    val code: String,
    override val label: String,
) : Labelled {
    OFF("off", "Off"),
    ARROW("arrow", "Arrow over lanes"),
    LANES("lanes", "Lanes only"),
    ;

    companion object {
        fun fromCode(code: String?): LaneIconMode = entries.firstOrNull { it.code == code } ?: OFF
    }
}

/** Where drawn lanes come from: the iPhone's lane guidance, or a fixed pattern for a parked check. */
enum class LaneSource(
    val code: String,
    override val label: String,
) : Labelled {
    IPHONE("iphone", "iPhone"),
    TEST("test", "Test pattern"),
    ;

    companion object {
        fun fromCode(code: String?): LaneSource = entries.firstOrNull { it.code == code } ?: IPHONE
    }
}

/**
 * The lane guidance test switches (design "Lane guidance tests", 2026-10-21). All off by default.
 *
 * [ask] is the only one that reaches the adapter: it widens the metadata Carlink3 pushes on the next
 * `CT_SUBSCRIBE` so the iPhone is asked for iAP2 lane guidance. The rest change only what Carlink3
 * draws for GM's cluster and HUD. [refusedAtMs] is when [ask] last turned itself off because the
 * iPhone rejected the identification that carried it (0 = never).
 */
data class LaneTestConfig(
    val ask: Boolean = false,
    val icon: LaneIconMode = LaneIconMode.OFF,
    val strip: Boolean = false,
    val source: LaneSource = LaneSource.IPHONE,
    val card: Boolean = false,
    val slotCard: Boolean = false,
    val refusedAtMs: Long = 0L,
) {
    /** True when anything is switched on, for the Cluster category's summary. */
    val anyOn: Boolean get() = ask || icon != LaneIconMode.OFF || strip || card || slotCard
}

/** Durable [LaneTestConfig]; read by the cluster step builder and the adapter config. */
class LaneTestStore private constructor(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val state =
        MutableStateFlow(
            LaneTestConfig(
                ask = prefs.getBoolean(KEY_ASK, false),
                icon = LaneIconMode.fromCode(prefs.getString(KEY_ICON, null)),
                strip = prefs.getBoolean(KEY_STRIP, false),
                source = LaneSource.fromCode(prefs.getString(KEY_SOURCE, null)),
                card = prefs.getBoolean(KEY_CARD, false),
                slotCard = prefs.getBoolean(KEY_SLOT_CARD, false),
                refusedAtMs = prefs.getLong(KEY_REFUSED_AT, 0L),
            ),
        )

    val flow: StateFlow<LaneTestConfig> = state.asStateFlow()

    val current: LaneTestConfig get() = state.value

    fun setAsk(on: Boolean) = update(current.copy(ask = on))

    fun setIcon(mode: LaneIconMode) = update(current.copy(icon = mode))

    fun setStrip(on: Boolean) = update(current.copy(strip = on))

    fun setSource(source: LaneSource) = update(current.copy(source = source))

    fun setCard(on: Boolean) = update(current.copy(card = on))

    fun setSlotCard(on: Boolean) = update(current.copy(slotCard = on))

    /** The iPhone refused an identification that asked for lanes: stop asking. */
    fun markRefused(atMs: Long) = update(current.copy(ask = false, refusedAtMs = atMs))

    private fun update(c: LaneTestConfig) {
        prefs
            .edit()
            .putBoolean(KEY_ASK, c.ask)
            .putString(KEY_ICON, c.icon.code)
            .putBoolean(KEY_STRIP, c.strip)
            .putString(KEY_SOURCE, c.source.code)
            .putBoolean(KEY_CARD, c.card)
            .putBoolean(KEY_SLOT_CARD, c.slotCard)
            .putLong(KEY_REFUSED_AT, c.refusedAtMs)
            .apply()
        state.value = c
    }

    companion object {
        // PERSISTENCE CONTRACT: renaming any of these orphans the stored choice.
        private const val PREFS_NAME = "carlink_lane_tests"
        private const val KEY_ASK = "ask"
        private const val KEY_ICON = "icon"
        private const val KEY_STRIP = "strip"
        private const val KEY_SOURCE = "source"
        private const val KEY_CARD = "card"
        private const val KEY_SLOT_CARD = "slot_card"
        private const val KEY_REFUSED_AT = "refused_at"

        @Volatile private var instance: LaneTestStore? = null

        fun get(context: Context): LaneTestStore =
            instance ?: synchronized(this) {
                instance ?: LaneTestStore(context).also { instance = it }
            }
    }
}
