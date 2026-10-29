package com.carlink.convert

import com.carlink.BuildConfig
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
import me.jahnen.libaums.core.fs.FileSystem

/** Where converting a stock adapter stands, for the connecting screen ([StockConversion.phase]). */
sealed interface ConversionPhase {
    /**
     * Past "Convert": the installer is being got, or is on its way to the adapter on the stick. The stock
     * link may end meanwhile: a stick going into the adapter takes it off USB as the adapter.
     */
    sealed interface Converting : ConversionPhase

    /** No stock adapter in hand. */
    data object Idle : ConversionPhase

    /**
     * A shared stick that looks like an adapter's ([SharedStick.looksLikeAdapterShare]), with no stock
     * adapter in hand: most likely the adapter, started with a stick in it, which then never comes up as
     * itself. Guidance only: the stick is never opened, let alone written, from here.
     */
    data class StickInFirst(
        val usbId: String,
    ) : ConversionPhase

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
    ) : Converting

    /** The installer is in hand: waiting for a FAT32 stick to go into the adapter, which then shares it over USB. */
    data object WaitingForStick : Converting

    /** The stick is in hand: opening it and seeing what is on it. */
    data object ReadingStick : Converting

    /**
     * The open stick asks a decision first: it holds the installer's last report ([report]) or an
     * A15W_Update.img already ([hasImage]), or the adapter came back sharing it after an install ([returned]).
     */
    data class StickReady(
        val report: StickReport?,
        val hasImage: Boolean,
        val returned: Boolean,
    ) : Converting {
        /** The report says this version was undone on the adapter before: writing it again takes OCBM_RETRY too. */
        val retry: Boolean get() = report?.undone == true
    }

    /** The stick can't take the installer. Fixed on a computer and put back into the adapter, it is read again. */
    data class StickProblem(
        val title: String,
        val body: String,
    ) : Converting

    data class WritingStick(
        val done: Long,
        val total: Long,
    ) : Converting

    /** Reading the installer back from the stick and checking its SHA-256. */
    data object CheckingStick : Converting

    /** The installer is on the stick: the adapter installs it when it starts with the stick in it. */
    data object ReplugAdapter : Converting

    /** The adapter left USB with the stick, to install; [hint] when it came back sharing the stick instead. */
    data class InstallingFromStick(
        val hint: String? = null,
    ) : Converting

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

/** What the conversion needs of the manager's USB side. */
interface ConversionUsb {
    /** The names of the attached devices whose ids [accepts] takes; readable without USB permission. */
    fun attached(accepts: (vendorId: Int, productId: Int) -> Boolean): Set<String>

    /** Look for the adapter again (back in its trial as 1314:2d00, or a stock one): in case an attach broadcast was missed. */
    fun searchAgain()

    /**
     * Open the stick [deviceName] through the bridge, as its one session, and hand it to
     * [StockConversion.stickOpened]: null when it can't be opened. Not through the adapter's connect path.
     */
    fun openStick(deviceName: String)
}

/**
 * Converting a stock adapter to OCBM from the app (OCBM one-file install M5 part 3), by the stick (29 Oct).
 *
 * The manager hands over each stock adapter it finds on USB ([claim]). In a build with the switch off
 * ([conversionOn]; off in release builds) the adapter is only recognised by its USB id and the driver
 * is pointed at the stick guide: nothing is opened. With it on, the manager opens the adapter and the
 * conversion speaks Carlinkit's protocol on it ([attach]): the firmware version (0xCC) and BoxSettings'
 * product type (0x19) decide the [Offer].
 *
 * On "Convert" the installer is obtained and checked ([ImageSupplier]) and the driver is asked for a
 * FAT32 stick in the adapter. A stock adapter with a stick and no installer on it shares the stick over
 * USB ([SharedStick]); only a shared stick that appears after "Convert" is taken ([wantsStick]), never
 * one outside this flow. The conversion also looks for it itself, in case its attach broadcast is missed.
 * It lets go of the stock adapter's link first: the bridge relays one session, and nothing but the
 * stick's own storage may use it from then on. The manager opens the stick through the bridge, apart
 * from its connect path, and hands it over ([stickOpened]); the bridge claims it from the head unit's
 * own mount. The conversion speaks USB mass storage to it ([BulkOnlyStorage]) and writes the installer
 * as the stock firmware's stick update ([StickFiles]): it runs as ROUTE=stick, the one route that also
 * takes the adapter's full flash backup to the stick. The driver then unplugs the adapter and plugs it
 * back in with the stick still in it. It comes back as an OCBM adapter (1314:2d00) in its trial, the
 * manager drops the conversion ([reset]) and the trial screen takes over; or it comes back sharing the
 * stick, and the installer's report on it says why.
 *
 * A conversion that ends without an OCBM adapter offers none again on its own ([gaveUp]) until an OCBM
 * adapter connects or the app restarts. The stock link is held, heartbeat and all, while the adapter is
 * on screen, so its watchdog does not reset it under the driver.
 */
