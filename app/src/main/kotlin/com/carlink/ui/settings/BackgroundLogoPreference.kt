package com.carlink.ui.settings

import android.content.Context
import androidx.annotation.DrawableRes
import com.carlink.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Brand logo on the connecting screen's background scene (Carlink2 [216]). App-only: the box never
 * sees it, so a change applies on the spot with no reconnect. Persisted by [code], never ordinal.
 */
enum class BackgroundLogoConfig(
    val code: String,
    override val label: String,
    /** The background scene with this logo: the connecting screen and the settings glass. */
    @DrawableRes val drawableRes: Int,
) : Labelled {
    /** The original background (res/drawable/loading_bg.webp). */
    GMC("gmc", "GMC", R.drawable.loading_bg),

    /** res/drawable-nodpi/loading_bg_chevrolet.jpg — same scene with the Chevrolet bowtie. */
    CHEVROLET("chevrolet", "CHEVROLET", R.drawable.loading_bg_chevrolet),
    ;

    companion object {
        val DEFAULT = GMC

        fun fromCode(code: String?): BackgroundLogoConfig = entries.find { it.code == code } ?: DEFAULT
    }
}

/**
 * The persisted [BackgroundLogoConfig], in the style of [DisplayModeStore]: SharedPreferences, read
 * once, plus a [StateFlow] so the connecting screen and the settings glass follow a change live.
 * One instance per process ([get]).
 */
class BackgroundLogoStore private constructor(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(BackgroundLogoConfig.fromCode(prefs.getString(KEY_LOGO, null)))

    /** Current choice; collect it to follow changes. */
    val flow: StateFlow<BackgroundLogoConfig> = state.asStateFlow()

    val current: BackgroundLogoConfig get() = state.value

    fun set(logo: BackgroundLogoConfig) {
        prefs.edit().putString(KEY_LOGO, logo.code).apply()
        state.value = logo
    }

    companion object {
        // PERSISTENCE CONTRACT: renaming either orphans every stored choice.
        private const val PREFS_NAME = "carlink_background_logo"
        private const val KEY_LOGO = "logo"

        @Volatile private var instance: BackgroundLogoStore? = null

        fun get(context: Context): BackgroundLogoStore =
            instance ?: synchronized(this) {
                instance ?: BackgroundLogoStore(context).also { instance = it }
            }
    }
}
