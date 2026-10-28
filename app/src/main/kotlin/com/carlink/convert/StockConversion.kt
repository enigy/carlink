package com.carlink.convert

import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.ocbm.RawBulkTransport
import com.carlink.protocol.AdapterConfig
import com.carlink.protocol.MessageSerializer
import com.carlink.protocol.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where converting a stock adapter stands, for the connecting screen ([StockConversion.phase]). */
sealed interface ConversionPhase {
    /** No stock adapter in hand. */
    data object Idle : ConversionPhase

    /** A stock adapter in a build with the switch off: pointed at the stick guide, never opened. */
    data class StickOnly(
        val usbId: String,
    ) : ConversionPhase

    /** Asking the adapter for its firmware version and product type. */
    data class Reading(
        val usbId: String,
    ) : ConversionPhase

    /** What the adapter said, and what can be offered it. */
    data class Offered(
        val usbId: String,
        val info: StockInfo,
        val offer: Offer,
    ) : ConversionPhase

    /** Getting the installer; [total] is 0 until its size is known (a bench copy is read at once). */
    data class Downloading(
        val done: Long,
        val total: Long,
    ) : ConversionPhase

    data class Sending(
        val done: Long,
        val total: Long,
    ) : ConversionPhase

    /** The adapter has the installer: its updater runs it and restarts the adapter. */
    data object Installing : ConversionPhase

    /** The adapter left USB after taking the installer; waiting for it to come back. */
    data object Restarting : ConversionPhase

    data class Failed(
        val title: String,
        val body: String,
    ) : ConversionPhase

    /**
     * "Not now", or OK on a message: nothing more is said about this adapter until its link ends (it was
     * unplugged), an OCBM adapter connects, or the app restarts.
     */
    data object Declined : ConversionPhase
}

/**
 * Converting a stock adapter to OCBM from the app (OCBM one-file install M5 part 3, decisions of 10-28).
 *
 * The manager hands over each stock adapter it finds on USB ([claim]). In a build with the switch off
 * ([conversionOn]; off in release builds) the adapter is only recognised by its USB id and the driver
 * is pointed at the stick guide: nothing is opened. With it on, the manager opens the adapter and the
 * conversion speaks Carlinkit's protocol on it ([attach]): the firmware version (0xCC) and BoxSettings'
 * product type (0x19) decide the [Offer]. On "Convert" the installer is obtained and checked
 * ([ImageSupplier]) and sent with SendFile to [InstallerImage.SEND_PATH]; the adapter's own updater runs
 * it and restarts the adapter, which ends the link. It comes back as an OCBM adapter (1314:2d00) in its
 * trial, the manager drops the conversion ([reset]) and the trial screen takes over.
 *
 * The link is held, heartbeat and all, while the adapter is on screen or declined, so the adapter's
 * watchdog does not reset it under the driver; it is let go when the link ends or [reset] runs.
 */
