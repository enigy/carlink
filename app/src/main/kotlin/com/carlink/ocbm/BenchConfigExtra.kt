package com.carlink.ocbm

import android.content.Context
import com.carlink.BuildConfig
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import java.io.File

/**
 * A debug build's bench levers for the pushed VehicleConfig (truck research after LIVI, 8 Nov): the lines of
 * `ocbm/vehicle-config-extra.yaml` in the app's external files folder (`adb push` on the emulator) are appended to the
 * YAML pushed at SUBSCRIBE. Only OCBM's bench keys are taken ([KEYS]), one `key: value` per line, so a stray line can't
 * break the whole document; anything else is logged and dropped. A release build never reads the file.
 */
object BenchConfigExtra {
    /**
     * OCBM's top-level bench extensions this file may set (docs/carplay/04_CAPABILITIES_AND_CONFIG.md, ocbm 2026-11-08).
     * `location` (11-10) lists the sentences Carlink builds from the head unit's GPS, `location: GPGGA,GPRMC`
     * ([com.carlink.car.CarGpsFeed]).
     */
    val KEYS = setOf("source_version", "display_panels", "location")

    private val LINE = Regex("""^([a-z_]+):\s*("?[0-9A-Za-z.,]{1,48}"?)\s*$""")

    fun bytes(context: Context): ByteArray {
        val text = read(context) ?: return ByteArray(0)
        val (kept, dropped) = lines(text)
        dropped.forEach { logWarn("[BENCH] vehicle config extra: dropped '$it'", tag = Logger.Tags.CONFIG) }
        logInfo("[BENCH] vehicle config extra: ${kept.ifEmpty { listOf("nothing usable") }.joinToString("; ")}", tag = Logger.Tags.CONFIG)
        return kept.joinToString("") { it + "\n" }.toByteArray(Charsets.UTF_8)
    }

    /** Whether a debug build's bench file sets [key]. */
    fun sets(
        context: Context,
        key: String,
    ): Boolean = read(context)?.let { text -> lines(text).first.any { keyOf(it) == key } } ?: false

    private fun read(context: Context): String? {
        if (!BuildConfig.DEBUG) return null
        val file = context.getExternalFilesDir(null)?.let { File(it, "ocbm/vehicle-config-extra.yaml") } ?: return null
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull()
    }

    /** The lines of [text] that set one of [KEYS], each once (the last wins), and the lines dropped. */
    fun lines(text: String): Pair<List<String>, List<String>> {
        val byKey = LinkedHashMap<String, String>()
        val dropped = ArrayList<String>()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { line ->
            val key = keyOf(line)
            if (key == null) dropped += line else byKey[key] = line
        }
        return byKey.values.toList() to dropped
    }

    private fun keyOf(line: String): String? =
        LINE
            .matchEntire(line)
            ?.groupValues
            ?.get(1)
            ?.takeIf { it in KEYS }
}
