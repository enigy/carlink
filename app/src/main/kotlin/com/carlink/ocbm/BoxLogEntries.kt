package com.carlink.ocbm

/**
 * One entry of the box's CH_LOG stream: a line from `/tmp/box.log` or one of the per-daemon logs
 * the box tails, stamped with the box's own clock (set from the head unit by CT_SETTIME).
 */
data class BoxLogEntry(
    val source: Int,
    val flags: Int,
    val seq: Int,
    val unixMs: Long,
    val text: String,
) {
    /** Source label as the box names it (`box`, `iap2d`, `airplayd`, `wl`, …). */
    val sourceName: String get() = BoxLogEntries.sourceName(source)
}

/**
 * CH_LOG frame decoding (`crates/ocbm-proto` `encode_log_entry`). A frame is entries back to back:
 * `[source u8][flags u8][seq u16 LE][unix_ms u64 LE][len u16 LE][text: len bytes]`. A drop report
 * (`LOG_F_DROPPED`) carries a u32 LE count of lines the box lost instead of text; it is rendered
 * as a line so the gap shows in the file instead of passing silently.
 */
object BoxLogEntries {
    private const val U32_LEN = 4

    fun parse(payload: ByteArray): List<BoxLogEntry> {
        val out = ArrayList<BoxLogEntry>()
        var i = 0
        while (i + Ocbm.LOG_ENTRY_HDR <= payload.size) {
            val source = payload[i].toInt() and 0xFF
            val flags = payload[i + 1].toInt() and 0xFF
            val seq = u16(payload, i + 2)
            val unixMs = u64(payload, i + 4)
            val len = u16(payload, i + 12)
            val start = i + Ocbm.LOG_ENTRY_HDR
            if (start + len > payload.size) break // torn frame: keep what decoded cleanly
            val text =
                if (flags and Ocbm.LOG_F_DROPPED != 0 && len >= U32_LEN) {
                    "… box dropped ${u32(payload, start)} line(s) here …"
                } else {
                    String(payload, start, len, Charsets.UTF_8).trimEnd('\n', '\r')
                }
            out += BoxLogEntry(source, flags, seq, unixMs, text)
            i = start + len
        }
        return out
    }

    /** Same names the box's `log_source_name` gives; an unknown id is shown, never dropped. */
    fun sourceName(id: Int): String =
        when (id) {
            Ocbm.LOG_SRC_BOX -> "box"
            Ocbm.LOG_SRC_AIRPLAYD -> "airplayd"
            Ocbm.LOG_SRC_AIRPLAYD_WL -> "airplayd-wl"
            Ocbm.LOG_SRC_IAP2D -> "iap2d"
            Ocbm.LOG_SRC_AA_BRIDGE -> "aa-bridge"
            Ocbm.LOG_SRC_RX_CONNECT -> "rx-connect"
            Ocbm.LOG_SRC_BT -> "bt"
            Ocbm.LOG_SRC_RADIO_AP_DHCP -> "radio_ap_dhcp"
            Ocbm.LOG_SRC_RADIO_BT_ATTACH -> "radio_bt_attach"
            Ocbm.LOG_SRC_RX_CONNECT_WL -> "rx-connect_wl"
            Ocbm.LOG_SRC_CARPLAY_WIRELESS -> "wl"
            Ocbm.LOG_SRC_INTERNAL -> "internal"
            else -> "src$id"
        }

    private fun u16(
        b: ByteArray,
        at: Int,
    ): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(
        b: ByteArray,
        at: Int,
    ): Long = (0 until U32_LEN).fold(0L) { acc, k -> acc or ((b[at + k].toLong() and 0xFF) shl (8 * k)) }

    private fun u64(
        b: ByteArray,
        at: Int,
    ): Long = (0 until 8).fold(0L) { acc, k -> acc or ((b[at + k].toLong() and 0xFF) shl (8 * k)) }
}
