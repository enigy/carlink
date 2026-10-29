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
    }

    data class Timing(
        /** From the session's start to "Pair a new iPhone" beside "Waiting for phone", when phones are bonded. */
        val waitMs: Long = 60_000,
        /** How long "Pair iPhone" opens the window for. */
        val windowSeconds: Int = WINDOW_SECONDS,
    )

    private val phaseState = MutableStateFlow<PhoneSetupPhase>(PhoneSetupPhase.None)

    /** What the connecting screen shows about pairing. */
    val phase: StateFlow<PhoneSetupPhase> = phaseState.asStateFlow()

    private val lock = Any()
    private var phones: PairedPhones? = null
    private var connected = false
    private var timer: Job? = null

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
        if (was != PhoneSetupPhase.None) scope.launch(Dispatchers.IO) { reread() }
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
            synchronized(lock) {
                if (connected) return@launch
                phaseState.value = PhoneSetupPhase.Pairing(name, ends)
                timer?.cancel()
                timer = scope.launch { windowRunsOut(name, timing.windowSeconds * MS_PER_SECOND) }
            }
        }
    }

    /**
     * MGMT_PAIR_WINDOW for [seconds] (0 closes it), the one call Settings' "Add phone" and this screen
     * share. A non-zero status means btd isn't reachable ([PhoneSetupPhase.BluetoothDown]); no answer at
     * all, while pairing from this screen, is "Try again". Returns the status.
     */
    fun window(seconds: Int): Int? {
        logWarn("[PAIRING] window ${if (seconds > 0) "open for ${seconds}s" else "close"} requested", tag = Logger.Tags.ADAPTR)
        val status = box.pairWindow(seconds)
        logWarn("[PAIRING] MGMT_PAIR_WINDOW status=${status ?: "no answer"}", tag = Logger.Tags.ADAPTR)
        when {
            status == null && seconds > 0 -> moveIfAsked(PhoneSetupPhase.NotPaired(nameNow(), NO_ANSWER, NO_ANSWER_BODY))
            status != null && status != 0 -> bluetoothDown("MGMT_PAIR_WINDOW", status)
        }
        return status
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

    /** CT_BT_PHASE while the window is open: the progress line. */
    fun onBtPhase(phase: Byte) {
        val text = progressText(phase) ?: return
        synchronized(lock) {
            val now = phaseState.value as? PhoneSetupPhase.Pairing ?: return
            phaseState.value = now.copy(progress = text)
        }
    }

    /** CT_PAIRING_CODE while the window is open (empty clears it); the Pair / Cancel prompt itself is the manager's. */
    fun onPairingCode(code: String) {
        synchronized(lock) {
            val now = phaseState.value as? PhoneSetupPhase.Pairing ?: return
            phaseState.value = now.copy(code = code.ifEmpty { null })
        }
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
                    timer = scope.launch { waitForPhone(parsed.name ?: DEFAULT_NAME) }
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

    private suspend fun waitForPhone(name: String) {
        delay(timing.waitMs)
        synchronized(lock) {
            if (connected || phaseState.value != PhoneSetupPhase.None) return
            phaseState.value = PhoneSetupPhase.Waiting(name, noPhone = false)
        }
        logInfo("[PAIRING] no phone ${timing.waitMs / MS_PER_SECOND} s into the session: offering to pair a new one", tag = Logger.Tags.ADAPTR)
    }

    private suspend fun windowRunsOut(
        name: String,
        ms: Long,
    ) {
        delay(ms)
        synchronized(lock) {
            if (connected || phaseState.value !is PhoneSetupPhase.Pairing) return
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
            if (!connected && (now is PhoneSetupPhase.Prompt || now is PhoneSetupPhase.NotPaired)) phaseState.value = next
        }
    }

    private fun nameNow(): String = phones?.name ?: DEFAULT_NAME

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
