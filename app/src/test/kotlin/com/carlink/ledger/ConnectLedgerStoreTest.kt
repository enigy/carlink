package com.carlink.ledger

import com.carlink.ledger.ConnectRecord.Kind
import com.carlink.ledger.ConnectRecord.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** The ledger's file: kept across processes, capped, cleared, and a file it can't read discarded rather than a crash. */
class ConnectLedgerStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val warnings = CopyOnWriteArrayList<String>()
    private val opened = mutableListOf<ConnectLedgerStore>()

    private val file get() = File(tmp.root, ConnectLedgerStore.FILE_NAME)

    private fun store() = ConnectLedgerStore(file = { file }, warn = { warnings += it }).also { opened += it }

    private fun record(at: Long) = ConnectRecord(startedAtMs = at, kind = Kind.WAKE_LISTED, outcome = Outcome.PICTURE, endMs = 3_000, pictureMs = 3_000, starts = 1)

    @After
    fun closeAll() = opened.forEach { it.close() }

    @Test
    fun `a new store with no file is empty, and says so once read`() {
        val s = store()
        assertEquals(ConnectLedger(), s.snapshot())
        assertEquals(ConnectLedger(), s.ledger.value)
        assertFalse(file.exists())
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `connects survive into a new store, as a new process finds them`() {
        val a = store()
        a.add(record(1))
        a.add(record(2))
        assertEquals(2, a.snapshot().records.size)
        a.close()

        val b = store()
        assertEquals(listOf(1L, 2L), b.snapshot().records.map { it.startedAtMs })
        assertEquals(b.snapshot(), b.ledger.value)
    }

    @Test
    fun `the file keeps the last 200`() {
        val a = store()
        for (i in 1..205) a.add(record(i.toLong()))
        // Every write done before the next store reads: close() waits only 2 s for them, which 205 synced writes overran
        // on a loaded Mac (16 Nov), and the next store then deleted the one in flight as a power cut's.
        assertEquals(200, a.snapshot().records.size)
        a.close()
        val back = store().snapshot()
        assertEquals(200, back.records.size)
        assertEquals(6L, back.records.first().startedAtMs)
        assertEquals(205L, back.records.last().startedAtMs)
    }

    @Test
    fun `a file that isn't a ledger is discarded and the ledger starts over`() {
        for (garbage in listOf("not json at all", "{\"v\":1,\"connects\":[", "{\"v\":99,\"connects\":[]}", "")) {
            file.writeText(garbage)
            val s = store()
            assertEquals(garbage, ConnectLedger(), s.snapshot())
            assertFalse("$garbage: deleted", file.exists())
            s.add(record(7))
            s.close()
            assertEquals(garbage, listOf(7L), store().snapshot().records.map { it.startedAtMs })
            file.delete()
        }
        assertEquals(4, warnings.count { it.contains("starts over") })
    }

    @Test
    fun `a file far larger than a ledger can be is not read`() {
        file.writeBytes(ByteArray((ConnectLedgerStore.MAX_FILE_BYTES + 1).toInt()) { ' '.code.toByte() })
        assertEquals(ConnectLedger(), store().snapshot())
        assertFalse(file.exists())
    }

    @Test
    fun `binary junk in the file is no crash`() {
        file.writeBytes(ByteArray(4_096) { (it * 31).toByte() })
        assertEquals(ConnectLedger(), store().snapshot())
    }

    @Test
    fun `a temp file a power cut left behind is cleaned up and the whole file read`() {
        val a = store()
        a.add(record(1))
        a.close()
        File(file.path + ".tmp").writeText("{\"v\":1,\"conn")
        val s = store()
        assertEquals(listOf(1L), s.snapshot().records.map { it.startedAtMs })
        assertFalse(File(file.path + ".tmp").exists())
    }

    @Test
    fun `clear deletes them all and says how many went`() {
        val s = store()
        s.add(record(1))
        s.add(record(2))
        assertEquals(2, s.clear())
        assertEquals(ConnectLedger(), s.snapshot())
        assertEquals(ConnectLedger(), s.ledger.value)
        assertFalse(file.exists())
        assertEquals(0, s.clear())
    }

    @Test
    fun `a store that can't write keeps the connect in memory and reports it`() {
        // Its folder is a file: nothing can be written there.
        val blocked = File(tmp.root, "blocked").apply { writeText("x") }
        val s = ConnectLedgerStore(file = { File(blocked, ConnectLedgerStore.FILE_NAME) }, warn = { warnings += it }).also { opened += it }
        s.add(record(1))
        assertEquals(listOf(1L), s.snapshot().records.map { it.startedAtMs })
        assertTrue(warnings.any { it.contains("not written") || it.contains("not replaced") })
    }
}
