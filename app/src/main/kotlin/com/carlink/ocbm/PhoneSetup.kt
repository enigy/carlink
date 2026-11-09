package com.carlink.ocbm

import android.os.SystemClock
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject

/**
 * What MGMT_INFO says about the adapter's Bluetooth: its [name] ("CarLink-xxxx") and the phones it
 * holds a bond for ([devices], read by ocbmd straight from the bond file on flash, so right even when
 * btd is not running). [devices] is null on a box that doesn't report them: unknown, not empty.
 */
data class PairedPhones(
    val name: String?,
    val devices: List<String>?,
) {
    /** Known to have no phone paired: the iPhone can neither reconnect nor be paged. */
    val none: Boolean get() = devices?.isEmpty() == true

    companion object {
        /** MGMT_INFO's JSON to its name and bonded phones; null when it isn't JSON. */
        fun parse(json: String): PairedPhones? {
            val info =
                try {
                    JSONObject(json)
                } catch (_: JSONException) {
                    return null
                }
            val macs = info.optJSONArray("devices")
            val devices = macs?.let { a -> (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { it.isNotBlank() } } }
            return PairedPhones(info.optString("name").takeIf { it.isNotBlank() }, devices)
        }
    }
}

/** What the connecting screen shows about pairing an iPhone ([PhoneSetup.phase]). */
sealed interface PhoneSetupPhase {
    /** Nothing: no session, phones are paired and the wait is young, a phone is connected, or the box doesn't say. */
    data object None : PhoneSetupPhase

    /** "Pair your iPhone": no phone is paired ([newPhone] false), or the driver asked to pair a new one. */
    data class Prompt(
        val name: String,
        val newPhone: Boolean,
    ) : PhoneSetupPhase

    /** The pairing window is open until [endsAtMs] ([PhoneSetup.Box.now]; null when the box pairs without one). */
    data class Pairing(
        val name: String,
        val endsAtMs: Long?,
        val progress: String? = null,
        val code: String? = null,
    ) : PhoneSetupPhase

    /** The window ran out with no phone, or the adapter didn't answer: "Try again". */
    data class NotPaired(
        val name: String,
        val title: String,
        val body: String,
    ) : PhoneSetupPhase

    /** A paired iPhone answered the adapter and reset the link twice: it has most likely forgotten the pairing (BTP_BOND_LOST). */
    data class PhoneForgot(
        val name: String,
    ) : PhoneSetupPhase

    /**
     * MGMT_PAIR_WINDOW came back non-zero while the adapter's Bluetooth may still be starting: about 15 s from
     * power-on (firmware ~10 s, attach ~13 s; the dev adapter in the truck, 5 Nov). The request is tried again until
     * btd answers or [PhoneSetup.Timing.btStartMs] runs out.
     */
    data class BluetoothStarting(
        val name: String,
    ) : PhoneSetupPhase

    /** MGMT_PAIR_WINDOW or SET_POLICY came back non-zero: btd is not reachable. */
    data class BluetoothDown(
        val name: String,
        val status: Int,
    ) : PhoneSetupPhase

    /**
     * "Waiting for phone" stays, with a way to pair: phones are paired but none came within
     * [PhoneSetup.Timing.waitMs] ([noPhone] false), or the driver put off pairing when none is ([noPhone] true).
     */
    data class Waiting(
        val name: String,
        val noPhone: Boolean,
    ) : PhoneSetupPhase
}