class StockConversion(
    private val images: ImageSupplier,
    private val conversionOn: Boolean,
    private val scope: CoroutineScope,
    private val searchAgain: () -> Unit,
    private val timing: Timing = Timing(),
) {
    /** How long each wait lasts. */
    data class Timing(
        /** For the firmware version and BoxSettings after Open. */
        val readMs: Long = 8_000,
        val heartbeatMs: Long = 2_000,
        /** For the adapter to restart once it has the installer (the plan says about 90 s). */
        val installMs: Long = 240_000,
        /** For it to come back on USB after leaving. */
        val restartMs: Long = 300_000,
        /** Between searches while it is away, in case its attach broadcast is missed. */
        val searchMs: Long = 45_000,
    )

    private val phaseState = MutableStateFlow<ConversionPhase>(ConversionPhase.Idle)

    /** What the connecting screen shows for a stock adapter. */
    val phase: StateFlow<ConversionPhase> = phaseState.asStateFlow()

    /** Where a debug build reads its bench copy of the installer, for the screen that asks for one. */
    val benchFolder: String? get() = images.benchFolder

    private val lock = Any()
    private var link: StockLink? = null
    private var usbId = ""
    private var info = StockInfo()

    // The installer went out and no OCBM adapter has come back since: a stock adapter now is that one, unconverted.
    private var installerSent = false

    // The one timer running (reading, installing or restarting); only touched under [lock].
    private var wait: Job? = null

    /**
     * A stock adapter ([usbId], "1314:1520") is on USB: true when the manager should open it and hand the
     * link to [attach]. False while a link is held, while a failure or a "Not now" stands, and in a build
     * with the switch off, which shows the stick guide instead.
     */
    fun claim(usbId: String): Boolean =
        synchronized(lock) {
            val now = phaseState.value
            when {
                link != null || now is ConversionPhase.Failed || now == ConversionPhase.Declined -> false
                !conversionOn -> {
                    phaseState.value = ConversionPhase.StickOnly(usbId)
                    false
                }
                else -> true
            }
        }

    /** The manager opened the adapter [claim] asked for: Open it and ask what it is. */
    fun attach(
        transport: RawBulkTransport,
        usbId: String,
    ) {
        lateinit var l: StockLink
        l = StockLink(transport, scope, timing.heartbeatMs, ::onFrame) { onLinkLost(l) }
        synchronized(lock) {
            link = l
            this.usbId = usbId
            info = StockInfo()
            phaseState.value = ConversionPhase.Reading(usbId)
        }
        logInfo("[CONVERT] stock adapter $usbId: asking its firmware version and model", tag = Logger.Tags.ADAPTR)
        if (!l.start()) logWarn("[CONVERT] Open did not go out to $usbId", tag = Logger.Tags.ADAPTR)
        synchronized(lock) {
            if (link !== l) return
            wait?.cancel()
            wait =
                scope.launch {
                    delay(timing.readMs)
                    settle()
                }
        }
    }

    /** The driver confirmed "Convert this adapter now?": get the installer, check it, send it. */
    fun convert() {
        val l =
            synchronized(lock) {
                val now = phaseState.value
                val held = link
                if (now !is ConversionPhase.Offered || now.offer != Offer.CONVERT || held == null) return
                phaseState.value = ConversionPhase.Downloading(0, 0)
                held
            }
        logWarn("[CONVERT] converting $usbId: confirmed in the app", tag = Logger.Tags.ADAPTR)
        scope.launch(Dispatchers.IO) { install(l) }
    }

    /** "Check again" on "No installer to send": the bench copy may be there now. */
    fun recheck() {
        val ready = images.available()
        synchronized(lock) {
            val now = phaseState.value
            if (ready && now is ConversionPhase.Offered && now.offer == Offer.NO_IMAGE) {
                phaseState.value = now.copy(offer = offerFor(now.info, imageReady = true))
            }
        }
    }

    /**
     * "Not now", or OK on a message. The adapter stays quiet ([ConversionPhase.Declined]) until it leaves;
     * after a failure with no adapter in hand the manager looks again, so a returned adapter is offered anew.
     */
    fun dismiss() {
        val lookAgain =
            synchronized(lock) {
                val now = phaseState.value
                if (now !is ConversionPhase.Offered && now !is ConversionPhase.StickOnly && now !is ConversionPhase.Failed) return
                val gone = link == null && now is ConversionPhase.Failed
                phaseState.value = if (gone) ConversionPhase.Idle else ConversionPhase.Declined
                gone
            }
        logInfo("[CONVERT] dismissed${if (lookAgain) "; looking for the adapter again" else ""}", tag = Logger.Tags.ADAPTR)
        if (lookAgain) searchAgain()
    }

    /**
     * Forget the stock adapter and let go of its link: an OCBM adapter is in hand (the converted one, back
     * in its trial, or another), or the manager is being released.
     */
    fun reset() {
        val l =
            synchronized(lock) {
                val held = link
                link = null
                installerSent = false
                phaseState.value = ConversionPhase.Idle
                wait?.cancel()
                held
            }
        l?.close()
    }

    /** A frame from the adapter, on the read thread: the two answers [settle] waits for. */
    private fun onFrame(
        type: Int,
        payload: ByteArray,
    ) {
        val answered =
            synchronized(lock) {
                info =
                    when (type) {
                        MessageType.SOFTWARE_VERSION.id -> info.copy(firmware = StockInfo.firmwareOf(payload) ?: info.firmware)
                        MessageType.BOX_SETTINGS.id -> info.copy(product = StockInfo.productOf(payload) ?: info.product)
                        else -> return
                    }
                info.complete && phaseState.value is ConversionPhase.Reading
            }
        if (answered) settle()
    }

    /** Asking is over (both answers in, or [Timing.readMs] passed): what the adapter is and what it is offered. */
    private fun settle() {
        val ready = images.available()
        val next =
            synchronized(lock) {
                if (phaseState.value !is ConversionPhase.Reading) return
                val p = if (installerSent) stillStock(info) else ConversionPhase.Offered(usbId, info, offerFor(info, ready))
                installerSent = false
                phaseState.value = p
                wait?.cancel()
                p
            }
        logInfo("[CONVERT] stock adapter $usbId: ${info.product ?: "model unknown"}, firmware ${info.firmware ?: "unknown"}: $next", tag = Logger.Tags.ADAPTR)
    }

    private suspend fun install(l: StockLink) {
        val bytes =
            images.obtain { done, total -> if (link === l) phaseState.value = ConversionPhase.Downloading(done, total) }.getOrElse {
                logWarn("[CONVERT] no installer: ${it.message}", tag = Logger.Tags.ADAPTR)
                if (link === l) phaseState.value = ConversionPhase.Failed("Couldn't get the installer", it.message ?: "No reason was given.")
                return
            }
        if (link !== l) return
        val message = MessageSerializer.serializeFile(InstallerImage.SEND_PATH, bytes)
        val started = System.nanoTime()
        phaseState.value = ConversionPhase.Sending(0, message.size.toLong())
        val sent = l.send(message) { done, total -> if (link === l) phaseState.value = ConversionPhase.Sending(done, total) }
        val ms = (System.nanoTime() - started) / NANOS_PER_MS
        if (!sent) {
            logWarn("[CONVERT] SendFile stopped after $ms ms", tag = Logger.Tags.ADAPTR)
            if (link === l) phaseState.value = ConversionPhase.Failed(SEND_FAILED, "A write to the adapter failed. Unplug it and plug it back in to try again.")
            return
        }
        logWarn("[CONVERT] installer sent: ${bytes.size} B to ${InstallerImage.SEND_PATH} in $ms ms; the adapter's updater has it", tag = Logger.Tags.ADAPTR)
        synchronized(lock) {
            if (link !== l) return
            installerSent = true
            phaseState.value = ConversionPhase.Installing
            wait?.cancel()
            wait =
                scope.launch {
                    delay(timing.installMs)
                    val minutes = timing.installMs / MS_PER_MINUTE
                    synchronized(lock) {
                        if (link !== l || phaseState.value != ConversionPhase.Installing) return@launch
                        installerSent = false
                        phaseState.value = ConversionPhase.Failed(NOT_CONVERTED, "It is still running its Carlinkit firmware $minutes minutes after taking the installer.")
                    }
                    logWarn("[CONVERT] no restart ${timing.installMs / 1000} s after the installer", tag = Logger.Tags.ADAPTR)
                }
        }
    }

    /** The link ended: the adapter restarting, as it should once it has the installer, or unplugged. */
    private fun onLinkLost(l: StockLink) {
        val next =
            synchronized(lock) {
                if (link !== l) return
                link = null
                val now = phaseState.value
                // All of it went out: the updater can restart the adapter before Installing is even set.
                val allSent = now is ConversionPhase.Sending && now.total > 0 && now.done >= now.total
                val p =
                    when {
                        now == ConversionPhase.Installing || allSent -> ConversionPhase.Restarting
                        now is ConversionPhase.Sending -> ConversionPhase.Failed(SEND_FAILED, SEND_CUT)
                        else -> ConversionPhase.Idle
                    }
                phaseState.value = p
                wait?.cancel()
                if (p == ConversionPhase.Restarting) {
                    installerSent = true
                    wait =
                        scope.launch {
                            // Search now and then while it is away, in case its attach broadcast is missed.
                            var waited = 0L
                            while (waited + timing.searchMs < timing.restartMs) {
                                delay(timing.searchMs)
                                waited += timing.searchMs
                                if (phaseState.value != ConversionPhase.Restarting) return@launch
                                searchAgain()
                            }
                            delay(timing.restartMs - waited)
                            val gone = "It left USB ${timing.restartMs / MS_PER_MINUTE} minutes ago to install OCBM. Unplug it and plug it back in."
                            synchronized(lock) {
                                if (phaseState.value != ConversionPhase.Restarting) return@launch
                                installerSent = false
                                phaseState.value = ConversionPhase.Failed("The adapter hasn't come back", gone)
                            }
                            logWarn("[CONVERT] the adapter has not come back after ${timing.restartMs / 1000} s", tag = Logger.Tags.ADAPTR)
                        }
                }
                p
            }
        logInfo("[CONVERT] link to $usbId ended: $next", tag = Logger.Tags.ADAPTR)
        l.close() // the bridge session outlives the read loop
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val MS_PER_MINUTE = 60_000L
        const val SEND_FAILED = "The installer didn't finish sending"
        const val SEND_CUT = "The adapter left USB before it had the whole installer. Plug it back in to try again."
        const val NOT_CONVERTED = "The adapter didn't convert"

        /** The adapter that took the installer came back on Carlinkit firmware. */
        fun stillStock(info: StockInfo): ConversionPhase.Failed =
            ConversionPhase.Failed(
                NOT_CONVERTED,
                "It restarted on its Carlinkit firmware${info.firmware?.let { " $it" }.orEmpty()}, so the installer refused it or didn't finish.",
            )
    }
}

