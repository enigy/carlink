package com.carlink.ledger

import com.carlink.ledger.ConnectRecord.EndReason
import com.carlink.ledger.ConnectRecord.Kind
import com.carlink.ledger.ConnectRecord.Outcome
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * The ledger's stored form: it reads back what it wrote, never throws on a bad document, and cannot carry a phone's name,
 * model, Bluetooth or Wi-Fi address, network name or a place, whatever a record or a file holds.
 */
class ConnectLedgerCodecTest {
    private val full =
        ConnectRecord(
            startedAtMs = 1_795_330_933_000L,
            kind = Kind.WAKE_LISTED,
            outcome = Outcome.PICTURE,
            endMs = 3_412,
            subscribeMs = 612,
            pictureMs = 3_412,
            streamingMs = 4_105,
            starts = 1,
            frontMs = 214,
            suspendedMs = 412_300,
            adapter =
                AdapterStartup(
                    uptimeMs = 1_802_400,
                    preload = "done",
                    absentPages = 0,
                    resets = 2,
                    firstNudges = 1,
                    secondNudges = 0,
                    pskMismatches = 3,
                    pskAtLeast = true,
                ),
            timeline = "[timeline] v1 host_present=0 bt_up=1195 first_frame=7195 nudge_2=-",
        )

    private val early = ConnectRecord(startedAtMs = 1_795_331_000_000L, kind = Kind.RECONNECT, outcome = Outcome.ENDED_EARLY, endMs = 2_100, endReason = EndReason.DISPLAY_OFF)

    @Test
    fun `a ledger reads back as it was written, oldest first`() {
        val ledger = ConnectLedger().with(full).with(early)
        val back = ConnectLedgerCodec.decode(ConnectLedgerCodec.encode(ledger))
        assertEquals(ledger, back)
    }

    @Test
    fun `a record's stored form, as the bench prints it`() {
        assertEquals(
            "{\"startedAtMs\":1795330933000,\"origin\":\"display_on\",\"kind\":\"wake_listed\",\"outcome\":\"picture\",\"endMs\":3412," +
                "\"subscribeMs\":612,\"pictureMs\":3412,\"streamingMs\":4105,\"starts\":1,\"frontMs\":214,\"suspendedMs\":412300," +
                "\"adapter\":{\"uptimeMs\":1802400,\"preload\":\"done\",\"absentPages\":0,\"resets\":2,\"firstNudges\":1,\"secondNudges\":0," +
                "\"pskMismatches\":3,\"pskAtLeast\":true},\"timeline\":\"[timeline] v1 host_present=0 bt_up=1195 first_frame=7195 nudge_2=-\"}",
            ConnectLedgerCodec.encodeRecord(full),
        )
        assertEquals(
            "a missing step is left out",
            "{\"startedAtMs\":1795331000000,\"origin\":\"reconnect\",\"kind\":\"reconnect\",\"outcome\":\"ended_early\",\"endMs\":2100," +
                "\"endReason\":\"display_off\",\"starts\":0}",
            ConnectLedgerCodec.encodeRecord(early),
        )
    }

    @Test
    fun `records stored before the not-in-front outcome read as they were, and the new outcome reads back`() {
        // As 2a4316f stored them.
        val old =
            "{\"v\":1,\"connects\":[" +
                "{\"startedAtMs\":1,\"origin\":\"display_on\",\"kind\":\"wake_listed\",\"outcome\":\"no_phone\",\"endMs\":120000," +
                "\"subscribeMs\":600,\"starts\":1,\"frontMs\":0,\"suspendedMs\":0}," +
                "{\"startedAtMs\":2,\"origin\":\"reconnect\",\"kind\":\"reconnect\",\"outcome\":\"ended_early\",\"endMs\":2100," +
                "\"endReason\":\"display_off\",\"starts\":0}," +
                ConnectLedgerCodec.encodeRecord(full) + "]}"
        val back = ConnectLedgerCodec.decode(old)!!.records
        assertEquals(listOf(Outcome.NO_PHONE, Outcome.ENDED_EARLY, Outcome.PICTURE), back.map { it.outcome })
        assertEquals(full, back[2])

        val away = ConnectRecord(3, Kind.WAKE_LISTED, Outcome.NOT_IN_FRONT, endMs = 120_000, subscribeMs = 600, streamingMs = 4_000, starts = 1)
        val stored = ConnectLedgerCodec.encodeRecord(away)
        assertTrue(stored, stored.contains("\"outcome\":\"not_in_front\""))
        assertEquals(ConnectLedger(listOf(away)), ConnectLedgerCodec.decode("{\"v\":1,\"connects\":[$stored]}"))
    }