/**
 * Setting up an iPhone on an OCBM adapter, on the connecting screen (29 Oct bench, plan page v11 "Cases to
 * plan for"). After a conversion OCBM has no paired phone: its bonds live in /etc/carplay/bt_link_keys and
 * stock's stay with stock, while the iPhone still lists the adapter (same name, same address) with stock's
 * keys, so it neither reconnects nor offers to pair. "Waiting for phone" would wait for nothing.
 *
 * So each session reads MGMT_INFO ([onSubscribed]). No bonded phone: lead with "Pair your iPhone"
 * ([PhoneSetupPhase.Prompt]), which opens the pairing window ([pair]) and follows BT_PHASE and
 * PAIRING_CODE until a phone connects ([onPhoneConnected]) or the window runs out. Phones bonded but none
 * here within [Timing.waitMs]: keep waiting, with "Pair a new iPhone" beside it, since nothing removes the
 * bond file and a re-converted adapter can list a phone that has since forgotten it. A box that doesn't
 * report its phones keeps today's waiting. A non-zero MGMT_PAIR_WINDOW or SET_POLICY means btd isn't
 * reachable ([PhoneSetupPhase.BluetoothDown]): on 29 Oct a USB stick left in the adapter did that.
 */
@Suppress("TooManyFunctions") // a state machine: the session's events, the driver's buttons and the steps between
class PhoneSetup(
    private val box: Box,
    private val scope: CoroutineScope,
    private val timing: Timing = Timing(),
) {
    /** The adapter, as the pairing screen needs it. */
    interface Box {
        /** MGMT_GET_INFO's JSON; null with no session or no answer. */
        fun info(): String?

        /** Whether the box gates pairing behind MGMT_PAIR_WINDOW (CAP_PAIR_WINDOW); without it pairing is always open. */
        val gatesPairing: Boolean

        /** MGMT_PAIR_WINDOW for [seconds] (0 closes it): its status, null with no answer. */
        fun pairWindow(seconds: Int): Int?

        /** A monotonic clock in ms, for the window's end. */
        fun now(): Long = SystemClock.elapsedRealtime()

        /** Whether btd runs (CT_BOX_HEALTH BH_CARPLAY_WIRELESS); null before the box has said. */
        fun wirelessUp(): Boolean? = null

        /** Whether the box reports its pairing window open (BH_PAIR_WINDOW); null before the box has said. */
        fun windowOpen(): Boolean? = null
    }

    data class Timing(
        /** From the session's start to "Pair a new iPhone" beside "Waiting for phone", when phones are bonded. */
        val waitMs: Long = 60_000,
        /** How long "Pair iPhone" opens the window for. */
        val windowSeconds: Int = WINDOW_SECONDS,
        /** How long a refused pairing window is tried again, while the adapter's Bluetooth may still be starting. */
        val btStartMs: Long = 30_000,
        /** Between those tries, as SET_POLICY's after SUBSCRIBE. */
        val btRetryMs: Long = 2_000,
        /** After this app's own MGMT_FORGET_DEVICE: no BOND_LOST is taken as news, and a window waits for the restart. */
        val forgetQuietMs: Long = 30_000,
        /** How often a window waiting for that restart looks at the box's health. */
        val restartPollMs: Long = 500,
        /** If the box never shows btd going down within this of the forget, the restart was missed: open anyway. */
        val restartDownMs: Long = 10_000,
        /** After a window opened in the quiet time, look again this much later, and open it once more if it closed. */
        val reopenCheckMs: Long = 4_000,
    )

    private val phaseState = MutableStateFlow<PhoneSetupPhase>(PhoneSetupPhase.None)

    /** What the connecting screen shows about pairing. */
    val phase: StateFlow<PhoneSetupPhase> = phaseState.asStateFlow()

    private val lock = Any()
    private var phones: PairedPhones? = null

    // The adapter's name from its latest MGMT_INFO, whoever read it ([onAdapterName]); kept across sessions.
    private var adapterName: String? = null
    private var connected = false
    private var timer: Job? = null

    // [Box.now] when this app last sent MGMT_FORGET_DEVICE ([onForgetSent]); the box restarts wireless as part of it.
    private var forgotAtMs: Long? = null

    // Bumped by every window request ([window]: an open, Stop's close, Settings' close): a reopen is only for the window
    // the driver last asked for ([reopenIfClosed]).
    private var windowGen = 0

    /** The adapter is known to have no phone paired: nothing to page. */
    val noPhonePaired: Boolean get() = synchronized(lock) { phones?.none == true }

    /** A session is up (after CT_SUBSCRIBE): read the adapter's phones and choose what to show. */
    fun onSubscribed() {
        synchronized(lock) {
            phones = null
            connected = false
            timer?.cancel()
            phaseState.value = PhoneSetupPhase.None
        }
        scope.launch(Dispatchers.IO) { read() }
    }

    /** A phone's session is keyed: pairing worked, or a paired phone came. Read the phones again, so the new one is listed. */
    fun onPhoneConnected() {
        val was =
            synchronized(lock) {
                connected = true
                timer?.cancel()
                phaseState.value.also { phaseState.value = PhoneSetupPhase.None }
            }
        if (was is PhoneSetupPhase.Pairing) logInfo("[PAIRING] a phone connected while pairing was open", tag = Logger.Tags.ADAPTR)
        if (was is PhoneSetupPhase.Pairing && was.endsAtMs != null) scope.launch(Dispatchers.IO) { closeOpened() }
        if (was != PhoneSetupPhase.None) scope.launch(Dispatchers.IO) { reread() }
    }

    /**
     * Close the window this screen opened, now that a phone is connected. Bench 11-03: a phone paired in the window the
     * adapter opens by itself, "Pair iPhone" opened a fresh 120 s one two seconds later, and the adapter stayed
     * discoverable to every iPhone nearby until a second phone paired. Not through [window]: a failed close must not
     * turn the connected session's screen into "Bluetooth is down".
     */
    private fun closeOpened() {
        val status = box.pairWindow(0)
        logInfo("[PAIRING] a phone connected: closing the window Carlink opened, status=${status ?: "no answer"}", tag = Logger.Tags.ADAPTR)
    }

    /** The session ended: nothing is known about the next one. */
    fun reset() {
        synchronized(lock) {
            phones = null
            connected = false
            timer?.cancel()
            phaseState.value = PhoneSetupPhase.None
        }
    }

    /** "Pair iPhone", "Try again" or "Pair a new iPhone": open the pairing window and follow the phone. */
    fun pair() {
        val name =
            synchronized(lock) {
                if (connected) return
                nameNow()
            }
        scope.launch(Dispatchers.IO) {
            if (!box.gatesPairing) {
                logInfo("[PAIRING] the box pairs without a window: showing where to pick $name", tag = Logger.Tags.ADAPTR)
                move(PhoneSetupPhase.Pairing(name, endsAtMs = null))
                return@launch
            }
            val status = window(timing.windowSeconds) ?: return@launch
            if (status != 0) return@launch
            val ends = box.now() + timing.windowSeconds * MS_PER_SECOND
            val late =
                synchronized(lock) {
                    if (!connected) {
                        phaseState.value = PhoneSetupPhase.Pairing(name, ends)
                        timer?.cancel()
                        timer = scope.launch { windowRunsOut(timing.windowSeconds * MS_PER_SECOND) }
                    }
                    connected
                }
            if (late) closeOpened() // a phone connected while the window was being opened
        }
    }

    /**
     * MGMT_PAIR_WINDOW for [seconds] (0 closes it), the one call Settings' "Add phone" and this screen
     * share. A non-zero status means btd isn't reachable ([PhoneSetupPhase.BluetoothDown]); no answer at
     * all, while pairing from this screen, is "Try again". Returns the status.
     */
    suspend fun window(seconds: Int): Int? {
        logWarn("[PAIRING] window ${if (seconds > 0) "open for ${seconds}s" else "close"} requested", tag = Logger.Tags.ADAPTR)
        val gen = synchronized(lock) { ++windowGen }
        val afterForget = seconds > 0 && forgetQuiet()
        if (afterForget) awaitRestart()
        if (synchronized(lock) { windowGen != gen }) {
            // Stop (or another request) came while this one waited for the restart: it is no longer wanted.
            logInfo("[PAIRING] a window request overtaken while it waited for the restart: not sent", tag = Logger.Tags.ADAPTR)
            return null
        }
        val status = send(seconds)
        if (afterForget && status == 0) scope.launch { reopenIfClosed(seconds, gen) }
        return status
    }

    /** One MGMT_PAIR_WINDOW, retried while Bluetooth starts, with what its answer means for the screen. */
    private suspend fun send(seconds: Int): Int? {
        var status = box.pairWindow(seconds)
        logWarn("[PAIRING] MGMT_PAIR_WINDOW status=${status ?: "no answer"}", tag = Logger.Tags.ADAPTR)
        if (seconds > 0 && status != null && status != 0) status = whileBluetoothStarts(seconds)
        when {
            status == null && seconds > 0 -> moveIfAsked(PhoneSetupPhase.NotPaired(nameNow(), NO_ANSWER, NO_ANSWER_BODY))
            status != null && status != 0 -> bluetoothDown("MGMT_PAIR_WINDOW", status)
        }
        return status
    }

    /**
     * Hold a window asked for right after a forget until the box's wireless restart is over: btd seen down, then up
     * again. A box that says nothing, or a restart the 2 s health ticks never caught going down within
     * [Timing.restartDownMs] of the forget, opens at once (or then); the quiet time bounds the wait either way.
     */
    private suspend fun awaitRestart() {
        val since = synchronized(lock) { forgotAtMs } ?: return
        if (box.wirelessUp() == null) return
        // Say what the wait is, as [whileBluetoothStarts] does; the screen otherwise sits unchanged for up to 10 s.
        val before =
            synchronized(lock) {
                val now = phaseState.value
                if (!connected) phaseState.value = PhoneSetupPhase.BluetoothStarting(nameNow())
                now
            }
        try {
            restartWait(since)
        } finally {
            synchronized(lock) { if (phaseState.value is PhoneSetupPhase.BluetoothStarting) phaseState.value = before }
        }
    }

    private suspend fun restartWait(since: Long) {
        var sawDown = false
        while (box.now() - since < timing.forgetQuietMs && !connected) {
            when (box.wirelessUp()) {
                null -> return
                false -> sawDown = true
                true -> if (sawDown || box.now() - since >= timing.restartDownMs) break
            }
            delay(timing.restartPollMs)
        }
        logInfo(
            "[PAIRING] window after a Forget: ${if (sawDown) "the box's wireless restart is over" else "no restart seen"} " +
                "${box.now() - since} ms after the forget; opening",
            tag = Logger.Tags.ADAPTR,
        )
    }

    /**
     * A window opened in the quiet time that the box reports closed again soon after: open it once more, unless the
     * driver has asked for something since ([windowGen]): after Stop the box must not stay discoverable.
     */
    private suspend fun reopenIfClosed(
        seconds: Int,
        gen: Int,
    ) {
        delay(timing.reopenCheckMs)
        if (connected || box.windowOpen() != false || synchronized(lock) { windowGen != gen }) return
        logWarn("[PAIRING] the window opened after a Forget closed again (the box's wireless restart): opening it once more", tag = Logger.Tags.ADAPTR)
        val status = box.pairWindow(seconds)
        logWarn("[PAIRING] MGMT_PAIR_WINDOW status=${status ?: "no answer"} (reopened)", tag = Logger.Tags.ADAPTR)
        if (status == 0) {
            synchronized(lock) {
                val now = phaseState.value as? PhoneSetupPhase.Pairing ?: return
                if (now.endsAtMs == null) return
                phaseState.value = now.copy(endsAtMs = box.now() + seconds * MS_PER_SECOND)
                timer?.cancel()
                timer = scope.launch { windowRunsOut(seconds * MS_PER_SECOND) }
            }
        }
    }

    /** "Stop" while the window is open: close it and go back to the prompt. */
    fun stop() {
        val name =
            synchronized(lock) {
                val now = phaseState.value as? PhoneSetupPhase.Pairing ?: return
                timer?.cancel()
                phaseState.value = PhoneSetupPhase.Prompt(now.name, newPhone = phones?.none != true)
                now.name
            }
        logInfo("[PAIRING] stopped on the pairing screen for $name", tag = Logger.Tags.ADAPTR)
        if (box.gatesPairing) scope.launch(Dispatchers.IO) { window(0) }
    }

    /** "Later" or "OK": back to "Waiting for phone", with the way to pair still beside it. */
    fun later() {
        synchronized(lock) {
            val now = phaseState.value
            if (now == PhoneSetupPhase.None || now is PhoneSetupPhase.Waiting) return
            phaseState.value = PhoneSetupPhase.Waiting(nameNow(), noPhone = phones?.none == true)
        }
    }

    /** "Pair a new iPhone" beside "Waiting for phone": the same screen as for no phone, then [pair]. */
    fun pairNew() {
        synchronized(lock) {
            if (phaseState.value !is PhoneSetupPhase.Waiting) return
            phaseState.value = PhoneSetupPhase.Prompt(nameNow(), newPhone = phones?.none != true)
        }
    }

    /**
     * This app sent MGMT_FORGET_DEVICE. The box removes the bond and restarts wireless, and during that restart btd's
     * AUTH_FAILED hook fires: on the 8 Nov bench BTP_BOND_LOST came 0.55 s after the forget, and the screen said the
     * iPhone may no longer know the adapter, about a phone the driver had just removed. The restart also closes a window
     * opened in it (opened 2.4 s after the forget, closed 0.7 s later). See [onBtPhase] and [window].
     */
    fun onForgetSent() {
        synchronized(lock) { forgotAtMs = box.now() }
    }

    /** Within [Timing.forgetQuietMs] of this app's own forget. */
    private fun forgetQuiet(): Boolean = synchronized(lock) { forgotAtMs }?.let { box.now() - it < timing.forgetQuietMs } == true

    /** CT_BT_PHASE while the window is open: the progress line. */
    fun onBtPhase(phase: Byte) {
        if (phase == Ocbm.BTP_BOND_LOST) {
            if (forgetQuiet()) {
                logInfo("[PAIRING] BT_PHASE BOND_LOST right after this app's Forget: the box's wireless restart, not news", tag = Logger.Tags.ADAPTR)
                return
            }
            phoneForgot()
            return
        }
        val text = progressText(phase) ?: return
        synchronized(lock) {
            val now = phaseState.value as? PhoneSetupPhase.Pairing ?: return
            phaseState.value = now.copy(progress = text)
        }
    }

    /**
     * BTP_BOND_LOST: a paired iPhone answered the adapter's call and reset the link twice, so it most likely forgot the
     * pairing (5 Nov bench: six pages, each answered and reset, and pairing offered only after a minute's wait). Say so
     * at once, with how to re-pair. Not while a phone is connected or the driver is already pairing.
     */
    private fun phoneForgot() {
        val shown =
            synchronized(lock) {
                val now = phaseState.value
                if (connected || now is PhoneSetupPhase.Pairing || now is PhoneSetupPhase.PhoneForgot) return
                timer?.cancel()
                phaseState.value = PhoneSetupPhase.PhoneForgot(nameNow())
                now
            }
        logWarn("[PAIRING] BT_PHASE BOND_LOST: a paired iPhone reset the link twice; asking for a re-pair (was $shown)", tag = Logger.Tags.ADAPTR)
    }

    /** CT_PAIRING_CODE while the window is open (empty clears it); the Pair / Cancel prompt itself is the manager's. */
    fun onPairingCode(code: String) {
        synchronized(lock) {
            val now = phaseState.value as? PhoneSetupPhase.Pairing ?: return
            phaseState.value = now.copy(code = code.ifEmpty { null })
        }
    }

    /**
     * MGMT_INFO named the adapter, on any read (this screen's, Settings', the install's): the screen follows a name that
     * changes during the session. On the factory adapter (31 Oct) ocbmd answered the read at SUBSCRIBE, before Wi-Fi was
     * up, with the serial's suffix (CarLink-0264), and later reads with the name the iPhone lists (CarLink-fd6c).
     */
    fun onAdapterName(name: String) {
        synchronized(lock) {
            adapterName = name
            phaseState.value = renamed(phaseState.value, name)
        }
    }

    /**
     * The window was refused: btd isn't reachable, which in the first ~15 s after power-on means it is still starting.
     * Show that and ask again every [Timing.btRetryMs] for up to [Timing.btStartMs] (the truck, 5 Nov: three refusals
     * in that window read as Bluetooth failing, and the adapter was unplugged). Returns the last status.
     */
    private suspend fun whileBluetoothStarts(seconds: Int): Int? {
        val before =
            synchronized(lock) {
                val now = phaseState.value
                if (!connected) phaseState.value = PhoneSetupPhase.BluetoothStarting(nameNow())
                now
            }
        val until = box.now() + timing.btStartMs
        var status: Int?
        var tries = 1
        do {
            delay(timing.btRetryMs)
            status = box.pairWindow(seconds)
            tries++
        } while (refused(status) && box.now() < until && !connected)
        logWarn("[PAIRING] MGMT_PAIR_WINDOW status=${status ?: "no answer"} after $tries tries while Bluetooth started", tag = Logger.Tags.ADAPTR)
        // Answered: back to what was showing, for the caller to move on from (pair() to Pairing).
        if (status == 0) synchronized(lock) { if (phaseState.value is PhoneSetupPhase.BluetoothStarting) phaseState.value = before }
        return status
    }

    /** SET_POLICY never came back 0 in its tries after SUBSCRIBE: btd isn't reachable. */
    fun onPolicyFailed(status: Int?) {
        if (status != null && status != 0) bluetoothDown("SET_POLICY", status)
    }

    private fun read() {
        val info = box.info()
        val parsed = info?.let { PairedPhones.parse(it) }
        val next =
            synchronized(lock) {
                phones = parsed
                if (connected) return
                val p = firstPhase(parsed)
                phaseState.value = p
                if (parsed?.devices?.isNotEmpty() == true) {
                    timer?.cancel()
                    timer = scope.launch { waitForPhone() }
                }
                p
            }
        logInfo(
            "[PAIRING] MGMT_INFO: ${parsed?.devices?.let { "${it.size} phone(s) paired" } ?: "phones not reported (${if (info == null) "no answer" else "older box"})"}; $next",
            tag = Logger.Tags.ADAPTR,
        )
    }

    private fun reread() {
        val parsed = box.info()?.let { PairedPhones.parse(it) } ?: return
        synchronized(lock) { phones = parsed }
        logInfo("[PAIRING] MGMT_INFO after the phone connected: ${parsed.devices?.size ?: "?"} phone(s) paired", tag = Logger.Tags.ADAPTR)
    }

    private suspend fun waitForPhone() {
        delay(timing.waitMs)
        synchronized(lock) {
            if (connected || phaseState.value != PhoneSetupPhase.None) return
            phaseState.value = PhoneSetupPhase.Waiting(nameNow(), noPhone = false)
        }
        logInfo("[PAIRING] no phone ${timing.waitMs / MS_PER_SECOND} s into the session: offering to pair a new one", tag = Logger.Tags.ADAPTR)
    }

    private suspend fun windowRunsOut(ms: Long) {
        delay(ms)
        synchronized(lock) {
            if (connected || phaseState.value !is PhoneSetupPhase.Pairing) return
            val name = nameNow()
            phaseState.value = PhoneSetupPhase.NotPaired(name, NOT_PAIRED, notPairedBody(name))
        }
        logInfo("[PAIRING] the pairing window ran out with no phone", tag = Logger.Tags.ADAPTR)
    }

    private fun bluetoothDown(
        what: String,
        status: Int,
    ) {
        logWarn("[PAIRING] $what status=$status: the adapter's Bluetooth (btd) isn't reachable", tag = Logger.Tags.ADAPTR)
        synchronized(lock) {
            if (connected) return
            timer?.cancel()
            phaseState.value = PhoneSetupPhase.BluetoothDown(nameNow(), status)
        }
    }

    private fun move(next: PhoneSetupPhase) {
        synchronized(lock) { if (!connected) phaseState.value = next }
    }

    /** [next], when the driver asked from this screen (a prompt or a failure on it is up). */
    private fun moveIfAsked(next: PhoneSetupPhase) {
        synchronized(lock) {
            val now = phaseState.value
            if (!connected && onThisScreen(now)) phaseState.value = next
        }
    }

    /** A refusal: btd answered nothing useful (no answer at all is not one). */
    private fun refused(status: Int?): Boolean = status != null && status != 0

    /** The driver asked from this screen: a prompt, a failure on it, or Bluetooth still starting is up. */
    private fun onThisScreen(p: PhoneSetupPhase): Boolean =
        when (p) {
            is PhoneSetupPhase.Prompt, is PhoneSetupPhase.NotPaired, is PhoneSetupPhase.BluetoothStarting, is PhoneSetupPhase.PhoneForgot -> true
            else -> false
        }

    private fun nameNow(): String = adapterName ?: phones?.name ?: DEFAULT_NAME

    companion object {
        /** How long "Pair iPhone" (and Settings' "Add phone") opens the window for. */
        const val WINDOW_SECONDS = 120
        private const val MS_PER_SECOND = 1_000L

        /** What an adapter is called before MGMT_INFO has named it. */
        const val DEFAULT_NAME = "the adapter"
        const val NO_ANSWER = "The adapter didn't answer"
        const val NO_ANSWER_BODY = "Check that it is plugged in, then try again."
        const val NOT_PAIRED = "No iPhone paired"

        fun notPairedBody(name: String): String = "Pairing was open for 2 minutes. Check that $name is forgotten on the iPhone, then try again and pick $name there."

        /**
         * What the screen leads with, from MGMT_INFO: "Pair your iPhone" when no phone is paired; nothing
         * yet when phones are (the wait comes first), or when the box doesn't report them.
         */
        fun firstPhase(phones: PairedPhones?): PhoneSetupPhase =
            if (phones != null && phones.none) PhoneSetupPhase.Prompt(phones.name ?: DEFAULT_NAME, newPhone = false) else PhoneSetupPhase.None

        /** [p] naming the adapter [name]; a phase that names none stays as it is. */
        fun renamed(
            p: PhoneSetupPhase,
            name: String,
        ): PhoneSetupPhase =
            when (p) {
                is PhoneSetupPhase.Prompt -> p.copy(name = name)
                is PhoneSetupPhase.Pairing -> p.copy(name = name)
                is PhoneSetupPhase.NotPaired -> p.copy(name = name, body = if (p.title == NOT_PAIRED) notPairedBody(name) else p.body)
                is PhoneSetupPhase.BluetoothDown -> p.copy(name = name)
                is PhoneSetupPhase.BluetoothStarting -> p.copy(name = name)
                is PhoneSetupPhase.PhoneForgot -> p.copy(name = name)
                is PhoneSetupPhase.Waiting -> p.copy(name = name)
                PhoneSetupPhase.None -> p
            }

        /** The progress line for a BT_PHASE while pairing; null for phases with nothing to say. */
        fun progressText(phase: Byte): String? =
            when (phase) {
                Ocbm.BTP_LINK_UP -> "The iPhone found the adapter."
                Ocbm.BTP_AUTHENTICATING -> "Pairing with the iPhone..."
                Ocbm.BTP_AUTHENTICATED, Ocbm.BTP_IDENTIFYING, Ocbm.BTP_IDENTIFIED -> "Paired. Setting up CarPlay..."
                Ocbm.BTP_WIFI_HANDOFF -> "Moving to Wi-Fi..."
                Ocbm.BTP_PAIR_REJECTED -> "The adapter turned the iPhone away: pairing wasn't open."
                else -> null
            }
    }
}