/**
 * Carlinkit's protocol on a stock adapter, as much as converting it needs: Open and the heartbeat as
 * Carlink2 sent them, the frames that come back, and one SendFile. The heartbeat and SendFile's slices
 * take turns on [writing], so a heartbeat never lands inside the installer.
 */
internal class StockLink(
    private val transport: RawBulkTransport,
    private val scope: CoroutineScope,
    private val heartbeatMs: Long,
    onFrame: (type: Int, payload: ByteArray) -> Unit,
    private val onLost: () -> Unit,
) {
    private val frames = StockFrames(onFrame)
    private val writing = Mutex()
    private var heartbeat: Job? = null

    @Volatile private var open = false

    /**
     * Start reading, then send Open; false if Open did not go out. The heartbeat's timer starts with it
     * and first fires [heartbeatMs] later (Carlink2's heartbeat_analysis.md): the adapter resets a link
     * that has had no heartbeat for 10 to 15 s.
     */
    fun start(): Boolean {
        open = true
        transport.setReadHandler { buf, n -> frames.push(buf, n) }
        transport.setDeadHandler { if (open) onLost() }
        transport.start()
        heartbeat =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(heartbeatMs)
                    writing.withLock { transport.writeBulk(MessageSerializer.serializeHeartbeat()) }
                }
            }
        return transport.writeBulk(MessageSerializer.serializeOpen(AdapterConfig.DEFAULT))
    }

    /** Write [message] in slices, reporting the bytes written; false if a write failed or the link closed. */
    suspend fun send(
        message: ByteArray,
        progress: (done: Long, total: Long) -> Unit,
    ): Boolean =
        writing.withLock {
            var at = 0
            while (at < message.size && open) {
                val end = minOf(at + SLICE, message.size)
                if (!transport.writeBulk(message.copyOfRange(at, end))) break
                at = end
                progress(at.toLong(), message.size.toLong())
            }
            at == message.size
        }

    /** Stop the heartbeat and let go of the adapter. Blocks while the transport winds down. */
    fun close() {
        open = false
        heartbeat?.cancel()
        transport.stop()
    }

    private companion object {
        /** One step of the send's progress: four of the bridge's 16 KiB writes. */
        const val SLICE = 64 * 1024
    }
}
