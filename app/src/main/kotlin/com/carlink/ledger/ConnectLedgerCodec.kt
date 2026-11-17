package com.carlink.ledger

import com.carlink.ledger.ConnectRecord.EndReason
import com.carlink.ledger.ConnectRecord.Kind
import com.carlink.ledger.ConnectRecord.Outcome
import org.json.JSONException
import org.json.JSONObject

/**
 * The ledger's stored form, JSON:
 *
 *     {"v":1,"connects":[{"startedAtMs":1795330933000,"origin":"display_on","kind":"wake_listed","outcome":"picture",
 *       "endMs":3412,"subscribeMs":612,"pictureMs":3412,"streamingMs":4105,"starts":1,"frontMs":214,"suspendedMs":0,
 *       "adapter":{"uptimeMs":1802400,"preload":"done","absentPages":0,"resets":0,"firstNudges":0,"secondNudges":0,
 *       "pskMismatches":0},"timeline":"[timeline] v1 a=1 b=2"}]}
 *
 * Oldest first. Only these keys are ever written, with numbers, booleans, the enums' codes and the two checked texts
 * (the preload word, the timeline line); a field that is null is left out. A document written by one version is read by
 * the next, so [decode] never throws: an unreadable document is null, and a record it can't read, or a text that fails
 * its check, is dropped. Keys it doesn't know are ignored, so nothing else a file might carry is read back.
 */
object ConnectLedgerCodec {
    const val SCHEMA_VERSION = 1

    /** [ledger] as one document, in a stable key order. */
    fun encode(ledger: ConnectLedger): String =
        buildString {
            append("{\"v\":").append(SCHEMA_VERSION).append(",\"connects\":[")
            ledger.records.forEachIndexed { i, r ->
                if (i > 0) append(',')
                append(encodeRecord(r))
            }
            append("]}")
        }

    /** One record as a JSON object: the stored form, and what the debug bench prints. */
    fun encodeRecord(r: ConnectRecord): String {
        val f = Fields()
        f.number("startedAtMs", r.startedAtMs)
        f.text("origin", r.origin.code)
        f.text("kind", r.kind.code)
        f.text("outcome", r.outcome.code)
        f.number("endMs", r.endMs)
        f.text("endReason", r.endReason?.code)
        f.number("subscribeMs", r.subscribeMs)
        f.number("pictureMs", r.pictureMs)
        f.number("streamingMs", r.streamingMs)
        f.number("starts", r.starts.toLong())
        f.number("frontMs", r.frontMs)
        f.number("suspendedMs", r.suspendedMs)
        r.adapter?.let { f.raw("adapter", encodeAdapter(it)) }
        f.text("timeline", r.timeline?.let(AdapterTimeline::accept))
        return f.close()
    }

    private fun encodeAdapter(a: AdapterStartup): String {
        val f = Fields()
        f.number("uptimeMs", a.uptimeMs)
        f.text("preload", a.preload?.takeIf(AdapterStartup::isPreloadWord))
        f.number("absentPages", a.absentPages?.toLong())
        f.number("resets", a.resets?.toLong())
        f.number("firstNudges", a.firstNudges?.toLong())
        f.number("secondNudges", a.secondNudges?.toLong())
        f.number("pskMismatches", a.pskMismatches?.toLong())
        if (a.pskAtLeast) f.raw("pskAtLeast", "true")
        return f.close()
    }

    /** The ledger in [json], or null when it can't be read as one (not JSON, another schema, no list). */
    fun decode(json: String): ConnectLedger? {
        val root =
            try {
                JSONObject(json)
            } catch (_: JSONException) {
                return null
            }
        if (root.optInt("v", -1) != SCHEMA_VERSION) return null
        val list = root.optJSONArray("connects") ?: return null
        val records = (0 until list.length()).mapNotNull { i -> list.optJSONObject(i)?.let(::decodeRecord) }
        return ConnectLedger(records.takeLast(ConnectLedger.CAP))
    }

    /** One record, or null without the fields every record has. Texts that fail their checks are dropped. */
    fun decodeRecord(o: JSONObject): ConnectRecord? {
        val kind = Kind.entries.firstOrNull { it.code == o.optString("kind") } ?: return null
        val outcome = Outcome.entries.firstOrNull { it.code == o.optString("outcome") } ?: return null
        return ConnectRecord(
            startedAtMs = long(o, "startedAtMs") ?: return null,
            kind = kind,
            outcome = outcome,
            endMs = long(o, "endMs") ?: return null,
            endReason = EndReason.entries.firstOrNull { it.code == o.optString("endReason") },
            subscribeMs = long(o, "subscribeMs"),
            pictureMs = long(o, "pictureMs"),
            streamingMs = long(o, "streamingMs"),
            starts = count(o, "starts") ?: 0,
            frontMs = long(o, "frontMs"),
            suspendedMs = long(o, "suspendedMs"),
            adapter = o.optJSONObject("adapter")?.let(::decodeAdapter),
            timeline = (o.opt("timeline") as? String)?.let(AdapterTimeline::accept),
        )
    }

    private fun decodeAdapter(o: JSONObject): AdapterStartup =
        AdapterStartup(
            uptimeMs = long(o, "uptimeMs"),
            preload = (o.opt("preload") as? String)?.takeIf(AdapterStartup::isPreloadWord),
            absentPages = count(o, "absentPages"),
            resets = count(o, "resets"),
            firstNudges = count(o, "firstNudges"),
            secondNudges = count(o, "secondNudges"),
            pskMismatches = count(o, "pskMismatches"),
            pskAtLeast = o.opt("pskAtLeast") == true,
        )

    private fun long(
        o: JSONObject,
        key: String,
    ): Long? = (o.opt(key) as? Number)?.toLong()

    private fun count(
        o: JSONObject,
        key: String,
    ): Int? = long(o, key)?.takeIf { it in 0..Int.MAX_VALUE }?.toInt()

    /** Appends `"key":value` pairs in the order given; a null value is left out. */
    private class Fields {
        private val out = StringBuilder("{")

        fun raw(
            key: String,
            value: String,
        ) {
            if (out.length > 1) out.append(',')
            out
                .append('"')
                .append(key)
                .append("\":")
                .append(value)
        }

        fun number(
            key: String,
            value: Long?,
        ) {
            if (value != null) raw(key, value.toString())
        }

        fun text(
            key: String,
            value: String?,
        ) {
            if (value != null) raw(key, JSONObject.quote(value))
        }

        fun close(): String = out.append('}').toString()
    }
}
