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
    /** OCBM's top-level bench extensions this file may set (docs/carplay/04_CAPABILITIES_AND_CONFIG.md, ocbm 2026-11-08). */
    val KEYS = setOf("source_version", "display_panels")

    private val LINE = Regex("""^([a-z_]+):\s*("?[0-9A-Za-z.]{1,24}"?)\s*$""")

    fun bytes(context: Context): ByteArray {
        if (!BuildConfig.DEBUG) return ByteArray(0)
        val file = context.getExternalFilesDir(null)?.let { File(it, "ocbm/vehicle-config-extra.yaml") } ?: return ByteArray(0)
        if (!file.isFile) return ByteArray(0)
        val text = runCatching { file.readText() }.getOrElse { return ByteArray(0) }
        val (kept, dropped) = lines(text)
        dropped.forEach { logWarn("[BENCH] vehicle config extra: dropped '$it'", tag = Logger.Tags.CONFIG) }
        logInfo("[BENCH] vehicle config extra: ${kept.ifEmpty { listOf("nothing usable") }.joinToString("; ")}", tag = Logger.Tags.CONFIG)
        return kept.joinToString("") { it + "\n" }.toByteArray(Charsets.UTF_8)
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