    @Test
    fun `a document that isn't a ledger reads as none, never a crash`() {
        for (bad in listOf("", "{", "not json", "[]", "{\"v\":2,\"connects\":[]}", "{\"connects\":[]}", "{\"v\":1}", "{\"v\":1,\"connects\":{}}")) {
            assertNull(bad, ConnectLedgerCodec.decode(bad))
        }
        assertEquals(ConnectLedger(), ConnectLedgerCodec.decode("{\"v\":1,\"connects\":[]}"))
    }

    @Test
    fun `a record it can't read is dropped and the others kept`() {
        val good = ConnectLedgerCodec.encodeRecord(full)
        val doc =
            "{\"v\":1,\"connects\":[" +
                "{\"kind\":\"wake_listed\",\"outcome\":\"picture\",\"endMs\":1}," + // no start time
                "{\"startedAtMs\":1,\"kind\":\"teleport\",\"outcome\":\"picture\",\"endMs\":1}," +
                "{\"startedAtMs\":1,\"kind\":\"reconnect\",\"outcome\":\"won\",\"endMs\":1}," +
                "{\"startedAtMs\":\"soon\",\"kind\":\"reconnect\",\"outcome\":\"picture\",\"endMs\":1}," +
                "7,null,\"text\"," +
                good + "]}"
        assertEquals(listOf(full), ConnectLedgerCodec.decode(doc)?.records)
    }

    @Test
    fun `a file holding more than 200 connects keeps the newest 200`() {
        val many = (1..250).joinToString(",") { ConnectLedgerCodec.encodeRecord(early.copy(startedAtMs = it.toLong())) }
        val back = ConnectLedgerCodec.decode("{\"v\":1,\"connects\":[$many]}")!!
        assertEquals(200, back.records.size)
        assertEquals(51L, back.records.first().startedAtMs)
    }

    /** Every key a stored record can hold, nested ones included. */
    private val allowedKeys =
        setOf(
            "startedAtMs",
            "origin",
            "kind",
            "outcome",
            "endMs",
            "endReason",
            "subscribeMs",
            "pictureMs",
            "streamingMs",
            "starts",
            "frontMs",
            "suspendedMs",
            "adapter",
            "timeline",
            "uptimeMs",
            "preload",
            "absentPages",
            "resets",
            "firstNudges",
            "secondNudges",
            "pskMismatches",
            "pskAtLeast",
        )

    private val personal = listOf("name", "model", "mac", "address", "bt", "wifi", "ssid", "bssid", "phone", "device", "location", "lat", "lon", "gps", "place", "position")

    private fun keysOf(o: JSONObject): Set<String> =
        o
            .keys()
            .asSequence()
            .flatMap { k -> sequenceOf(k) + (o.optJSONObject(k)?.let(::keysOf) ?: emptySet()) }
            .toSet()

    private fun stringsOf(o: JSONObject): List<String> =
        o
            .keys()
            .asSequence()
            .flatMap { k ->
                when (val v = o.get(k)) {
                    is String -> sequenceOf(v)
                    is JSONObject -> stringsOf(v).asSequence()
                    else -> emptySequence()
                }
            }.toList()

