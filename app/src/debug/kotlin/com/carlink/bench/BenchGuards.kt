package com.carlink.bench

import com.carlink.CarlinkManager
import com.carlink.DisplayOff
import java.net.InetAddress
import java.net.InetSocketAddress

/** What [BenchReceiver] accepts, as plain functions so a test reaches them (the Mac's 14 Nov review, D3). */
internal object BenchGuards {
    const val MAX_LINES = 1000
    const val MIN_GRACE_MS = 1000L

    private val MAC = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")
    private val FLAG = Regex("^bench_[a-z0-9_]+$")
    private val IPV4 = Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")
    private const val MAX_PORT = 65535
    private const val MAX_OCTET = 255
    private val LOGS = mapOf("box" to "/tmp/box.log", "wl" to "/tmp/wl.log", "carplayd_wl" to "/tmp/carplayd_wl.log")

    /** A box flag Carlink may write: /tmp/bench_* only. */
    fun isFlag(name: String): Boolean = FLAG.matches(name)

    /** The order as lower-case addresses, or null when it is empty, names a phone twice or holds anything else. */
    fun order(macs: String): List<String>? {
        val order = macs.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return order.takeIf { it.isNotEmpty() && it.all(MAC::matches) && it.distinct().size == it.size }
    }

    /**
     * The adapter's Wi-Fi address for `wifi_addr`: an IPv4 address (a literal, so nothing is looked up) and a port, or
     * null when either isn't one.
     */
    fun wifiAddress(
        host: String,
        port: Int,
    ): InetSocketAddress? {
        if (!IPV4.matches(host) || port !in 1..MAX_PORT) return null
        if (host.split('.').any { it.toInt() > MAX_OCTET }) return null
        return InetSocketAddress(InetAddress.getByName(host), port)
    }

    /** The box log a name stands for, or null. */
    fun logPath(file: String): String? = LOGS[file]

    /** The names box_log takes. */
    val logNames: Set<String> get() = LOGS.keys

    /** The display-off wait for [ms]: 0 (or less) puts back the 30 s, anything else at least [MIN_GRACE_MS]. */
    fun graceMs(ms: Int): Long = if (ms > 0) ms.toLong().coerceAtLeast(MIN_GRACE_MS) else DisplayOff.GRACE_MS

    /** A flicker tests a wake only from dormant: DISCONNECTED after the display-off wait ended the session. */
    fun flickerAllowed(
        state: CarlinkManager.State,
        dormant: Boolean,
    ): Boolean = state == CarlinkManager.State.DISCONNECTED && dormant
}
