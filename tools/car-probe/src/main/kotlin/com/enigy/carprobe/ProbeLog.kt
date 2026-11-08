package com.enigy.carprobe

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One JSON object per line, in the app's own files (`probe/probe-<start>.jsonl`): every line has `t` (epoch ms),
 * `probe` (which question it answers: vehicle, location, video, audio, display, camera, meta, perm) and its fields.
 * [export] copies every log to Downloads/CarlinkProbe, where the head unit's Files app can put them on a USB stick.
 */
object ProbeLog {
    private const val TAG = "CarlinkProbe"
    private const val SHOWN = 200

    private val lock = Any()
    private var file: File? = null
    private val recent = ArrayDeque<String>()

    @Volatile var listener: (() -> Unit)? = null

    fun open(context: Context) {
        synchronized(lock) {
            if (file != null) return
            val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "probe").apply { mkdirs() }
            file = File(dir, "probe-${stamp()}.jsonl")
        }
        log("meta", "opened" to file?.name)
    }

    fun log(
        probe: String,
        vararg fields: Pair<String, Any?>,
    ) {
        val o = JSONObject().put("t", System.currentTimeMillis()).put("probe", probe)
        for ((k, v) in fields) o.put(k, json(v))
        write(o)
    }

    fun write(o: JSONObject) {
        val line = o.toString()
        Log.i(TAG, line)
        synchronized(lock) {
            runCatching { file?.appendText(line + "\n") }
            recent.addLast(line)
            while (recent.size > SHOWN) recent.removeFirst()
        }
        listener?.invoke()
    }

    fun recentLines(): List<String> = synchronized(lock) { recent.toList() }

    /** Every probe log to Downloads/CarlinkProbe; the names written, or why not. */
    fun export(context: Context): String {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "probe")
        val logs = dir.listFiles { f -> f.name.endsWith(".jsonl") }?.sortedBy { it.name }.orEmpty()
        if (logs.isEmpty()) return "no logs yet"
        val written = ArrayList<String>()
        for (f in logs) {
            val values =
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/CarlinkProbe")
                }
            val result =
                runCatching {
                    val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("insert refused")
                    context.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } ?: error("no stream")
                }
            written += if (result.isSuccess) f.name else "${f.name}: ${result.exceptionOrNull()?.message}"
        }
        return "Downloads/CarlinkProbe: " + written.joinToString(", ")
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    /** Lists, maps and arrays become JSON; anything else its string or number. */
    fun json(v: Any?): Any =
        when (v) {
            null -> JSONObject.NULL
            is JSONObject, is JSONArray, is String, is Number, is Boolean -> v
            is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), json(x)) } }
            is Collection<*> -> JSONArray().also { a -> v.forEach { a.put(json(it)) } }
            is Array<*> -> JSONArray().also { a -> v.forEach { a.put(json(it)) } }
            is IntArray -> JSONArray().also { a -> v.forEach { a.put(it) } }
            is LongArray -> JSONArray().also { a -> v.forEach { a.put(it) } }
            is FloatArray -> JSONArray().also { a -> v.forEach { a.put(it.toDouble()) } }
            is BooleanArray -> JSONArray().also { a -> v.forEach { a.put(it) } }
            is ByteArray -> v.joinToString("") { "%02x".format(it) }
            is Throwable -> "${v.javaClass.simpleName}: ${v.message}"
            else -> v.toString()
        }
}