    @Test
    fun `a record's stored form has only its own keys, and none names a phone, an address, a network or a place`() {
        val stored = JSONObject(ConnectLedgerCodec.encodeRecord(full))
        val keys = keysOf(stored)
        assertTrue("unexpected keys: ${keys - allowedKeys}", allowedKeys.containsAll(keys))
        for (k in allowedKeys) {
            val lower = k.lowercase()
            assertFalse("$k", personal.any { it == lower || lower.startsWith(it) || lower.endsWith(it) })
        }
        // Its texts are the enums' codes, the preload word and the timeline line, each of a form no name or address fits.
        val codes = (Kind.entries.map { it.code } + Kind.entries.map { it.origin.code } + Outcome.entries.map { it.code } + EndReason.entries.map { it.code }).toSet()
        for (s in stringsOf(stored)) {
            assertTrue(s, s in codes || AdapterStartup.isPreloadWord(s) || AdapterTimeline.accept(s) == s)
        }
    }

    @Test
    fun `the record types hold no field for a phone, an address, a network or a place, and only two texts`() {
        for (type in listOf(ConnectRecord::class.java, AdapterStartup::class.java)) {
            val fields = type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }
            assertTrue(fields.isNotEmpty())
            for (field in fields) {
                val lower = field.name.lowercase()
                assertFalse("${type.simpleName}.${field.name}", personal.any { lower.startsWith(it) || lower.endsWith(it) })
                if (field.type == String::class.java) assertTrue("${type.simpleName}.${field.name} is a text", field.name in setOf("timeline", "preload"))
            }
        }
    }

    @Test
    fun `a file carrying a phone's name, address, network or place gives none of them back`() {
        val hostile =
            JSONObject(ConnectLedgerCodec.encodeRecord(full))
                .put("phoneName", "Test iPhone")
                .put("model", "iPhone99,9")
                .put("btMac", "02:00:00:00:0a:0b")
                .put("ssid", "TestNet")
                .put("lat", 47.0)
                .put("lon", -122.0)
                .put("preload", "Test iPhone")
                .put("timeline", "[timeline] v1 bt=02:00:00:00:0a:0b")
        hostile.getJSONObject("adapter").put("preload", "Test iPhone").put("deviceName", "Test iPhone")
        val doc = JSONObject().put("v", 1).put("connects", JSONArray().put(hostile)).toString()

        val back = ConnectLedgerCodec.decode(doc)?.records?.single()
        assertNotNull(back)
        assertNull("a timeline not in the v1 form is dropped", back!!.timeline)
        assertNull("a preload that isn't a lower-case word is dropped", back.adapter?.preload)
        val again = ConnectLedgerCodec.encodeRecord(back)
        for (gone in listOf("Test iPhone", "iPhone99", "02:00:00:00:0a:0b", "TestNet", "47.0", "phoneName", "btMac", "ssid", "lat", "lon", "deviceName", "model")) {
            assertFalse(gone, again.contains(gone))
        }
    }

    @Test
    fun `a record handed a bad text keeps it out of the stored form`() {
        val bad = full.copy(timeline = "[timeline] v1 phone=Test iPhone", adapter = full.adapter?.copy(preload = "Test iPhone"))
        val stored = JSONObject(ConnectLedgerCodec.encodeRecord(bad))
        assertFalse(stored.has("timeline"))
        assertFalse(stored.getJSONObject("adapter").has("preload"))
        for (timeline in listOf("[timeline] v1 bt_02000000a0b1=1", "[timeline] v1 dial=12345678901234", "[timeline] v1 ${"k".repeat(25)}=1")) {
            assertFalse(timeline, JSONObject(ConnectLedgerCodec.encodeRecord(full.copy(timeline = timeline))).has("timeline"))
        }
    }

    @Test
    fun `a file whose timeline holds an address in a key gives no timeline back`() {
        val stored = JSONObject(ConnectLedgerCodec.encodeRecord(full)).put("timeline", "[timeline] v1 dial=1 a4c3f085ac2d=7")
        val doc = JSONObject().put("v", 1).put("connects", JSONArray().put(stored)).toString()
        val back = ConnectLedgerCodec.decode(doc)!!.records.single()
        assertNull(back.timeline)
    }
}
