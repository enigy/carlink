package com.enigy.bridgeprobe

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.enigy.carlink2.bridge.BridgeContract
import com.enigy.carlink2.bridge.ICarlinkBridge
import com.enigy.carlink2.bridge.IUsbBridgeSession
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Bench probe for bridge API 6: "another app". Binds the bridge, shows its API and the short status an approved app
 * gets, opens a streaming session on the adapter, sends OCBM CT_HELLO and says whether CT_HELLO_ACK came back, and
 * shows every refusal and end reason the bridge gives. Its first call records it as a request in Carlink › Settings ›
 * Bridge Configuration (with other apps' access on).
 */
class ProbeActivity : Activity() {
    private lateinit var log: TextView
    private var bridge: ICarlinkBridge? = null
    private var session: IUsbBridgeSession? = null
    private val client = Binder()

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                binder: IBinder,
            ) {
                val b = ICarlinkBridge.Stub.asInterface(binder)
                bridge = b
                say("bound ${name.flattenToShortString()}, API ${runCatching { b.apiVersion }.getOrElse { "? (${it.message})" }}")
            }

            override fun onServiceDisconnected(name: ComponentName) {
                bridge = null
                say("bridge process died")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = TextView(this).apply { textSize = 18f }
        val buttons =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button("Bind") { bind() })
                addView(button("Status") { call("status") { it.status } })
                addView(button("Open") { open() })
                addView(button("HELLO") { hello() })
                addView(button("Why ended") { session?.let { s -> say("end reason: ${runCatching { s.endReason }.getOrElse { "refused: ${it.message}" } ?: "still open"}") } })
                addView(button("Close") { session?.let { s -> runCatching { s.close() } }.also { say("closed") } })
            }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 32, 32, 32)
                addView(buttons)
                addView(ScrollView(context).apply { addView(log) })
            },
        )
        bind()
    }

    override fun onDestroy() {
        runCatching { session?.close() }
        runCatching { unbindService(connection) }
        super.onDestroy()
    }

    private fun button(
        label: String,
        onClick: () -> Unit,
    ) = Button(this).apply {
        text = label
        textSize = 20f
        setOnClickListener { thread { onClick() } }
    }

    private fun bind() {
        val intent = Intent().setComponent(ComponentName(BridgeContract.BRIDGE_PACKAGE, BridgeContract.BRIDGE_SERVICE_CLASS))
        say(if (bindService(intent, connection, Context.BIND_AUTO_CREATE)) "binding…" else "bindService refused: is the bridge installed?")
    }

    private fun call(
        what: String,
        block: (ICarlinkBridge) -> Any?,
    ) {
        val b = bridge ?: return say("not bound")
        say("$what: " + runCatching { block(b) }.getOrElse { "refused (${it.javaClass.simpleName}): ${it.message}" })
    }

    private fun open() {
        val b = bridge ?: return say("not bound")
        val usb = getSystemService(UsbManager::class.java)
        val adapter = usb.deviceList.values.firstOrNull { BridgeContract.isAdapter(it.vendorId, it.productId) } ?: return say("no adapter on USB")
        val opened =
            runCatching { b.openSession(adapter.deviceName, false, false, client) }
                .getOrElse { return say("openSession refused (${it.javaClass.simpleName}): ${it.message}") }
        if (opened == null) return say("openSession: null (not handed over, or not an adapter). Status: ${runCatching { b.status }.getOrNull()}")
        session = opened
        say("session open on ${adapter.deviceName}")
        val stream = opened.takeStream() ?: return say("no stream")
        thread { readAcks(stream) }
    }

    private fun hello() {
        val s = session ?: return say("no session")
        // CH_CTRL, SOM|EOM: CT_HELLO, protocol version 1, instance nonce 0 ("not supplied").
        val frame = frame(channel = 0, payload = byteArrayOf(CT_HELLO, 1, 0, 0, 0, 0))
        val wrote = runCatching { s.write(frame, WRITE_TIMEOUT_MS) }.getOrElse { return say("write refused: ${it.message}") }
        say("CT_HELLO: wrote $wrote of ${frame.size} B")
    }

    /** Scan the raw stream for OCBM frames; report CT_HELLO_ACK. */
    private fun readAcks(stream: ParcelFileDescriptor) {
        val input = FileInputStream(stream.fileDescriptor)
        val buf = ByteArray(BridgeContract.READ_CHUNK_BYTES)
        var pending = ByteArray(0)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                pending += buf.copyOf(n)
                var i = 0
                while (pending.size - i >= HDR_LEN) {
                    if (le32(pending, i) != MAGIC) {
                        i++
                        continue
                    }
                    val len = le32(pending, i + 4)
                    if (pending.size - i < HDR_LEN + len) break
                    val channel = (pending[i + 8].toInt() and 0xFF) or ((pending[i + 9].toInt() and 0xFF) shl 8)
                    if (channel == 0 && len > 0 && pending[i + HDR_LEN] == CT_HELLO_ACK) say("CT_HELLO_ACK came back ($len B)")
                    i += HDR_LEN + len
                }
                pending = pending.copyOfRange(i, pending.size)
            }
        } catch (e: java.io.IOException) {
            say("stream: ${e.message}")
        }
        say("stream EOF; end reason: ${runCatching { session?.endReason }.getOrElse { "refused: ${it.message}" }}")
        runCatching { stream.close() }
    }

    private fun say(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        runOnUiThread { log.text = "$stamp $line\n${log.text}" }
    }

    private companion object {
        const val MAGIC = 0x4F43424D
        const val HDR_LEN = 16
        const val CT_HELLO: Byte = 0x01
        const val CT_HELLO_ACK: Byte = 0x02
        const val WRITE_TIMEOUT_MS = 2_000

        fun le32(
            b: ByteArray,
            off: Int,
        ): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

        /** One OCBM frame: header (magic, length, channel, flags SOM|EOM, XOR check, seq 0), then the payload. */
        fun frame(
            channel: Int,
            payload: ByteArray,
        ): ByteArray {
            val out = ByteArray(HDR_LEN + payload.size)
            for (k in 0 until 4) out[k] = (MAGIC ushr (8 * k)).toByte()
            for (k in 0 until 4) out[4 + k] = (payload.size ushr (8 * k)).toByte()
            out[8] = channel.toByte()
            out[9] = (channel ushr 8).toByte()
            out[10] = 0x03
            var check = 0
            for (k in 0 until 11) check = check xor (out[k].toInt() and 0xFF)
            out[11] = check.toByte()
            payload.copyInto(out, HDR_LEN)
            return out
        }
    }
}