@Suppress("TooManyFunctions") // a state machine: its entry points and the steps between its phases
class StockConversion(
    private val images: ImageSupplier,
    private val conversionOn: Boolean,
    private val scope: CoroutineScope,
    private val usb: ConversionUsb,
    private val timing: Timing = Timing(),
    /** Whether the emulator bench's stick (46f4:0001) is taken too: debug builds. */
    private val benchStick: Boolean = BuildConfig.DEBUG,
) {
    /** How long each wait lasts. */
    data class Timing(
        /** For the firmware version and BoxSettings after Open. */
        val readMs: Long = 8_000,
        val heartbeatMs: Long = 2_000,
        /** From the adapter leaving USB with the stick to its return as 1314:2d00; 5 minutes until a stick install is timed. */
        val deadlineMs: Long = 300_000,
        /** Between searches while it is away, in case its attach broadcast is missed. */
        val searchMs: Long = 45_000,
        /** Between looks for the stick, and at whether it has left USB once the installer is on it. */
        val pollMs: Long = 1_000,
        /**
         * From a stick's attach to opening it: the head unit's own driver probes a new stick and Android mounts
         * it, and the bridge's claim is best not made in the middle of that.
         */
        val openDelayMs: Long = 2_000,
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

    // A conversion ended without an OCBM adapter: none is offered again until one connects ([reset]) or the app restarts.
    private var gaveUp = false

    // The one timer running (reading, the replug or the install); only touched under [lock].
    private var wait: Job? = null

    // Looks for the stick past "Convert" ([watchForStick]); only touched under [lock].
    private var watch: Job? = null

    // The checked installer, from "Convert" on.
    private var image: ByteArray? = null

    // Shared sticks that are not the one asked for: those on USB at "Convert", and each one already taken.
    private val notStick = mutableSetOf<String>()

    // The stick being opened, and then the open stick while a decision on it is pending or the installer goes to it.
    private var stickName = ""
    private var stick: OpenStick? = null

    // The stick being read came back from the adapter after an install.
    private var returning = false

    // A stock adapter showed up while the adapter was away installing.
    private var sawStock = false

    // The shared stick [ConversionPhase.StickInFirst] is about, and those its guidance was dismissed for.
    private var strayName = ""
    private val strayDismissed = mutableSetOf<String>()

    /**
     * A stock adapter ([usbId], "1314:1520") is on USB: true when the manager should open it and hand the
     * link to [attach]. False while a link is held, past "Convert", while a failure or a "Not now" stands,
     * after a conversion that failed ([gaveUp]), and in a build with the switch off, which shows the stick
     * guide instead.
     */
    fun claim(usbId: String): Boolean =
        synchronized(lock) {
            val now = phaseState.value
            val quiet = gaveUp || now is ConversionPhase.Converting || now is ConversionPhase.Failed || now == ConversionPhase.Declined
            if (now is ConversionPhase.InstallingFromStick) sawStock = true
            when {
                link != null || quiet -> false
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
            startWait {
                delay(timing.readMs)
                settle()
            }
        }
    }

    /** The driver confirmed "Convert this adapter now?": get the installer and check it, then ask for the stick. */
    fun convert() {
        synchronized(lock) {
            val now = phaseState.value
            if (now !is ConversionPhase.Offered || now.offer != Offer.CONVERT || link == null) return
            phaseState.value = ConversionPhase.Downloading(0, 0)
            // The stick asked for is one that appears from here on: a shared stick already on USB is not this adapter's.
            notStick.clear()
            notStick += usb.attached(SharedStick::isAdapterStick)
        }
        logWarn("[CONVERT] converting $usbId: confirmed in the app", tag = Logger.Tags.ADAPTR)
        scope.launch(Dispatchers.IO) { obtain() }
    }

    /** "Check again" on "No installer": the bench copy may be there now. */
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
     * Whether the shared stick [deviceName] (by its ids) is the one the conversion asked for: one that
     * appeared after "Convert", while the conversion waits for the stick or for the adapter to come back
     * with it. Debug builds also take the emulator bench's stick, plugged in before "Convert" or after.
     */
    fun wantsStick(
        vendorId: Int,
        productId: Int,
        deviceName: String,
    ): Boolean = synchronized(lock) { waitsForStick(phaseState.value) && deviceName !in notStick && isStick(vendorId, productId) }

    /** A device attached (USB_DEVICE_ATTACHED): taken, off the caller's thread, when it is the stick [wantsStick] waits for. */
    fun stickAttached(
        vendorId: Int,
        productId: Int,
        deviceName: String,
    ) {
        if (wantsStick(vendorId, productId, deviceName)) scope.launch(Dispatchers.IO) { take(deviceName) }
    }

    /**
     * A shared stick on USB that the conversion didn't ask for ([wantsStick] is false). When it looks like an
     * adapter's share, with no stock adapter in hand and nothing else on screen, the driver is told to take
     * the stick out, so the adapter comes up alone first ([ConversionPhase.StickInFirst]). True while that
     * guidance stands for it, so the manager stops looking. Nothing opens the stick from here, ever.
     */
    fun strayStick(
        vendorId: Int,
        productId: Int,
        version: String?,
        deviceName: String,
    ): Boolean {
        val show =
            synchronized(lock) {
                val now = phaseState.value
                val free = now == ConversionPhase.Idle || now == ConversionPhase.Declined || now is ConversionPhase.StickInFirst
                when {
                    !conversionOn || !SharedStick.looksLikeAdapterShare(vendorId, productId, version) -> false
                    link != null || !free || deviceName in strayDismissed -> false
                    else -> {
                        strayName = deviceName
                        phaseState.value = ConversionPhase.StickInFirst(StockAdapters.usbId(vendorId, productId))
                        true
                    }
                }
            }
        if (show) logInfo("[CONVERT] $deviceName looks like an adapter's shared stick, with no stock adapter in hand: guidance only", tag = Logger.Tags.ADAPTR)
        return show
    }

    /** The stick is open through the bridge, or being opened: the bridge's one session is the stick's. */
    val holdsStick: Boolean
        get() =
            when (phaseState.value) {
                ConversionPhase.ReadingStick, ConversionPhase.CheckingStick -> true
                is ConversionPhase.StickReady, is ConversionPhase.WritingStick -> true
                else -> false
            }

    /**
     * The stick [take] asked the manager for, opened through the bridge; null when it couldn't be (the
     * manager logged why), or because the installed bridge is older than the stick needs ([bridgeTooOld]).
     */
    fun stickOpened(
        transport: StickTransport?,
        bridgeTooOld: Boolean = false,
    ) {
        if (transport == null) {
            val why = if (bridgeTooOld) BRIDGE_TOO_OLD else "The USB bridge couldn't open it. $PUT_BACK"
            moveOn<ConversionPhase.ReadingStick>(ConversionPhase.StickProblem(CANT_OPEN, why))
            return
        }
        val trace: ((String) -> Unit)? = if (BuildConfig.DEBUG) { line -> logInfo("[STICK] $line", tag = Logger.Tags.USB) } else null
        scope.launch(Dispatchers.IO) { readStick(BulkOnlyStorage(transport, trace = trace)) }
    }

    /** "Write the installer", or "Try again" after a report that this version was undone: write it, with OCBM_RETRY for a retry. */
    fun writeStick() {
        val (open, retry) =
            synchronized(lock) {
                val now = phaseState.value as? ConversionPhase.StickReady ?: return
                val s = stick ?: return
                val bytes = image ?: return
                if (now.returned && !now.retry) return
                phaseState.value = ConversionPhase.WritingStick(0, bytes.size.toLong())
                s to now.retry
            }
        logWarn("[CONVERT] writing the installer to the stick${if (retry) ", with OCBM_RETRY" else ""}: confirmed in the app", tag = Logger.Tags.ADAPTR)
        scope.launch(Dispatchers.IO) { writeTo(open, retry) }
    }

    /**
     * "Not now", "Cancel", or OK on a message. The stick is let go. With the adapter's link held it stays quiet
     * ([ConversionPhase.Declined]) until the adapter leaves; with no link (the stick took the adapter off USB)
     * the manager looks again, so the adapter is offered anew once the stick is out. After a conversion that
     * failed ([gaveUp]) it does not.
     */
    fun dismiss() {
        var open: OpenStick? = null
        val lookAgain =
            synchronized(lock) {
                val now = phaseState.value
                if (!dismissable(now)) return
                if (now is ConversionPhase.StickInFirst) {
                    strayDismissed += strayName // not shown again for this stick
                    phaseState.value = ConversionPhase.Idle
                    return@synchronized false
                }
                if (now is ConversionPhase.StickReady && now.returned) gaveUp = true
                open = stick
                stick = null
                wait?.cancel()
                val gone = link == null && !gaveUp && (now is ConversionPhase.Failed || now is ConversionPhase.Converting)
                phaseState.value = if (gone) ConversionPhase.Idle else ConversionPhase.Declined
                gone
            }
        open?.storage?.release()
        logInfo("[CONVERT] dismissed${if (lookAgain) "; looking for the adapter again" else ""}", tag = Logger.Tags.ADAPTR)
        if (lookAgain) usb.searchAgain()
    }

    /**
     * Forget the stock adapter, let go of its link and of the stick: an OCBM adapter is in hand (the converted
     * one, back in its trial, or another), or the manager is being released.
     */
    fun reset() {
        val (l, open) =
            synchronized(lock) {
                val held = link to stick
                link = null
                stick = null
                image = null
                gaveUp = false
                returning = false
                sawStock = false
                notStick.clear()
                strayDismissed.clear()
                phaseState.value = ConversionPhase.Idle
                wait?.cancel()
                watch?.cancel()
                held
            }
        open?.storage?.release()
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
                val p = ConversionPhase.Offered(usbId, info, offerFor(info, ready))
                phaseState.value = p
                wait?.cancel()
                p
            }
        logInfo("[CONVERT] stock adapter $usbId: ${info.product ?: "model unknown"}, firmware ${info.firmware ?: "unknown"}: $next", tag = Logger.Tags.ADAPTR)
    }

    private fun obtain() {
        val got = images.obtain { done, total -> moveOn<ConversionPhase.Downloading>(ConversionPhase.Downloading(done, total)) }
        val next =
            synchronized(lock) {
                if (phaseState.value !is ConversionPhase.Downloading) return
                image = got.getOrNull()
                val p: ConversionPhase =
                    got.exceptionOrNull()?.let { ConversionPhase.Failed("Couldn't get the installer", it.message ?: "No reason was given.") }
                        ?: ConversionPhase.WaitingForStick
                phaseState.value = p
                if (p == ConversionPhase.WaitingForStick) watchForStick()
                p
            }
        logInfo("[CONVERT] installer: ${got.getOrNull()?.let { "${it.size} B, checked" } ?: got.exceptionOrNull()?.message}; $next", tag = Logger.Tags.ADAPTR)
    }

    /**
     * Past "Convert", look for the stick now and then: it may have been plugged in first (the bench's), or
     * its attach broadcast missed. Ends with the conversion. Under [lock].
     */
    private fun watchForStick() {
        if (watch?.isActive == true) return
        watch =
            scope.launch(Dispatchers.IO) {
                while (phaseState.value is ConversionPhase.Converting) {
                    delay(timing.pollMs)
                    if (!waitsForStick(phaseState.value)) continue
                    usb.attached(::isStick).firstOrNull { name -> synchronized(lock) { name !in notStick } }?.let { take(it) }
                }
            }
    }

    /**
     * The stick [deviceName] is the one asked for: let go of the stock adapter's link, then, once the head
     * unit is done with the new stick ([Timing.openDelayMs]), have the manager open it. The bridge relays
     * one session, so nothing but the stick's storage may use it from here.
     */
    private suspend fun take(deviceName: String) {
        val dropped =
            synchronized(lock) {
                val now = phaseState.value
                if (!waitsForStick(now) || deviceName in notStick) return
                notStick += deviceName
                stickName = deviceName
                returning = now == ConversionPhase.ReplugAdapter || now is ConversionPhase.InstallingFromStick || (now is ConversionPhase.StickProblem && returning)
                wait?.cancel()
                phaseState.value = ConversionPhase.ReadingStick
                link.also { link = null }
            }
        logInfo("[CONVERT] taking the stick at $deviceName${if (returning) ", back from the adapter" else ""}", tag = Logger.Tags.ADAPTR)
        dropped?.close()
        delay(timing.openDelayMs)
        val still = synchronized(lock) { phaseState.value == ConversionPhase.ReadingStick && stickName == deviceName }
        if (still) usb.openStick(deviceName)
    }

    /** On the bridge's thread: open the stick and see what is on it, then write to it at once when it is empty. */
    private fun readStick(storage: BulkOnlyStorage) {
        val (bytes, returned) = synchronized(lock) { image to returning }
        val (next, fs) =
            runCatching { look(storage, bytes?.size ?: 0, returned) }
                .getOrElse { ConversionPhase.StickProblem(CANT_READ, "${reason(it)} $PUT_BACK") to null }
        val open = fs?.let { OpenStick(storage, it, synchronized(lock) { stickName }) }
        val taken =
            synchronized(lock) {
                if (phaseState.value != ConversionPhase.ReadingStick || bytes == null) return@synchronized false
                stick = open
                phaseState.value = next
                if (next is ConversionPhase.InstallingFromStick) startWait { awaitInstall() }
                true
            }
        if (!taken || open == null) storage.release()
        logInfo("[CONVERT] the stick: $next${if (taken) "" else " (no longer wanted)"}", tag = Logger.Tags.ADAPTR)
        if (taken && open != null && next is ConversionPhase.WritingStick) writeTo(open, retry = false)
    }

    /**
     * Open the stick's FAT32 volume and see what is on it: the next phase, and the file system when the stick
     * stays open for it. A stick with nothing on it to show or replace is written at once: "Convert" was the
     * go-ahead. What the head unit puts on a stick it mounts (LOST.DIR, Movies, Music, Pictures) is neither.
     */
    private fun look(
        storage: BulkOnlyStorage,
        imageBytes: Int,
        returned: Boolean,
    ): Pair<ConversionPhase, FileSystem?> {
        storage.init()
        val fs =
            when (val v = StickVolume.open(storage)) {
                is StickVolume.NotFat32 -> return ConversionPhase.StickProblem(NOT_FAT32, "It holds ${v.found}. $FORMAT_IT") to null
                is StickVolume.Fat32 -> v.fs
            }
        if (returned && StickFiles.imageWaiting(fs)) return ConversionPhase.InstallingFromStick(NOT_STARTED) to null
        val contents = StickFiles.inspect(fs)
        if (!returned) roomProblem(contents, imageBytes)?.let { return it to null }
        if (!returned && contents.report == null && !contents.hasImage) return ConversionPhase.WritingStick(0, imageBytes.toLong()) to fs
        return ConversionPhase.StickReady(contents.report, contents.hasImage, returned) to fs
    }

    /** On the bridge's thread: the installer onto the stick, read back and checked, then the stick let go. */
    private fun writeTo(
        open: OpenStick,
        retry: Boolean,
    ) {
        val bytes = synchronized(lock) { image } ?: return
        val started = System.nanoTime()
        val problem =
            runCatching {
                roomProblem(StickFiles.inspect(open.fs), bytes.size) ?: run {
                    StickFiles.write(open.fs, bytes, InstallerImage.sha256Hex(bytes), retry, ::writing)
                    open.storage.sync()
                    null
                }
            }.getOrElse { ConversionPhase.StickProblem(WRITE_FAILED, "${reason(it)} $PUT_BACK") }
        open.storage.release()
        val ms = (System.nanoTime() - started) / NANOS_PER_MS
        val next =
            synchronized(lock) {
                if (stick === open) stick = null
                val now = phaseState.value
                if (now !is ConversionPhase.WritingStick && now != ConversionPhase.CheckingStick) return
                val p = problem ?: ConversionPhase.ReplugAdapter
                phaseState.value = p
                if (problem == null) startWait { awaitReplug(open.name) }
                p
            }
        logWarn("[CONVERT] ${bytes.size} B to the stick in $ms ms${if (retry) " with OCBM_RETRY" else ""}: $next", tag = Logger.Tags.ADAPTR)
    }

    private fun writing(
        done: Long,
        total: Long,
    ) = moveOn<ConversionPhase.WritingStick>(if (done < total) ConversionPhase.WritingStick(done, total) else ConversionPhase.CheckingStick)

    /** The installer is on the stick: wait for the driver to unplug the adapter, which takes the stick off USB with it. */
    private suspend fun awaitReplug(name: String) {
        while (name in usb.attached { _, _ -> true }) delay(timing.pollMs)
        synchronized(lock) {
            if (phaseState.value != ConversionPhase.ReplugAdapter) return
            phaseState.value = ConversionPhase.InstallingFromStick()
        }
        logInfo("[CONVERT] the stick left USB with the adapter; ${timing.deadlineMs / MS_PER_SECOND} s for it to come back as OCBM", tag = Logger.Tags.ADAPTR)
        awaitInstall()
    }

    /**
     * The adapter is away with the stick: [Timing.deadlineMs] for it to come back as 1314:2d00, which ends
     * this through [reset], or sharing the stick, which [take] reads. The manager searches now and
     * then, in case an attach broadcast is missed. At the deadline the app gives up on this conversion.
     */
    private suspend fun awaitInstall() {
        var waited = 0L
        while (waited < timing.deadlineMs) {
            val step = minOf(timing.searchMs, timing.deadlineMs - waited)
            delay(step)
            waited += step
            if (phaseState.value !is ConversionPhase.InstallingFromStick) return
            if (waited < timing.deadlineMs) usb.searchAgain()
        }
        var dropped: StockLink? = null
        val failed =
            synchronized(lock) {
                if (phaseState.value !is ConversionPhase.InstallingFromStick) return
                val onStock = sawStock || link != null
                dropped = link
                link = null
                gaveUp = true
                val p = if (onStock) ConversionPhase.Failed(NOT_CONVERTED, STOCK_AGAIN) else ConversionPhase.Failed(NOT_BACK, notBack())
                phaseState.value = p
                p
            }
        dropped?.let { l -> scope.launch(Dispatchers.IO) { l.close() } }
        logWarn("[CONVERT] ${failed.title}: ${timing.deadlineMs / MS_PER_SECOND} s after it left with the stick; no conversion is offered again", tag = Logger.Tags.ADAPTR)
    }

    /** The link ended: the adapter unplugged, or a stick put into it, which takes it off USB as the adapter. */
    private fun onLinkLost(l: StockLink) {
        val next =
            synchronized(lock) {
                if (link !== l) return
                link = null
                if (phaseState.value !is ConversionPhase.Converting) {
                    phaseState.value = ConversionPhase.Idle
                    wait?.cancel()
                }
                phaseState.value
            }
        logInfo("[CONVERT] link to $usbId ended: $next", tag = Logger.Tags.ADAPTR)
        l.close() // the bridge session outlives the read loop
    }

    /** Waiting for a stick, or for the adapter to come back with it. */
    private fun waitsForStick(p: ConversionPhase): Boolean =
        when (p) {
            ConversionPhase.WaitingForStick, ConversionPhase.ReplugAdapter -> true
            is ConversionPhase.StickProblem, is ConversionPhase.InstallingFromStick -> true
            else -> false
        }

    private fun isStick(
        vendorId: Int,
        productId: Int,
    ): Boolean = SharedStick.isAdapterStick(vendorId, productId) || (benchStick && SharedStick.isBenchStick(vendorId, productId))

    /** A panel with a button that ends it: [dismiss] does something there. */
    private fun dismissable(p: ConversionPhase): Boolean =
        when (p) {
            is ConversionPhase.Offered, is ConversionPhase.StickOnly, is ConversionPhase.Failed, is ConversionPhase.StickInFirst -> true
            is ConversionPhase.StickProblem, is ConversionPhase.StickReady -> true
            ConversionPhase.WaitingForStick, ConversionPhase.ReplugAdapter -> true
            else -> false
        }

    private fun roomProblem(
        contents: StickContents,
        imageBytes: Int,
    ): ConversionPhase.StickProblem? =
        if (!StickFiles.tooFull(contents, imageBytes)) {
            null
        } else {
            val has = ((contents.freeBytes ?: 0) + contents.reclaimableBytes) / MIB
            val needs = (StickFiles.roomNeeded(imageBytes) + MIB - 1) / MIB
            ConversionPhase.StickProblem(
                TOO_FULL,
                "It has $has MB free; it needs $needs MB, for the installer and the adapter's flash backup. Make room on a computer, then put it back into the adapter.",
            )
        }

    /** Replace the phase with [next] while it is still a [T]: progress that must not undo a later step. */
    private inline fun <reified T : ConversionPhase> moveOn(next: ConversionPhase) {
        synchronized(lock) { if (phaseState.value is T) phaseState.value = next }
    }

    /** Start the one timer, ending the one before. Under [lock]. */
    private fun startWait(block: suspend () -> Unit) {
        wait?.cancel()
        wait = scope.launch { block() }
    }

    private fun notBack(): String =
        "It hasn't come back as an OCBM adapter in the ${timing.deadlineMs / MS_PER_MINUTE} minutes since it restarted with the stick. " +
            "Give it a few more minutes. If it still doesn't come back, unplug it and read OCBM_RESULT.txt on the stick with a computer."

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val MS_PER_SECOND = 1_000L
        const val MS_PER_MINUTE = 60_000L
        const val MIB = 1024L * 1024
        const val PUT_BACK = "Take the stick out of the adapter and put it back in to try again."
        const val BRIDGE_TOO_OLD =
            "The Carlink USB bridge on this head unit is too old to write to a stick. Install the bridge that comes with this " +
                "Carlink (1.0.6 or newer), then take the stick out of the adapter and put it back in."
        const val FORMAT_IT = "Format it as FAT32 on a computer, then put it back into the adapter."
        const val NOT_FAT32 = "The stick isn't FAT32"
        const val TOO_FULL = "The stick is too full"
        const val CANT_OPEN = "Carlink couldn't open the stick"
        const val CANT_READ = "Carlink couldn't read the stick"
        const val WRITE_FAILED = "The installer didn't go onto the stick"
        const val NOT_CONVERTED = "The adapter didn't convert"
        const val NOT_BACK = "The adapter hasn't come back"
        const val STOCK_AGAIN = "It came back on its Carlinkit firmware instead of OCBM. The installer's report, OCBM_RESULT.txt, is on the stick: read it with a computer."
        const val NOT_STARTED =
            "It came back sharing the stick instead of installing from it. Take the stick out of the adapter and put it back in, " +
                "leaving the adapter plugged in: it installs from a stick put in while it runs."

        /** An exception's message as a sentence. */
        fun reason(e: Throwable): String {
            val text = (e.message ?: e.javaClass.simpleName).replaceFirstChar { it.uppercase() }
            return if (text.endsWith(".")) text else "$text."
        }
    }
}

/** A stick open for the conversion: its USB mass storage, its FAT32 volume, and its USB device name. */
private class OpenStick(
    val storage: BulkOnlyStorage,
    val fs: FileSystem,
    val name: String,
)

/**
 * Carlinkit's protocol on a stock adapter, as much as the conversion needs: Open and the heartbeat as
 * Carlink2 sent them, and the frames that come back.
 */
internal class StockLink(
    private val transport: RawBulkTransport,
    private val scope: CoroutineScope,
    private val heartbeatMs: Long,
    onFrame: (type: Int, payload: ByteArray) -> Unit,
    private val onLost: () -> Unit,
) {
    private val frames = StockFrames(onFrame)
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
                    transport.writeBulk(MessageSerializer.serializeHeartbeat())
                }
            }
        return transport.writeBulk(MessageSerializer.serializeOpen(AdapterConfig.DEFAULT))
    }

    /** Stop the heartbeat and let go of the adapter. Blocks while the transport winds down. */
    fun close() {
        open = false
        heartbeat?.cancel()
        transport.stop()
    }
}
