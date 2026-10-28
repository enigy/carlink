package com.carlink

import android.content.Context
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Surface
import com.carlink.audio.AudioRouteInfo
import com.carlink.audio.AudioTestLock
import com.carlink.audio.CallEchoMonitor
import com.carlink.audio.CallPaths
import com.carlink.audio.MicProfile
import com.carlink.audio.MicrophoneCaptureManager
import com.carlink.audio.SilenceGuard
import com.carlink.av.MediaAudioPlayer
import com.carlink.av.SkipAftermath
import com.carlink.av.TransientFocusPolicy
import com.carlink.av.VideoCodecs
import com.carlink.av.VideoRenderer
import com.carlink.av.VoiceRouter
import com.carlink.callui.CallNotificationHost
import com.carlink.callui.CallRoster
import com.carlink.callui.CallState
import com.carlink.car.AppFocusClaimer
import com.carlink.car.DriveStateMonitor
import com.carlink.cluster.ClusterNavigation
import com.carlink.device.KnownDevice
import com.carlink.device.KnownDeviceSnapshot
import com.carlink.device.KnownDeviceStore
import com.carlink.device.mergeDeviceList
import com.carlink.device.phonePolicyJson
import com.carlink.logging.DriveLog
import com.carlink.logging.DriveLogging
import com.carlink.logging.LogExport
import com.carlink.logging.LogRedactor
import com.carlink.logging.LogTrail
import com.carlink.logging.Logger
import com.carlink.logging.ProbeLog
import com.carlink.logging.logDebug
import com.carlink.logging.logError
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.media.CarlinkMediaBrowserService
import com.carlink.media.ConnectPlay
import com.carlink.media.MediaControl
import com.carlink.media.MediaSessionManager
import com.carlink.media.NowPlayingInfo
import com.carlink.media.NowPlayingState
import com.carlink.media.PlayStatus
import com.carlink.media.TrackChangeHold
import com.carlink.navigation.LaneActivity
import com.carlink.navigation.NavigationStateManager
import com.carlink.navigation.OcbmNavigationFeed
import com.carlink.navigation.compose.ComposedIconStore
import com.carlink.ocbm.BinaryPlist
import com.carlink.ocbm.BoxLogEntry
import com.carlink.ocbm.BridgeBulkTransport
import com.carlink.ocbm.CarPlayDock
import com.carlink.ocbm.HostInstanceNonce
import com.carlink.ocbm.Ocbm
import com.carlink.ocbm.OcbmAvLanes
import com.carlink.ocbm.OcbmClient
import com.carlink.ocbm.OemIcon
import com.carlink.ocbm.PixelRect
import com.carlink.ocbm.RawBulkTransport
import com.carlink.ocbm.SplitViewArea
import com.carlink.ocbm.VehicleConfigSpec
import com.carlink.ocbm.VehicleConfigYaml
import com.carlink.ocbm.seam.SeamPipe
import com.carlink.ocbm.seam.ViewAreaRect
import com.carlink.protocol.AdapterConfig
import com.carlink.protocol.MessageSerializer
import com.carlink.protocol.MultiTouchAction
import com.carlink.protocol.PhoneType
import com.carlink.ui.settings.AutoCallPath
import com.carlink.ui.settings.CallAudioStore
import com.carlink.ui.settings.CarPlayLimitStore
import com.carlink.ui.settings.DisplayMode
import com.carlink.ui.settings.DriveSide
import com.carlink.ui.settings.DriveSideStore
import com.carlink.ui.settings.DriverProfiles
import com.carlink.ui.settings.LaneGuidanceStore
import com.carlink.ui.settings.LayoutStore
import com.carlink.ui.settings.MusicStore
import com.carlink.ui.settings.SessionSettings
import com.carlink.usb.CarlinkBridge
import com.carlink.usb.UsbPermissionPrompt
import com.carlink.util.DisplayProfile
import com.carlink.util.LogCallback
import com.carlink.util.PanelGeometry
import com.enigy.carlink2.bridge.BridgeContract
import com.enigy.carlink2.bridge.ICarlinkBridge
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * Main Carlink Manager — OCBM.
 *
 * Central orchestrator for the CarPlay-only app against the **open OCBM userspace** on a
 * CPC200-CCPA:
 * - The adapter link through the USB bridge ([BridgeBulkTransport]) and the OCBM session ([OcbmClient])
 * - Video via [VideoRenderer] (H.264 or HEVC, whichever iOS negotiated), fed from `CH_VIDEO` through the forward-encrypted seam
 * - Audio via [MediaAudioPlayer] (media: PCM wired / AAC-LC wireless) and [VoiceRouter] (telephony/Siri/alert/nav)
 * - Microphone capture gated by the box's own `CT_UPLINK`, shipped over `CH_MIC`
 * - MediaSession integration for AAOS (metadata + album art) from `CH_METADATA`
 * - Auto-reconnect with exponential backoff and Pattern A / Pattern C escalation
 *
 * ## What changed from the riddleBox era, and why the shape is different
 *
 * The old manager was a **message pump**: one `handleMessage` switch over decoded protocol
 * messages, from which every piece of session state was *inferred*. OCBM inverts that — the box
 * states things directly (`CT_BT_PHASE`, `SEV_PHONE_*`, `CT_UPLINK`, per-stream `audioType`), so
 * the pump is gone and this class is a set of callbacks wired to [OcbmClient].
 *
 * Two whole categories of bug disappear with it:
 * - **Voice-mode arbitration.** riddleBox required a `VoiceMode` state machine because
 *   `PHONECALL_START` arrived ~130 ms *before* `SIRI_STOP`, and a naive reading killed the call's
 *   microphone. OCBM routes per stream by `audioType`, so there is no command ordering to reason
 *   about, and the mic is gated by the box.
 * - **Format guessing.** Audio formats came from a `decodeType` byte mapped through a fixed table;
 *   now each stream carries its own `SEAM_FORMAT`.
 *
 * ## The public API is deliberately unchanged
 *
 * Every member the UI and media layers call keeps its exact signature, so `MainActivity`,
 * `MainScreen`, `PhonesTab` and everything under `media/` compile untouched. That is why
 * [sendMultiTouch] still takes riddleBox's `MessageSerializer.TouchPoint`: it is the app's
 * host-side input value type now, and rewriting `MainScreen.handleTouchEvent` (110 lines of
 * hard-won deadband / ACTION_CANCEL / DOWN-demotion logic) while also swapping protocols would
 * have been two risky changes at once.
 */
class CarlinkManager(
    private val context: Context,
    initialConfig: AdapterConfig = AdapterConfig.DEFAULT,
    /**
     * MediaSession manager owned by [MainActivity] (app-scope lifetime). Injected so the
     * underlying Media3 session survives CarlinkManager rebuilds — without it, each rebuild
     * would fire `onSessionDestroyed` on CarLauncher's controller, which does not rebind to a
     * new token, leaving the homescreen Media card blank until a reinstall.
     */
    injectedMediaSessionManager: MediaSessionManager? = null,
    /**
     * The panel as detected at launch ([DisplayProfile.detect]) — the source of truth for the CarPlay
     * config's geometry, frame rate and safe area. Null only in tests / on a host with no window;
     * [config] then supplies the legacy width/height/fps.
     */
    val displayProfile: DisplayProfile? = null,
    /** The display mode the window was set to for [displayProfile]: part of what each session is built with. */
    private val displayMode: DisplayMode = DisplayMode.DEFAULT,
) {
    private var config: AdapterConfig = initialConfig

    /** Configured WiFi/Bluetooth name the adapter advertises (shown on the dashboard). */
    val adapterName: String get() = config.boxName

    init {
        live = this
        // Carlink3: cluster/HUD navigation (from Carlink2). Resolves the Templates Host icon
        // authority the bridge hosts; the compose icon store renders maneuver bitmaps for it.
        NavigationStateManager.initialize(context.applicationContext)
        ComposedIconStore.setEnabled(true)
    }

    companion object {
        /**
         * The manager currently driving a session, for entry points that have no reference to it:
         * the voice-interaction session (hardware PTT) is bound by the system, not by this app.
         * Set in the constructor, compare-and-cleared in [release] so a stale manager can never
         * clobber a newer one.
         */
        @Volatile var live: CarlinkManager? = null
            private set

        private const val USB_WAIT_PERIOD_MS = 3000L

        private const val USB_FIND_ATTEMPTS = 10 // × USB_WAIT_PERIOD_MS: ~30 s to find the adapter

        /** Without the bridge Carlink3 can't reach the adapter (it has no USB access of its own). */
        private const val INSTALL_BRIDGE_STATUS = "Install the Carlink USB bridge"

        /** How long a bridge permission prompt may stay unanswered before the start gives up. */
        private const val BRIDGE_PROMPT_WAIT_MS = 30_000L

        /** Status while the system USB permission prompt is up (an action line on the connecting screen). */
        private const val ALLOW_USB_STATUS = "Allow USB access for the adapter when asked"

        private const val BRIDGE_PERMISSION_POLL_MS = 250L

        /**
         * Polls (× [BRIDGE_PERMISSION_POLL_MS] = 2 s) for the platform's own grant before prompting. The
         * fixed USB handler path broadcasts USB_DEVICE_ATTACHED and only THEN grants the bridge, so a
         * start the broadcast triggered can arrive a moment early; prompting then would show a needless
         * dialog for a permission that is milliseconds away (Carlink2 kept the same grace, [204]).
         */
        private const val BRIDGE_GRANT_GRACE_POLLS = 8

        /** After Reboot adapter, search for it if no attach broadcast has arrived by then. */
        private const val REBOOT_FALLBACK_MS = 15_000L

        private const val MAX_RECONNECT_ATTEMPTS = 5

        /** The box daemon's log (ocbmd stdout/stderr, tools/run_ocbmd.sh); tmpfs, so RAM-only. */
        private const val BOX_LOG_PATH = "/tmp/box.log"

        /** How much of the box's reject line goes into the [LANES] refusal log line. */
        private const val LANE_REFUSAL_LOG_CHARS = 160

        private const val BYTE_MASK = 0xFF

        /** The other daemons' logs Save Logs also pulls (ocbmd's LOG_SRC table), with the file label each gets. */
        private val BOX_DAEMON_LOGS = listOf("/tmp/iap2d.log" to "iap2d", "/tmp/carplayd.log" to "carplayd", "/tmp/supervisor.log" to "supervisor")

        // Wake lock: safety timeout per acquire, and how often the refresh job renews it.
        // Refresh at half the timeout so a missed tick still leaves a full margin.
        private const val WAKE_LOCK_TIMEOUT_MS = 2 * 60 * 60 * 1000L // 2h
        private const val WAKE_LOCK_REFRESH_INTERVAL_MS = WAKE_LOCK_TIMEOUT_MS / 2

        // How long a forgotten device's MAC is suppressed from list merges while the box
        // processes the forget (it restarts wireless, which is not instant).
        private const val FORGET_SUPPRESS_MS = 25_000L

        /** When to re-send the phone policy after SUBSCRIBE: btd comes up with the radios, a few seconds in. */
        private val POLICY_SYNC_DELAYS_MS = longArrayOf(5_000L, 15_000L, 45_000L)

        private const val INITIAL_RECONNECT_DELAY_MS = 2000L
        private const val MAX_RECONNECT_DELAY_MS = 30000L

        // Surface debouncing — wait for size to stabilize before rebuilding the decoder.
        private const val SURFACE_DEBOUNCE_MS = 150L

        // ---------------------------------------------------------------------------------
        // Reconnect-escalation patterns (in-source conventions)
        //
        //   Pattern A — "no initial response" errors in a row (consecutiveNoResponse >= 2).
        //               Under OCBM this means CT_HELLO went unanswered: the box is absent,
        //               still booting, or its OUT path is dead. Retrying won't help.
        //               Surfaced as "Adapter not responding — reboot adapter".
        //
        //   Pattern B — DELETED. It keyed off riddleBox's SCANNING_DEVICE command, which has
        //               no OCBM analogue; CT_BT_PHASE now reports the handshake truthfully
        //               instead of it being inferred. `hadPriorSession` survives only to
        //               select the give-up message.
        //
        //   Pattern C — STREAMING sessions that die within SHORT_SESSION_THRESHOLD_MS,
        //               SHORT_SESSION_ESCALATION_COUNT times in a row → "connection unstable".
        //
        // Keep these labels/thresholds stable; remote logs filter on the "[ESCALATION]" tag.
        // ---------------------------------------------------------------------------------

        private const val SHORT_SESSION_THRESHOLD_MS = 10_000L
        private const val SHORT_SESSION_ESCALATION_COUNT = 2

        /**
         * Contacts the box can carry. Apple's `HIDTouchScreenMultiCreateDescriptor` declares
         * exactly two `Finger` collections, so this is the descriptor's capacity, not a policy
         * choice — raising it would emit contacts the HID report has no field for.
         */
        private const val MAX_CONTACTS = 2

        /** Must hold one whole 2400x960 HEVC IDR; see [SeamPipe]'s capacity contract. */
        private const val VIDEO_PIPE_BYTES = 8 * 1024 * 1024
        private const val VIDEO_PIPE_DEPTH = 64

        /** After an overlay closes, how long to wait for frames before assuming a stuck decoder. */
        private const val OVERLAY_RECOVERY_WATCHDOG_MS = 750L
    }

    /**
     * Connection state enum.
     */
    enum class State {
        DISCONNECTED,
        CONNECTING,
        DEVICE_CONNECTED,
        STREAMING,
    }

    /**
     * Information about a known device.
     *
     * `type` is always "CarPlay" and `rfcomm` is always null under OCBM: `MGMT_INFO.devices` is a
     * list of bare MAC strings read out of the box's 25-byte link-key store, which holds no name,
     * no type and no timestamp. `name` and `lastConnected` are supplied by the app's own history
     * (see [com.carlink.device.KnownDeviceStore]), fed by `CT_PHONE_IDENT` — the box genuinely
     * cannot provide either.
     *
     * [bonded] distinguishes the two halves of the merged list: true = the box currently holds a
     * link key for this phone and can page it; false = the app remembers it but the box does not,
     * so it cannot be connected until it is paired again. Defaulted so existing construction sites
     * are unaffected.
     */
    data class DeviceInfo(
        val btMac: String,
        val name: String,
        val type: String,
        val lastConnected: String? = null,
        val rfcomm: String? = null,
        val bonded: Boolean = true,
    )

    /**
     * Callback interface for Carlink events.
     */
    interface Callback {
        fun onStateChanged(state: State)

        fun onStatusTextChanged(text: String)

        fun onHostUIPressed()

        /** Called when phone type becomes known or cleared. */
        fun onPhoneTypeChanged(phoneType: PhoneType) {}

        /** Called when the adapter's paired device list changes. */
        fun onDeviceListChanged(devices: List<DeviceInfo>) {}

        /**
         * Box-side session state: which transport owns the box (`Ocbm.PM_*`) and its subsystem
         * liveness (`Ocbm.BH_*` mask; [healthKnown] false until the first CT_BOX_HEALTH). Main thread.
         */
        fun onBoxStatusChanged(
            projMode: Byte,
            health: Int,
            healthKnown: Boolean,
        ) {}

        /**
         * The box published (non-null) or cleared (null) a wireless SSP Numeric-Comparison pairing
         * code. The UI must show it beside Pair / Cancel and route the answer to
         * [answerPairing]; the box waits up to 55 s and then cancels on its own. Main thread.
         */
        fun onPairingCodeChanged(code: String?) {}
    }

    /** The pairing code currently awaiting the user's Pair / Cancel, or null. */
    @Volatile var pairingCode: String? = null
        private set

    /**
     * Answer the pairing prompt: `CT_PAIR_CONFIRM` (accept = the codes match). One answer per
     * prompt — the prompt clears locally at once; the box clears its code when the bond completes
     * or is refused. False = no prompt outstanding or no session.
     */
    fun answerPairing(accept: Boolean): Boolean {
        val c = client ?: return false
        val code = pairingCode ?: return false
        logInfo("[PAIRING] user answered ${if (accept) "PAIR" else "CANCEL"} for code $code", tag = Logger.Tags.ADAPTR)
        pairingCode = null
        callback?.onPairingCodeChanged(null)
        setStatusText(if (accept) "Pairing..." else "Pairing cancelled")
        scope.launch(Dispatchers.IO) { c.sendPairConfirm(accept) }
        return true
    }

    /**
     * Observer for iPhone call state (iAP2 `callState` records reduced by [CallRoster]). Multiple can
     * be registered; fires on the main thread. This is the hook the call-audio path uses to arbitrate
     * focus against the media lane — see the seam note in OCBMANDROID.md.
     */
    fun interface CallStateListener {
        fun onCallPresentation(presentation: CallRoster.Presentation)
    }

    private val callStateListeners = mutableListOf<CallStateListener>()

    fun addCallStateListener(listener: CallStateListener) {
        synchronized(callStateListeners) { callStateListeners.add(listener) }
    }

    fun removeCallStateListener(listener: CallStateListener) {
        synchronized(callStateListeners) { callStateListeners.remove(listener) }
    }

    /** Last raw `callState` record seen this session (null = none / idle). */
    @Volatile var callState: CallState? = null
        private set

    /**
     * The view area iOS is drawing CarPlay into, from the video stream's own header
     * ([VideoSeam.onViewArea]): null until a session's first codec config, then every animation
     * step of a Dock resize. The pane in MainScreen lays itself over the rest of the frame from this,
     * so a switch asked for by Settings, the pane's button or CarPlay's own Dock button all look alike.
     */
    private val viewAreaState = MutableStateFlow<ViewAreaRect?>(null)
    val viewArea: StateFlow<ViewAreaRect?> = viewAreaState.asStateFlow()

    /** CarPlay's area of the split layout the last pushed config declared ([vehicleConfigSpec]); null = one full area. */
    @Volatile var declaredSplitArea: PixelRect? = null
        private set

    /** The last pushed config put CarPlay's Dock on the right (`rightHandDrive`); the drive side applies per connection. */
    @Volatile private var dockOnRight = false

    /** The saved settings the last pushed config was built from ([vehicleConfigSpec]). */
    @Volatile private var pushedSettings: SessionSettings? = null

    /** The settings the session in use was built with; null with no adapter session. Settings' exit prompt reads it. */
    val builtSettings: SessionSettings? get() = pushedSettings.takeIf { state != State.DISCONNECTED }

    /**
     * Taps on CarPlay's Dock ([CarPlayDock]), counted: the pane reads a new count as the driver leaving
     * the CarPlay app in front, the one app switch it can see ([onCarPlayTap]).
     */
    private val dockTapsState = MutableStateFlow(0)
    val dockTaps: StateFlow<Int> = dockTapsState.asStateFlow()

    /** The track and its playback for the split layout's media card: the media session's merge, as a flow. */
    private val nowPlayingState = MutableStateFlow(NowPlayingState.NONE)
    val nowPlaying: StateFlow<NowPlayingState> = nowPlayingState.asStateFlow()

    /** [callPresentation] as a flow, for the split layout's call card. */
    private val callPresentationState = MutableStateFlow<CallRoster.Presentation>(CallRoster.Presentation.None)
    val callPresentationFlow: StateFlow<CallRoster.Presentation> = callPresentationState.asStateFlow()

    /** The reduced call view the notification currently shows. */
    val callPresentation: CallRoster.Presentation get() = callNotificationHost?.presentation ?: CallRoster.Presentation.None

    /** Dashboard observer for [boxStatusText]. Multiple can be registered; fires on the main thread. */
    fun interface BoxStatusListener {
        fun onBoxStatus(text: String)
    }

    private val boxStatusListeners = mutableListOf<BoxStatusListener>()

    fun addBoxStatusListener(listener: BoxStatusListener) {
        synchronized(boxStatusListeners) { boxStatusListeners.add(listener) }
    }

    fun removeBoxStatusListener(listener: BoxStatusListener) {
        synchronized(boxStatusListeners) { boxStatusListeners.remove(listener) }
    }

    /** Which transport owns the box (CT_PROJ_MODE). `PM_NONE` until told. */
    @Volatile var projectionMode: Byte = Ocbm.PM_NONE
        private set

    /** Box subsystem liveness (CT_BOX_HEALTH). Meaningful only when [boxHealthKnown]. */
    @Volatile var boxHealth: Int = 0
        private set

    @Volatile var boxHealthKnown: Boolean = false
        private set

    /**
     * Listener for device management events. Unlike [Callback], multiple can be registered.
     */
    fun interface DeviceListener {
        fun onDeviceListChanged(devices: List<DeviceInfo>)
    }

    private val deviceListeners = mutableListOf<DeviceListener>()

    fun addDeviceListener(listener: DeviceListener) {
        synchronized(deviceListeners) { deviceListeners.add(listener) }
    }

    fun removeDeviceListener(listener: DeviceListener) {
        synchronized(deviceListeners) { deviceListeners.remove(listener) }
    }

    private fun notifyDeviceListeners() {
        val snapshot = synchronized(deviceListeners) { deviceListeners.toList() }
        snapshot.forEach { it.onDeviceListChanged(_pairedDevices) }
    }

    // Coroutine scope for async operations. SupervisorJob so one failed child cannot cancel the
    // whole scope — without it, every later launch silently no-ops and the session becomes a
    // permanent zombie. The handler logs instead of crashing.
    private val scopeExceptionHandler =
        CoroutineExceptionHandler { _, e ->
            logError("[SCOPE] Uncaught exception in session coroutine", tag = Logger.Tags.USB, throwable = e)
        }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + scopeExceptionHandler)

    // Serializes session lifecycle transitions (start/stop/restart/handleError). NOT reentrant —
    // internal callers already holding it must use the *Impl variants.
    private val lifecycleMutex = Mutex()

    // Set by release(); gates startImpl. Closes the zombie-start hole: an ATTACHED-intent start()
    // launched on the Activity's lifecycleScope could otherwise survive release() and drive the
    // released manager through a full connect, claiming USB from its replacement.
    @Volatile private var released = false

    private val currentState = AtomicReference(State.DISCONNECTED)
    val state: State get() = currentState.get()

    private var callback: Callback? = null

    // ---- OCBM session -----------------------------------------------------------------------
    // One transport + one client PER SESSION, by contract: OcbmClient.stop() is terminal and a
    // bridge session has no reopen, so reconnecting means constructing a fresh pair. Both are
    // cheap — neither holds a Context or native resources beyond the bridge's socket.
    private var transport: RawBulkTransport? = null
    private var client: OcbmClient? = null

    private val ocbmLog = ProbeLog.sub("ocbm")
    private val usbLog = ProbeLog.sub("usb")

    // Wake lock to prevent CPU sleep during streaming. PARTIAL keeps the CPU running but lets
    // the screen turn off.
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wakeLock: PowerManager.WakeLock =
        powerManager
            .newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Carlink::UsbStreamingWakeLock",
            ).apply {
                // Non-reference-counted so the periodic refresh (a repeat acquire that restarts
                // the safety timeout) still fully releases on one release().
                setReferenceCounted(false)
            }

    // Renews the wake lock's safety timeout while a session is active; without it a continuous
    // session longer than the timeout silently lost the lock mid-drive.
    @Volatile private var wakeLockRefreshJob: Job? = null

    // ---- video ------------------------------------------------------------------------------

    /**
     * One video generation: a pipe, the renderer bound to a Surface, and the thread draining it.
     *
     * These three live and die together because [VideoRenderer] binds its `Surface` at
     * construction and its `consume` owns the codec for the life of the call. A new Surface
     * therefore means a new epoch, never a mutation of the old one.
     *
     * Note the asymmetry with audio, which is session-scoped: only video is surface-scoped, which
     * is exactly why `VideoSeam`'s pipe is swappable while the seam itself (holding the session's
     * decrypt key) is not.
     */
    private class VideoEpoch(
        val seq: Int,
        val pipe: SeamPipe,
        val renderer: VideoRenderer,
        val thread: Thread,
    )

    @Volatile private var videoEpoch: VideoEpoch? = null
    private val videoEpochSeq =
        java.util.concurrent.atomic
            .AtomicInteger(0)
    private var videoSurface: Surface? = null

    /** True when video start is deferred until a session exists and a valid Surface is present. */
    private var codecDeferred = true

    /** One-time [initialize] work (mic manager, MediaSession attach) has run. */
    private var initializedOnce = false

    /**
     * Who the connected phone is, from `CT_PHONE_IDENT` — the phone's own AirPlay SETUP plist.
     *
     * Keyed by lower-cased `deviceID` (the BR/EDR MAC), which is the join key against MGMT_INFO's
     * bonded list. Retained across the session so a device list rebuilt later still gets the name.
     */
    private val phoneNames = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())

    /** `deviceID` of the phone currently in session, lower-cased, or null. */
    @Volatile private var connectedPhoneMac: String? = null

    /**
     * Durable known-device history. Survives process death; cleared only by the user or by clearing
     * app storage. This is what lets the list render before any adapter session exists.
     */
    private var knownStore: KnownDeviceStore? = null

    /**
     * The device the box should prefer when it initiates a reconnect, or null for
     * "follow most-recently-connected".
     *
     * Persisted here today; the box-side verb that acts on it is Phase 3. Storing it now is not
     * speculative — the ordering it drives is already visible in the list.
     */
    val preferredBtMac: String? get() = knownStore?.snapshot?.preferredMac

    /** Command verbs already logged as unhandled, so each is named once rather than per arrival. */
    private val unhandledCommands = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** AAOS drive-state source. Null off automotive; see [DriveStateMonitor]. */
    private var driveMonitor: DriveStateMonitor? = null

    /** The truck is being driven, as AAOS says (UX restrictions). False off automotive. */
    val isDriving: Boolean get() = driveMonitor?.driving == true

    /** NAVIGATION app-focus claim, edge-driven from route guidance. Null off automotive. */
    private var appFocusClaimer: AppFocusClaimer? = null

    /** CallStyle notification for iPhone calls; answer/decline/hang-up route to [sendTelephony]. */
    private var callNotificationHost: CallNotificationHost? = null

    /** Whether MainActivity (the projection) is on screen; the call card stays out of its way while it is. */
    @Volatile private var appVisible = true

    /**
     * MainActivity started or stopped. While Carlink3 is on screen CarPlay's own call screen is the
     * call UI, so the Android call card is not posted (on AAOS a call card stays heads-up over the
     * projection until it is acted on). Main thread.
     */
    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        callNotificationHost?.setAppVisible(visible)
    }

    /**
     * This host session's identity on the wire, sent in every CT_HELLO this manager's clients make.
     *
     * Random per manager instance, never zero (0 is the wire's "not supplied"). It exists so the box
     * can tell a REPLACEMENT host from a reattaching one: a process killed without CT_STOP leaves the
     * box believing a host is present, and a relaunch inside the heartbeat grace keeps that belief
     * alive from the new process — so projection is never re-armed and the session comes up with no
     * A/V. Verified on hardware before the fix; see OCBMANDROID.md.
     *
     * Stable across USB blips (a reattach is the same host), rotated ONLY by [cleanSlateReset] —
     * whose whole point is that the box must see a new host, not a reattaching one.
     */
    @Volatile private var instanceNonce: Int = HostInstanceNonce.fresh()

    /** The nonce the next CT_HELLO will carry. Exposed for logs and tests. */
    val hostInstanceNonce: Int get() = instanceNonce

    private var actualSurfaceWidth = 0
    private var actualSurfaceHeight = 0

    // ---- audio ------------------------------------------------------------------------------
    // Session-scoped (as in the reference implementation, where only the video renderer is
    // surface-scoped). MediaAudioPlayer holds a resting AUDIOFOCUS_GAIN so AAOS has an owner to return
    // to; VoiceRouter takes per-purpose transient focus with distinct listener identities,
    // because AAOS CarAudioFocus keys on the listener object.
    private var mediaPlayer: MediaAudioPlayer? = null
    private var voiceRouter: VoiceRouter? = null

    // ---- microphone -------------------------------------------------------------------------
    private var microphoneManager: MicrophoneCaptureManager? = null

    @Volatile private var isMicrophoneCapturing = false

    // Negotiated by the box and delivered on CT_UPLINK. Never guessed: a wrong rate here is a
    // pitch-shifted uplink that Siri mishears with no error anywhere.
    @Volatile private var micRate = 16000

    @Volatile private var micChannels = 1

    @Volatile private var micCodec = MicProfile.CODEC_PCM

    /** The input the running capture was pinned to (null = the OS's choice), so a call can tell whether to move it. */
    @Volatile private var micInput: android.media.AudioDeviceInfo? = null

    // Dedicated single-thread scheduler for the 20 ms mic send cadence. Deliberately NOT
    // shared-pool coroutines (broke mic timing — revisions [19]/[21]). A single-thread executor
    // also serializes old/new tasks across format switches, and the generation token skips stale
    // queued runs entirely.
    private val micSendExecutor =
        java.util.concurrent
            .ScheduledThreadPoolExecutor(1) { r ->
                Thread({
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                    r.run()
                }, "MicSend")
            }.apply { removeOnCancelPolicy = true }
    private var micSendFuture: java.util.concurrent.ScheduledFuture<*>? = null

    private val micSendGeneration =
        java.util.concurrent.atomic
            .AtomicInteger(0)

    // ---- MediaSession -----------------------------------------------------------------------
    // Service-scope-owned. This manager only borrows access — [initialize] attaches the transport
    // callback, [release] detaches it. Resolved DYNAMICALLY: if the system restarts the MBS
    // mid-run, a cached reference dangles and every update silently early-returns.
    private val mediaSessionEnabled = injectedMediaSessionManager != null
    private val mediaSessionManager: MediaSessionManager?
        get() = if (mediaSessionEnabled && !released) MediaSessionManager.instance() else null

    private var attachedMediaControlCallback: MediaSessionManager.MediaControlCallback? = null

    private var frameIntervalJob: Job? = null

    /** Current phone type. Null when no phone is connected. */
    @Volatile var currentPhoneType: PhoneType? = null
        private set

    // ---- reconnect / escalation -------------------------------------------------------------
    @Volatile private var reconnectJob: Job? = null
    private var reconnectAttempts: Int = 0

    private var hadPriorSession: Boolean = false
    private var consecutiveNoResponse: Int = 0
    private var shortLivedStreamingCount: Int = 0
    private var lastStreamingStartMs: Long = 0L

    // ---- surface debounce -------------------------------------------------------------------
    private var surfaceUpdateJob: Job? = null
    private var pendingSurface: Surface? = null
    private var pendingSurfaceWidth: Int = 0
    private var pendingSurfaceHeight: Int = 0
    private var pendingCallback: Callback? = null

    // ---- media metadata ---------------------------------------------------------------------
    private var lastMediaSongName: String? = null
    private var lastMediaArtistName: String? = null
    private var lastMediaAlbumName: String? = null
    private var lastMediaAppName: String? = null
    private var lastMediaGenre: String? = null
    private var lastMediaComposer: String? = null
    private var lastTrackNumber: Int = 0
    private var lastTrackCount: Int = 0
    private var lastAlbumCover: ByteArray? = null
    private var lastDuration: Long = 0L
    private var lastPosition: Long = 0L

    /** `elapsedRealtime()` when [lastPosition] last came from the phone, so the media card can run it forward; 0 = never. */
    private var lastPositionAtMs: Long = 0L
    private var lastIsPlaying: Boolean = true

    /** The phone's last `playbackStatus`, for [logPlayStatusChange]; null until the first record that carries one. */
    private var lastPlayStatus: Int? = null

    /** A new song's length waiting for its title ([songLength]), and the timeout that publishes it anyway. */
    private var heldLength: Long? = null
    private var heldLengthJob: Job? = null

    /** `elapsedRealtime()` when a song length last arrived; a title change right after it keeps that length ([TrackChangeHold.lengthLedTitle]). */
    private var lastLengthAtMs: Long = 0L

    /**
     * Album art arrives out of band from the track that references it, so it is cached by id
     * until the two meet.
     *
     * Match on `artworkId and 0xFF`: the wire id is ONE byte while `nowPlaying.artworkId` is the
     * full iAP2 integer. A full-int comparison silently dropped every artwork with an id ≥ 256 —
     * a bug already paid for once on the macOS host.
     */
    private val artworkById = HashMap<Int, ByteArray>()

    @Volatile private var pendingArtworkId: Int? = null

    // ---- device list ------------------------------------------------------------------------
    @Volatile private var _pairedDevices: List<DeviceInfo> = emptyList()

    private val deviceListLock = Any()

    // MAC -> elapsedRealtime expiry. Forgetting a device restarts the box's wireless stack, and
    // its device list during that window still contains the device; suppress the MAC from merges
    // until expiry or the card resurrects seconds after the user removed it.
    private val recentlyForgotten = mutableMapOf<String, Long>()

    /**
     * Atomically mutate the device list under [deviceListLock], filtering out recently-forgotten
     * MACs. Returns the committed list.
     */
    private fun mutateDeviceList(mutation: (List<DeviceInfo>) -> List<DeviceInfo>): List<DeviceInfo> {
        synchronized(deviceListLock) {
            val now = SystemClock.elapsedRealtime()
            recentlyForgotten.entries.removeIf { it.value <= now }
            var next = mutation(_pairedDevices)
            if (recentlyForgotten.isNotEmpty()) {
                next = next.filter { it.btMac !in recentlyForgotten }
            }
            _pairedDevices = next
            return next
        }
    }

    /**
     * MACs the box currently holds a bond for, from the last `MGMT_INFO`.
     *
     * Kept SEPARATE from [_pairedDevices] on purpose. That list now also carries history-only
     * entries, so any membership question asked of it would be wrong — most sharply the
     * single-bonded-device heuristic below, which would see two entries for a user with one phone
     * and silently stop marking it Connected.
     */
    @Volatile private var bondedMacs: Set<String> = emptySet()

    @Volatile private var _connectedBtMac: String? = null

    /** The adapter's list of paired wireless devices. */
    val pairedDevices: List<DeviceInfo> get() = _pairedDevices

    /** BT MAC of the currently connected phone (null if none). */
    val connectedBtMac: String? get() = _connectedBtMac

    /** 0 = wired, 1 = wireless, null = unknown. */
    @Volatile var currentWifi: Int? = null
        private set

    /** Clears cached media metadata to prevent stale data on reconnect. */
    private fun clearCachedMediaMetadata() {
        lastMediaSongName = null
        lastMediaArtistName = null
        lastMediaAlbumName = null
        lastMediaAppName = null
        lastMediaGenre = null
        lastMediaComposer = null
        lastTrackNumber = 0
        lastTrackCount = 0
        lastAlbumCover = null
        lastDuration = 0L
        lastPosition = 0L
        lastPositionAtMs = 0L
        lastIsPlaying = true
        lastPlayStatus = null
        nowPlayingState.value = NowPlayingState.NONE
        heldLengthJob?.cancel()
        heldLength = null
        lastLengthAtMs = 0L
        synchronized(artworkById) { artworkById.clear() }
        pendingArtworkId = null
        _connectedBtMac = null
        currentWifi = null
    }

    // LogCallback for Java/legacy components — routes to Logger with proper tags
    private val logCallback =
        object : LogCallback {
            override fun log(message: String) {
                this@CarlinkManager.log(message)
            }

            override fun log(
                tag: String,
                message: String,
            ) {
                Logger.d(message, tag)
            }

            override fun logPerf(
                tag: String,
                message: String,
            ) {
                if (Logger.isDebugLoggingEnabled() && Logger.isTagEnabled(tag)) {
                    Logger.d(message, tag)
                }
            }
        }

    /**
     * Initialize the manager with a Surface and actual surface dimensions.
     *
     * Preconditions and call contract:
     * - Must be called from the main thread.
     * - Safe to call repeatedly (size changes go through a 150 ms debounce).
     * - The callback reference is retained until the next initialize() call or [release].
     * - The FIRST call bypasses the debounce; only subsequent calls coalesce.
     */
    fun initialize(
        surface: Surface,
        surfaceWidth: Int,
        surfaceHeight: Int,
        callback: Callback,
    ) {
        // Round to even — 4:2:0 chroma subsampling cannot express an odd dimension.
        val evenWidth = surfaceWidth and 1.inv()
        val evenHeight = surfaceHeight and 1.inv()

        actualSurfaceWidth = evenWidth
        actualSurfaceHeight = evenHeight

        // Config resolution was pre-computed from stable WindowMetrics in MainActivity. Do NOT
        // override with surface dimensions — SurfaceView size oscillates during Compose layout
        // because systemBars insets apply asynchronously on AAOS.
        logInfo(
            "[RES] Using resolution ${config.width}x${config.height} (surface: ${evenWidth}x$evenHeight)",
            tag = Logger.Tags.VIDEO,
        )

        // Do NOT use reference equality to decide the Surface is "the same". After the app goes
        // to background the Java object may be identical while the native BufferQueue has been
        // destroyed and recreated — rendering into it yields "BufferQueue has been abandoned".
        // Keyed on one-time setup having run, NOT on a live decoder. Under riddleBox the renderer
        // existed from the first call so this branch was equivalent — but the epoch is now only
        // created once a SESSION exists, and MainScreen re-calls initialize() on every surface or
        // container-size change. Without this flag every pre-session call would redo the one-time
        // work below, rebuilding the mic manager and re-attaching the MediaSession callback.
        if (initializedOnce) {
            pendingSurface = surface
            pendingSurfaceWidth = evenWidth
            pendingSurfaceHeight = evenHeight
            pendingCallback = callback

            surfaceUpdateJob?.cancel()
            surfaceUpdateJob =
                scope.launch {
                    delay(SURFACE_DEBOUNCE_MS)

                    val finalSurface = pendingSurface ?: return@launch
                    val finalCallback = pendingCallback ?: return@launch

                    logInfo(
                        "[LIFECYCLE] Surface stabilized at ${pendingSurfaceWidth}x$pendingSurfaceHeight",
                        tag = Logger.Tags.VIDEO,
                    )

                    // Persistent-divergence detector: the decoder runs at the pushed config while
                    // these are the live UI dimensions. A mismatch at first init is an expected
                    // startup transient the debounce coalesces away — but one that SURVIVES the
                    // debounce means the on-screen area genuinely disagrees with the projection
                    // area, i.e. a visible scale/crop mismatch.
                    if (pendingSurfaceWidth != config.width || pendingSurfaceHeight != config.height) {
                        logWarn(
                            "[RES] Surface stabilized at ${pendingSurfaceWidth}x$pendingSurfaceHeight " +
                                "but the decoder is ${config.width}x${config.height} — persistent divergence " +
                                "(Δ=${config.width - pendingSurfaceWidth}x${config.height - pendingSurfaceHeight})",
                            tag = Logger.Tags.VIDEO,
                        )
                    }

                    this@CarlinkManager.callback = finalCallback
                    this@CarlinkManager.videoSurface = finalSurface

                    // A new Surface is a new epoch: retire the old one first so its codec is
                    // released before a second is configured. This VPU's codec pool is small.
                    retireVideoEpoch("surface changed")
                    if (client?.lanes != null) openVideoEpoch(finalSurface)
                }
            return
        }

        this.callback = callback
        this.videoSurface = surface
        initializedOnce = true

        logInfo(
            "[RES] Initializing with surface ${evenWidth}x$evenHeight @ ${config.fps}fps, ${config.dpi}dpi",
            tag = Logger.Tags.VIDEO,
        )

        // Video starts when a session exists AND a Surface is valid; both orders happen.
        codecDeferred = true

        // Load the remembered devices. On IO because it reads a file, and NOT under lifecycleMutex
        // so it cannot serialise against start(). This is what makes the list appear before any
        // adapter session exists — the requirement's "shouldn't wait to populate until a device
        // connects".
        knownStore =
            KnownDeviceStore(context).also { store ->
                scope.launch(Dispatchers.IO) {
                    val snap = store.load()
                    // Seed the in-memory name cache so a session that starts before the first
                    // MGMT_INFO still labels its phone.
                    snap.devices.values.forEach { d -> d.name?.let { phoneNames[d.mac] = it } }
                    withContext(Dispatchers.Main) { rebuildDeviceList() }
                }
            }

        // Drive state is vehicle state, not UI state, so it is owned here rather than in Compose
        // (unlike night mode, which rides isSystemInDarkTheme). The monitor primes itself on
        // connect, and setLimitedUI no-ops until a client exists, so an early edge is not lost —
        // onSessionKeyed re-pushes the current value.
        driveMonitor =
            DriveStateMonitor(context).also { m ->
                m.onDriveStateChanged = { driving -> setLimitedUI(driving) }
                m.start()
            }
        watchCarPlayLimit()
        appFocusClaimer = AppFocusClaimer(context).also { it.start() }
        callNotificationHost = CallNotificationHost(context) { idx -> sendTelephony(idx) }
        callNotificationHost?.setAppVisible(appVisible)

        microphoneManager =
            MicrophoneCaptureManager(context, logCallback).also { mgr ->
                // Wiring this is NOT optional. MicrophoneCaptureManager invokes it and nothing
                // else clears our flag, so without it a fatal AudioRecord error leaves the
                // pipeline dead-but-"capturing": isMicrophoneCapturing stays true, the 20 ms
                // timer keeps pushing, readChunk() returns null forever, and the next gate-on
                // early-returns — Siri or a whole phone call silent until the box cycles the
                // gate. Its contract forbids a synchronous stop() (that would join the calling
                // thread), hence the scope hop.
                mgr.onCaptureError = { why ->
                    scope.launch {
                        logWarn("[MIC] capture died: $why — clearing state", tag = Logger.Tags.AUDIO)
                        stopMicrophoneCapture()
                    }
                }
            }

        // MediaSession lifetime is owned by MainActivity. We only attach our transport-control
        // callback so routed Play/Pause/Skip reach the box. Never construct a MediaSessionManager
        // here — that bypasses the app-scope ownership invariant and resurrects the
        // blank-homescreen-card bug.
        val session = mediaSessionManager
        if (session != null) {
            val transportCallback =
                object : MediaSessionManager.MediaControlCallback {
                    override fun onPlay() {
                        // The car's own play() (boot autoplay, media power back, Media Center) lands
                        // some 40 s before the phone is connected (drive log 10-25 22:06) and used to
                        // be dropped by sendMediaButton for want of a client. Remembered instead, and
                        // delivered when the phone reports its music ([ConnectPlay]).
                        if (state != State.STREAMING) {
                            val from = mediaSessionManager?.lastCommandSender ?: "the car"
                            connectPlay.defer(from)
                            logInfo("[MEDIA] play from $from before CarPlay is up — deferred until the phone reports its music", tag = Logger.Tags.MEDIA_SESSION)
                            return
                        }
                        sendMediaButton(Ocbm.MEDIA_BTN_PLAY)
                    }

                    override fun onPause() {
                        sendMediaButton(Ocbm.MEDIA_BTN_PAUSE)
                    }

                    override fun onStop() {
                        // The car stops the old source when its primary source changes — after a
                        // steering-wheel skip the car also gave to the radio (10-25 11:26), or when
                        // the driver picked another source. The music is left to the phone either
                        // way: if the new source plays, the audio focus loss pauses the phone
                        // (onLanesArmed); if it does not, there is nothing to pause for. Answering
                        // with a Pause here was what silenced the music after a skip (1.00).
                        logInfo("[MEDIA_SESSION] stop from the car (its primary source changed) — music left to the phone; a focus loss pauses it", tag = Logger.Tags.MEDIA_SESSION)
                    }

                    override fun onSkipToNext() {
                        sendMediaButton(Ocbm.MEDIA_BTN_NEXT)
                    }

                    override fun onSkipToPrevious() {
                        sendMediaButton(Ocbm.MEDIA_BTN_PREV)
                    }

                    override fun onPlayPause() {
                        sendMediaButton(Ocbm.MEDIA_BTN_PLAY_PAUSE)
                    }

                    /**
                     * Headset-hook short press is call-aware, the way Android's own dispatcher
                     * treats it: answer a ringing call, otherwise toggle playback. It never ENDS a
                     * call — a mis-press hanging up is worse than one pausing music.
                     */
                    override fun onHeadsetHook() {
                        if (callPresentation is CallRoster.Presentation.Incoming) {
                            sendTelephony(Ocbm.TEL_ANSWER)
                        } else {
                            sendMediaButton(Ocbm.MEDIA_BTN_PLAY_PAUSE)
                        }
                    }

                    override fun onVoiceAssist() {
                        requestSiri()
                    }
                }
            attachedMediaControlCallback = transportCallback
            session.setMediaControlCallback(transportCallback)
            logInfo("MediaSession transport callback attached", tag = Logger.Tags.ADAPTR)
        } else {
            logInfo("MediaSession not provided (owner skipped)", tag = Logger.Tags.ADAPTR)
        }

        logInfo("CarlinkManager initialized (OCBM)", tag = Logger.Tags.ADAPTR)
        checkCallPathIfNeeded()
    }

    /**
     * Start the OCBM session.
     *
     * Runs under [lifecycleMutex] on Dispatchers.IO: the body performs blocking USB opens and
     * writes. Main-safe to call from anywhere.
     */
    suspend fun start() =
        lifecycleMutex.withLock {
            withContext(Dispatchers.IO) { startImpl() }
        }

    /**
     * [start], but only if nothing has started meanwhile — checked under the lifecycle lock, so two
     * triggers for the same attach (the broadcast and an attach intent, or the reboot fallback) can't
     * tear down the session the first one just began ([startImpl] restarts a live client).
     */
    suspend fun startIfDisconnected(reason: String) =
        lifecycleMutex.withLock {
            if (released || state != State.DISCONNECTED) {
                logInfo("[USB] $reason — state=$state, not starting", tag = Logger.Tags.USB)
            } else {
                logInfo("[USB] $reason — connecting", tag = Logger.Tags.USB)
                cancelReconnect()
                withContext(Dispatchers.IO) { startImpl() }
            }
        }

    /**
     * Find the adapter and open it through the USB bridge, or null if we should abandon the start.
     *
     * The bridge is required: Carlink3 has no USB access of its own (removed 2026-10-21, the way
     * Carlink2 did in [204]), so it never holds a USB permission or shows a USB dialog. Without the
     * bridge there is nothing to retry: the start stops at "Install the Carlink USB bridge" (the
     * connecting screen shows its setup panel) and MainActivity starts again once it is installed.
     *
     * The [released] re-checks in [acquireBridgeTransport] matter: finding the adapter can take
     * ~30 s and the permission prompt up to 30 s more, and `release()` runs OUTSIDE [lifecycleMutex],
     * so the flag can flip under an in-flight start. Without them a released manager goes on to open
     * the adapter and bring up a full session — a zombie holding it against its own replacement.
     */
    private suspend fun acquireTransport(): RawBulkTransport? {
        if (CarlinkBridge.isInstalled(context)) return acquireBridgeTransport()
        logWarn("[BRIDGE] USB bridge not installed — Carlink3 can't reach the adapter without it", tag = Logger.Tags.USB)
        cancelReconnect() // nothing changes by retrying until it is installed
        handleFailedStart(INSTALL_BRIDGE_STATUS)
        return null
    }

    /**
     * The sideloaded bridge (`android.car.usb.handler`, module `:bridge`) holds the USB permission the
     * platform grants it silently on every attach and relays the raw bulk stream over a socket, so
     * this app never asks for USB permission itself and stays Play-shippable. Device discovery only
     * needs `UsbManager.deviceList`, which is readable without permission. A boot-time attach the
     * platform never granted gets the bridge's one-tap prompt ([ICarlinkBridge.requestPermission]).
     */
    private suspend fun acquireBridgeTransport(): RawBulkTransport? {
        val dev = awaitAdapterListed()
        if (dev == null) {
            logError("Failed to find the OCBM adapter", tag = Logger.Tags.USB)
            handleFailedStart("Adapter not found")
            return null
        }
        if (released) return null
        val bridge = bindRawStreamBridge() ?: return null
        if (!awaitBridgePermission(bridge, dev.deviceName)) {
            // The bridge asks once per attach and a denial sticks until the adapter re-enumerates.
            handleFailedStart("USB permission denied — unplug and replug the adapter")
            return null
        }
        return openViaBridge(bridge, dev.deviceName)
    }

    /** The adapter from `UsbManager.deviceList` (no permission needed), polled for up to ~30 s. */
    private suspend fun awaitAdapterListed(): android.hardware.usb.UsbDevice? {
        var attempts = 0
        while (attempts < USB_FIND_ATTEMPTS) {
            BridgeBulkTransport.findAdapter(context)?.let { return it }
            attempts++
            delay(USB_WAIT_PERIOD_MS)
        }
        return null
    }

    /** Bind the bridge and require the raw-stream API; reports the failure and returns null otherwise. */
    private suspend fun bindRawStreamBridge(): ICarlinkBridge? {
        val bridge = CarlinkBridge.connect(context)
        if (bridge == null) {
            logError("[BRIDGE] bind failed: ${CarlinkBridge.lastFailure}", tag = Logger.Tags.USB)
            handleFailedStart("USB bridge isn't responding")
            return null
        }
        val api = runCatching { bridge.apiVersion }.getOrDefault(-1)
        if (api < BridgeContract.RAW_STREAM_API_VERSION) {
            logError(
                "[BRIDGE] API $api is older than the raw-stream API ${BridgeContract.RAW_STREAM_API_VERSION}",
                tag = Logger.Tags.USB,
            )
            handleFailedStart("USB bridge is out of date")
            return null
        }
        return bridge
    }

    /**
     * True once the bridge holds USB permission for [name]; asks once (one-tap prompt) and waits for
     * the answer.
     *
     * While the prompt may be on screen, [UsbPermissionPrompt] is set so MainActivity keeps its
     * cluster activity off it, and the status asks the driver to allow it. The wait follows the
     * prompt itself ([awaitPromptAnswer]) rather than a fixed 10 s: a prompt still open when the
     * old timer ran out was abandoned while on screen, and the cluster launch it had been holding
     * then covered it.
     */
    private suspend fun awaitBridgePermission(
        bridge: ICarlinkBridge,
        name: String,
    ): Boolean {
        if (awaitGrant(bridge, name, BRIDGE_GRANT_GRACE_POLLS)) return true
        val prompt = runCatching { bridge.requestPermission(name) }.getOrDefault(BridgeContract.PERMISSION_DENIED)
        logInfo("[BRIDGE] no USB permission for $name — prompt result $prompt; waiting for an answer", tag = Logger.Tags.USB)
        UsbPermissionPrompt.set(true)
        setStatusText(ALLOW_USB_STATUS)
        val granted =
            try {
                awaitPromptAnswer(bridge, name)
            } finally {
                UsbPermissionPrompt.set(false)
            }
        if (!granted) {
            logError("[BRIDGE] still no USB permission for $name — ${runCatching { bridge.status }.getOrNull()}", tag = Logger.Tags.USB)
        }
        return granted
    }

    /**
     * Wait while the bridge's prompt is unanswered: until the bridge holds permission, the prompt
     * was denied (or the adapter left), or [BRIDGE_PROMPT_WAIT_MS] passes. After the first call,
     * `requestPermission` only reports the state for that device instance (PENDING until answered),
     * so polling it shows nothing new to the driver.
     */
    private suspend fun awaitPromptAnswer(
        bridge: ICarlinkBridge,
        name: String,
    ): Boolean {
        val deadline = android.os.SystemClock.elapsedRealtime() + BRIDGE_PROMPT_WAIT_MS
        while (android.os.SystemClock.elapsedRealtime() < deadline && !bridge.hasPermission(name)) {
            val state = runCatching { bridge.requestPermission(name) }.getOrDefault(BridgeContract.PERMISSION_DENIED)
            if (state == BridgeContract.PERMISSION_DENIED || state == BridgeContract.PERMISSION_NO_DEVICE) break
            delay(BRIDGE_PERMISSION_POLL_MS)
        }
        return bridge.hasPermission(name)
    }

    /** True as soon as the bridge holds permission for [name], checking up to [polls] times. */
    private suspend fun awaitGrant(
        bridge: ICarlinkBridge,
        name: String,
        polls: Int,
    ): Boolean {
        repeat(polls) {
            if (bridge.hasPermission(name)) return true
            delay(BRIDGE_PERMISSION_POLL_MS)
        }
        return bridge.hasPermission(name)
    }

    /** Open [name] through the bridge and wrap the returned socket; null (reported) on failure or if released meanwhile. */
    private fun openViaBridge(
        bridge: ICarlinkBridge,
        name: String,
    ): RawBulkTransport? {
        setStatusText("Adapter found — opening...")
        val stream = runCatching { bridge.open(name) }.getOrNull()
        if (stream == null) {
            logError("[BRIDGE] open failed — ${runCatching { bridge.status }.getOrNull()}", tag = Logger.Tags.USB)
            handleFailedStart("USB bridge could not open the adapter")
            return null
        }
        val t = BridgeBulkTransport(bridge, stream, name, usbLog)
        if (released) {
            logWarn("[LIFECYCLE] released during bridge open — closing and abandoning", tag = Logger.Tags.ADAPTR)
            t.stop()
            return null
        }
        logInfo("[BRIDGE] ${t.bridgeStatus()}", tag = Logger.Tags.USB)
        return t
    }

    private suspend fun startImpl() {
        if (released) {
            logWarn("[LIFECYCLE] start() on a released manager — ignoring", tag = Logger.Tags.ADAPTR)
            return
        }

        // Tear down any existing session BEFORE entering CONNECTING, so the FGS started at
        // CONNECTING isn't immediately stopped by the teardown's DISCONNECTED transition.
        if (client != null) stopImpl()

        setState(State.CONNECTING)
        setStatusText("Searching for adapter...")

        val t = acquireTransport() ?: return

        transport = t
        val c = OcbmClient(t, ocbmLog)
        // Stable for this manager's whole life, NOT per client: a reattach after a USB blip is the same
        // host and should warm-reuse the box's live session, whereas a new process must force a re-arm.
        c.instanceNonce = instanceNonce
        client = c
        wireClientCallbacks(c)
        c.start()

        // CT_HELLO, retransmitted until ACK. Failure here is Pattern A's trigger — keep the
        // "no initial response" substring, which remote log filters match on.
        setStatusText("Connecting to adapter...")
        if (!c.hello()) {
            handleError("no initial response — no CT_HELLO_ACK from the adapter")
            return
        }

        // The box has no RTC battery, so its clock is bogus at every boot and CarPlay's TLS
        // pairing needs a real one. Non-fatal: a wrong clock degrades pairing, a missing
        // SETTIME ack does not mean the link is bad.
        c.setTime()

        // THE APP IS THE IGNITION: this is the presence latch that brings the box's radios up.
        setStatusText("Starting projection...")
        if (!c.subscribe(VehicleConfigYaml.renderBytes(vehicleConfigSpec()))) {
            handleError("usb — CT_SUBSCRIBE write failed")
            return
        }
        c.startHeartbeat()
        armBoxLogStream(c)
        syncPhonePolicyAfterSubscribe()

        setStatusText("Waiting for phone...")
    }

    /**
     * The config document pushed on SUBSCRIBE.
     *
     * Derived from [config] so the decoder, the box's `/info` advertisement and the coordinate
     * space the box scales touch into all come from ONE number and cannot drift.
     */
    private fun vehicleConfigSpec(): VehicleConfigSpec {
        // Detected panel first (DisplayProfile.geometry, already clamped and legalised); the stored
        // AdapterConfig only fills in when no profile exists. The spec's own checks are a backstop.
        val g = displayProfile?.geometry ?: PanelGeometry.fromConfig(config)
        // Advertise HEVC only when this head unit can actually decode it at the negotiated geometry:
        // `enablesHEVC` drives the box's `hevcInfo` lever (carplayd main.rs), and a stream iOS
        // encodes in a codec the decoder cannot take is a black screen with no error anywhere.
        val caps = VideoCodecs.probe(g.width, g.height, g.maxFps)
        // Settings › Cluster › Ask iPhone for lanes: the proven set plus lane guidance only.
        val askLanes = LaneGuidanceStore.get(context).current.ask
        // Settings › Display › Layout: CarPlay's side of the split, or null for the whole panel. A
        // panel with no legal area (below the floor) stays full screen rather than failing.
        val layout = LayoutStore.get(context).current
        val split = layout.carPlayArea(g.width, g.height)
        declaredSplitArea = split
        val driveSide = DriveSideStore.get(context).current
        if (layout.split) {
            val area = split?.let { "${it.width}x${it.height}@${it.x},${it.y}" } ?: "NONE (illegal for this panel; full screen)"
            logInfo("[VIEW_AREA] layout ${layout.layout.name} ${layout.width.percent}%: CarPlay area $area", tag = Logger.Tags.VIDEO)
        }
        logInfo(
            "[CONFIG] decoders at ${g.width}x${g.height}@${g.maxFps}: h264=${caps.h264Decoder ?: "NONE"} " +
                "hevc=${caps.hevcDecoder ?: "NONE"} -> enablesHEVC=${caps.hevc}",
            tag = Logger.Tags.VIDEO,
        )
        val spec =
            VehicleConfigSpec(
                enablesHevc = caps.hevc,
                name = config.boxName,
                width = g.width,
                height = g.height,
                maxFps = g.maxFps,
                safeOriginX = g.safe.x,
                safeOriginY = g.safe.y,
                safeWidth = g.safe.width,
                safeHeight = g.safe.height,
                // Wallpaper under the cutout, interactive UI inside the safe rect — see PanelGeometry.
                drawUIOutsideSafeArea = g.drawUiOutsideSafeArea,
                dpi = g.dpi,
                diagonalInches = g.diagonalInches,
                // The OEM icon is the CarPlay home-screen tile that returns the driver here; its tap
                // comes back as an inbound `requestUI` (see onCommandPlist). Rendered once and cached:
                // it is three PNG encodes, and this runs on the connect path.
                oemIconImages = oemIconImages,
                oemIconLabel = context.getString(R.string.app_name),
                rightHandDrive = driveSide == DriveSide.RIGHT,
                viewArea2 = split,
                viewAreaAnimMs = if (split != null) SplitViewArea.TRANSITION_MS else null,
                metadataTier = if (askLanes) "extended" else "proven",
                metadataSkip = if (askLanes) VehicleConfigSpec.LANES_ONLY_SKIP else emptyList(),
            )
        dockOnRight = spec.rightHandDrive
        pushedSettings = SessionSettings(displayMode, layout, driveSide, askLanes)
        LaneActivity.noteAsked(askLanes)
        if (askLanes) logWarn("[LANES] asking the iPhone for lane guidance: tier extended, skip ${VehicleConfigSpec.LANES_ONLY_SKIP}", tag = Logger.Tags.NAVI)
        logInfo("[CONFIG] ${g.describe()}", tag = Logger.Tags.ADAPTR)
        return spec
    }

    /** Lazily rendered once per manager; the launcher icon cannot change under a running process. */
    private val oemIconImages: List<OemIcon.Image> by lazy { OemIcon.render(context) }

    /**
     * Stop and disconnect.
     *
     * @param reboot when true, also asks the box to reboot. Expect ~50 s of device absence and a
     *   fresh Android USB permission grant afterwards; grants are per-attachment.
     */
    fun stop(reboot: Boolean = false) {
        // Synchronous by contract (release()/onDestroy needs teardown before returning).
        stopImpl(reboot)
    }

    /**
     * @param preserveEscalation keep Pattern A/C context across the stop. Adapter-initiated
     *   restarts are exactly the "phone left and may not return" scenario the escalation exists
     *   to diagnose; user-initiated stops keep the full reset.
     */
    private fun stopImpl(
        reboot: Boolean = false,
        preserveEscalation: Boolean = false,
    ) {
        logDebug("[LIFECYCLE] stop()", tag = Logger.Tags.VIDEO)
        cancelDelayedKeyframe()
        cancelReconnect()
        OcbmNavigationFeed.clear() // Carlink3: drop the route so the cluster does not show a stale trip
        // Always clear the streaming-start timestamp: with preserveEscalation a stale value
        // would let the NEXT error measure "session duration" from the PREVIOUS session's start,
        // producing phantom Pattern C increments for attempts that never reached STREAMING.
        lastStreamingStartMs = 0L
        if (!preserveEscalation) {
            hadPriorSession = false
            consecutiveNoResponse = 0
            shortLivedStreamingCount = 0
        }
        currentPhoneType = null
        currentWifi = null
        codecDeferred = true
        callback?.onPhoneTypeChanged(PhoneType.UNKNOWN)
        clearCachedMediaMetadata()
        clearSessionUiState()
        stopMicrophoneCapture()

        if (reboot) {
            // Manual verb only, never automated: the device disappears for ~50 s and
            // re-enumerates, and Android's USB permission grant does not survive that.
            logWarn("[LIFECYCLE] MGMT_REBOOT requested", tag = Logger.Tags.ADAPTR)
            runCatching { client?.mgmtAction(Ocbm.MGMT_REBOOT) }
        }

        retireVideoEpoch("session stopping")
        releaseAudio()

        // CT_STOP is a session-end indicator: the box goes idle IMMEDIATELY (ocbmd `go_idle`;
        // STOP_GRACE was removed 2026-09-03, docs/ops/06_CORRECTIONS_LEDGER.md). Nothing is reused
        // by a relaunch — the next SUBSCRIBE is a fresh session.
        client?.stop()
        client = null
        transport?.stop()
        transport = null

        setState(State.DISCONNECTED)
        setStatusText("Disconnected")
    }

    /**
     * User-initiated Disconnect Adapter / Close App: [stopImpl] under the lifecycle mutex on IO,
     * so it neither blocks the main thread on USB teardown nor interleaves with a start() in
     * flight. [stop] stays synchronous for [release] / onDestroy, which need teardown before they
     * return.
     */
    suspend fun disconnect() =
        lifecycleMutex.withLock {
            withContext(Dispatchers.IO) {
                logInfo("[LIFECYCLE] disconnect() — user-initiated", tag = Logger.Tags.ADAPTR)
                stopImpl()
            }
        }

    /**
     * Settings → Adapter → Save Logs: pull the box's `/tmp/box.log` over the live link (it lives in
     * RAM on the box and is gone at power-off, so this is the only way to keep it after a drive) and
     * save it next to this process's own logcat under Downloads/Carlink3. Returns where they went.
     */
    suspend fun saveLogs(): Result<String> =
        withContext(Dispatchers.IO) {
            val c = client ?: return@withContext Result.failure(IllegalStateException("Adapter not connected"))
            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            logInfo("[LOGS] pulling $BOX_LOG_PATH", tag = Logger.Tags.ADAPTR)
            val box = c.pullFile(BOX_LOG_PATH).getOrElse { return@withContext Result.failure(it) }
            val saved = ArrayList<String>(6)
            saved += LogExport.save(context, "carlink3-box-$stamp.log", box).getOrElse { return@withContext Result.failure(it) }
            // The per-daemon logs, best effort: a daemon that never ran has no file, which is fine.
            for ((path, label) in BOX_DAEMON_LOGS) {
                val pulled = c.pullFile(path, timeoutMs = 10_000)
                pulled.onSuccess { bytes -> LogExport.save(context, "carlink3-$label-$stamp.log", bytes).onSuccess { saved += it } }
                pulled.onFailure { logInfo("[LOGS] $path: ${it.message}", tag = Logger.Tags.ADAPTR) }
            }
            // The app's own INFO+ trail, independent of logcat and its filters (Logger.recordTrail).
            // Stamped with the flag states so a thin logcat file can be told from a filter problem.
            logWarn(
                "[LOGS] saving trail: verboseInRelease=${Logger.isReleaseVerbose()} debugLogging=${Logger.isDebugLoggingEnabled()} " +
                    "clusterNav=${ClusterNavigation.isEnabled(context)} state=$state",
                tag = Logger.Tags.ADAPTR,
            )
            val trail = LogTrail.snapshot().toByteArray(Charsets.UTF_8)
            saved += LogExport.save(context, "carlink3-trail-$stamp.log", trail).getOrElse { return@withContext Result.failure(it) }
            LogExport.appLogcat()?.let { app ->
                saved += LogExport.save(context, "carlink3-app-$stamp.log", app).getOrElse { return@withContext Result.failure(it) }
            }
            logInfo("[LOGS] saved ${saved.joinToString(", ")} (box log ${box.size} bytes, trail ${trail.size} bytes)", tag = Logger.Tags.ADAPTR)
            Result.success(saved.joinToString(" and "))
        }

    /**
     * `MGMT_RESTART_WIRELESS`: bounce the box's wireless stack (btd + radios; the box re-advertises).
     *
     * Drops a WIRELESS phone; a wired phone is untouched — the supervisor's `wireless_down` leaves
     * a wired-owned carplayd running (tools/session_supervisor.sh, the "carplayd are wired-owned"
     * branch). Same verb and semantics as MacHost's "Restart wireless stack".
     */
    fun restartWireless() {
        logWarn("[LIFECYCLE] MGMT_RESTART_WIRELESS requested", tag = Logger.Tags.ADAPTR)
        setStatusText("Restarting wireless...")
        scope.launch(Dispatchers.IO) {
            val status = client?.mgmtAction(Ocbm.MGMT_RESTART_WIRELESS)
            logInfo("[LIFECYCLE] RESTART_WIRELESS status=$status", tag = Logger.Tags.ADAPTR)
        }
    }

    /**
     * Forward the AAOS day/night state to CarPlay mid-session, so CarPlay's own UI follows the
     * head unit's theme.
     *
     * `setNightMode` is global and advisory — one input alongside iOS's own appearance logic. The
     * explicit per-display commands are sent too, so the flip is deterministic rather than
     * depending on the user's iPhone-side "Automatic" preference.
     */
    fun setNightMode(night: Boolean) {
        logInfo("[NIGHT] AAOS night=$night", tag = Logger.Tags.ADAPTR)
        val c = client ?: return
        scope.launch(Dispatchers.IO) {
            c.sendNightMode(night)
            c.sendAppearance(dark = night, isMap = false)
            c.sendAppearance(dark = night, isMap = true)
        }
    }

    /**
     * Switch CarPlay to declared view area [index] — 0 the whole panel, 1 the split layout's CarPlay
     * area — the deterministic form of the Dock resize button (`CMD_VIEW_AREA`). The box answers
     * `updateViewArea` and iOS animates the crop; [viewArea] follows from the stream's own header.
     * The box refuses an index the pushed config did not declare, so this is a no-op in full screen.
     */
    fun requestViewArea(index: Int) {
        val c = client ?: return
        logInfo("[VIEW_AREA] requesting view area $index", tag = Logger.Tags.VIDEO)
        scope.launch(Dispatchers.IO) { c.sendCommand(Ocbm.CMD_VIEW_AREA, index.toByte()) }
    }

    /**
     * Bring a CarPlay app forward on the main screen by URL (`requestUI` with `url`): `nowplaying:` for
     * the app that is playing when the pane's source name is tapped, `maps:` to put Maps back. Which
     * URLs iOS honours on this box is being benched (Settings › Troubleshoot in debug builds).
     */
    fun openCarPlayApp(url: String) {
        val c = client ?: return
        logInfo("[CARPLAY_UI] requestUI url=$url", tag = Logger.Tags.ADAPTR)
        scope.launch(Dispatchers.IO) { c.sendRequestUiUrl(url) }
    }

    /**
     * A finger lifted from CarPlay at [x], [y] (0..1 of the frame), from MainScreen's touch forwarding.
     * A lift on the Dock counts in [dockTaps]. Lifts out to twice the Dock's taken width are logged with
     * their depth, so a drive log shows where the Dock really ends ([CarPlayDock.SHARE] is not measured).
     */
    fun onCarPlayTap(
        x: Float,
        y: Float,
    ) {
        val area = viewAreaState.value ?: return
        val depth = CarPlayDock.depth(area, dockOnRight, x)
        val width = CarPlayDock.width(area)
        if (depth < 0f || depth > width * 2) return
        val onDock = CarPlayDock.contains(area, dockOnRight, x, y)
        logInfo("[CARPLAY_UI] tap ${depth.toInt()} px in from the Dock edge (Dock taken as ${width.toInt()} px): ${if (onDock) "Dock" else "not Dock"}", tag = Logger.Tags.ADAPTR)
        if (onDock) dockTapsState.value += 1
    }

    /** A transport button from the pane's media card: the same HID tap the steering wheel and the media session send. */
    fun mediaControl(control: MediaControl) {
        logInfo("[UI_ACTION] pane media ${control.name}", tag = Logger.Tags.MEDIA_SESSION)
        sendMediaButton(control.button)
    }

    /**
     * Limit CarPlay while driving flipped mid-drive, or a different iPhone's choice loaded: push the
     * result now, not at the next gear change.
     */
    private fun watchCarPlayLimit() {
        scope.launch {
            CarPlayLimitStore.get(context).flow.drop(1).collect {
                driveMonitor?.let { m -> if (m.connected) setLimitedUI(m.driving) }
            }
        }
    }

    /**
     * Restrict the CarPlay UI as if the vehicle were in Drive, or release it.
     *
     * The box turns this into `/command setLimitedUI {limitedUI: <bool>}`
     * (`airplayd/src/main.rs:1065-1066`), which is a runtime command — no reconnect, no `/info`
     * change. iOS hides the on-screen keyboard, the phone keypad and long scrollable lists.
     *
     * WHICH elements get limited is the pushed config's `limitedUIConfig`; this is only the
     * on/off. We push no `limitedUIConfig` today, so iOS applies its own defaults.
     *
     * Settings › Display › Limit CarPlay while driving off ([CarPlayLimitStore]): never limited, so
     * CarPlay stays as it is when parked.
     */
    fun setLimitedUI(driving: Boolean) {
        val limitOn = CarPlayLimitStore.get(context).limitWhileDriving
        val limited = driving && limitOn
        logInfo("[DRIVE] limitedUI=$limited (driving=$driving${if (limitOn) "" else ", limit while driving off"})", tag = Logger.Tags.ADAPTR)
        val c = client ?: return
        scope.launch(Dispatchers.IO) {
            c.sendCommand(if (limited) Ocbm.CMD_LIMITED_UI_ON else Ocbm.CMD_LIMITED_UI_OFF)
        }
    }

    // ==================== Device Management ====================

    /**
     * Ask the box for a fresh device snapshot.
     *
     * Blocking with a 5 s timeout box-side, so never called from a property getter — `PhonesTab`
     * polls those at 1 Hz.
     */
    fun refreshDeviceList() {
        logInfo("[DEVICE_MGMT] Requesting device snapshot", tag = Logger.Tags.ADAPTR)
        scope.launch(Dispatchers.IO) { pollBoxInfo() }
    }

    private fun pollBoxInfo() {
        val json = client?.mgmtGetInfo() ?: return
        val info = runCatching { JSONObject(json) }.getOrNull() ?: return

        val macs = info.optJSONArray("devices")
        if (macs != null) {
            val bonded =
                (0 until macs.length())
                    .mapNotNull { i -> macs.optString(i).takeIf { it.isNotEmpty() }?.lowercase() }
                    .toSet()
            bondedMacs = bonded

            // Learn any bond we have never seen — a fresh install over existing bonds, or app data
            // cleared while the box kept its link keys. It shows as a bare MAC until the phone next
            // connects and CT_PHONE_IDENT names it. Suppressed MACs are deliberately NOT learned:
            // the box's snapshot lags its own forget, and persisting one here would defeat the
            // suppression window permanently.
            val suppressed = synchronized(deviceListLock) { recentlyForgotten.keys.toSet() }
            val unknown = bonded - (knownStore?.snapshot?.devices?.keys ?: emptySet()) - suppressed
            if (unknown.isNotEmpty()) {
                val now = System.currentTimeMillis()
                knownStore?.update { snap ->
                    snap.copy(devices = snap.devices + unknown.associateWith { KnownDevice(mac = it, firstSeenMs = now) })
                }
            }
            rebuildDeviceList()
        }

        val wireless = info.optString("transport") == "wireless"
        val phonePresent = info.optBoolean("phone_present", false)
        currentWifi =
            if (wireless) {
                1
            } else if (phonePresent) {
                0
            } else {
                null
            }

        // `CT_PHONE_IDENT` names the live phone outright, so prefer it. The heuristic below is
        // the fallback for a box that has not sent one yet.
        connectedPhoneMac?.let { _connectedBtMac = it }

        // Single-bonded-device heuristic: MGMT_INFO reports no connected MAC, so with two or more
        // BONDED phones the "Connected" card will be wrong. Asked of `bondedMacs`, never of
        // `_pairedDevices` — the latter now includes history-only entries, which would make
        // `singleOrNull()` null for a one-phone user and silently kill the highlight.
        if (_connectedBtMac == null && (wireless || phonePresent)) {
            bondedMacs.singleOrNull()?.let { _connectedBtMac = it }
        }
    }

    // There is deliberately NO connectToDevice(): OCBM has no targeted-connect verb, and the
    // previous implementation sent MGMT_RESTART_WIRELESS in its place — wrong with more than one
    // bonded phone, and a full radio bounce for a wired one. The Phones cards are information-only
    // until the box grows the verb (btd's control port already has `{"cmd":"connect","address"}`,
    // crates/vendor/wireless/src/control.rs; the MGMT relay is the missing piece —
    // docs/ops/04_OPEN_ITEMS.md).

    /** True when the box currently holds a link key for [btMac] (from the last MGMT_INFO). */
    fun isBonded(btMac: String): Boolean = btMac.lowercase() in bondedMacs

    /**
     * Remove a device.
     *
     * A BONDED record is forgotten on the box too (`MGMT_FORGET_DEVICE`; the box restarts wireless
     * as part of the forget, which drops a live wireless session). An UNBONDED record — one the app
     * remembers but the box holds no link key for — is a LOCAL history delete only: the box has
     * nothing to forget, and its FORGET_DEVICE handler unconditionally restarts wireless
     * (`ccpa/ocbmd/src/main.rs`, the `MGMT_FORGET_DEVICE` arm), so sending it would bounce the
     * radios for nothing.
     */
    fun forgetDevice(btMac: String) {
        val mac = btMac.lowercase()
        val bonded = mac in bondedMacs
        logInfo("[DEVICE_MGMT] Forget device: $btMac (bonded=$bonded${if (bonded) "" else " — local history delete, nothing sent"})", tag = Logger.Tags.ADAPTR)

        // Optimistically remove for a responsive UI, and suppress the MAC from merges while the
        // box processes the forget — its snapshot still contains the device for a while.
        synchronized(deviceListLock) {
            recentlyForgotten[mac] = SystemClock.elapsedRealtime() + FORGET_SUPPRESS_MS
        }
        // Delete the remembered record in the same breath. `recentlyForgotten` is in-memory and
        // elapsedRealtime-based, so it cannot cover storage: without this the device returns when
        // the window expires, or on the next app launch.
        knownStore?.update { snap ->
            snap.copy(
                devices = snap.devices - mac,
                preferredMac = snap.preferredMac?.takeIf { it != mac },
                priority = snap.priority - mac,
            )
        }
        bondedMacs = bondedMacs - mac
        // Its Cluster and Driving settings go with it ([DriverProfiles]).
        DriverProfiles.forget(context, mac)
        mutateDeviceList { list -> list.filter { it.btMac.lowercase() != mac } }
        callback?.onDeviceListChanged(_pairedDevices)
        notifyDeviceListeners()

        if (!bonded) return
        scope.launch(Dispatchers.IO) {
            val status = client?.mgmtAction(Ocbm.MGMT_FORGET_DEVICE, btMac.toByteArray(Charsets.US_ASCII))
            logInfo("[DEVICE_MGMT] FORGET_DEVICE($btMac) status=$status", tag = Logger.Tags.ADAPTR)
            delay(1000)
            pollBoxInfo()
        }
    }

    /**
     * The clean-slate reset behind BOTH Settings ▸ Reset Connection and the overlay's Reset Device.
     *
     * CT_STOP → close OCBM → release the USB interface → wait for the box's own session-end
     * lifecycle → reclaim USB → HELLO with a NEW instance nonce → SUBSCRIBE. The sequence, the
     * stage order and the wait's derivation live in [CleanSlateReset]; this is only its binding
     * to the manager. Runs entirely under [lifecycleMutex] so nothing (a USB-attach start, a
     * link-dead error) can interleave with the half-torn-down state.
     *
     * Ending the phone session is intended: the box tears projection down on the GONE edge
     * (`tools/session_supervisor.sh` `teardown`), and the new session re-arms it.
     */
    suspend fun cleanSlateReset(): Boolean {
        if (released) return false
        return lifecycleMutex.withLock {
            withContext(Dispatchers.IO) { cleanSlate.run() }
        }
    }

    /**
     * Ends the session for a rebuild with new settings (Settings' "Reset now", owner 10-28): the
     * clean-slate reset's stop and box settle, without its reconnect, because a new manager connects
     * next with a new instance nonce. [resetInFlight] stays true from here on, so the deferred
     * display-mode apply does not rebuild while the box is still ending the phone session.
     */
    suspend fun endForRebuild() {
        if (released) return
        endingForRebuild = true
        lifecycleMutex.withLock {
            withContext(Dispatchers.IO) {
                stopImpl(preserveEscalation = true)
                setStatusText("Applying settings...")
            }
            delay(CleanSlateReset.BOX_TEARDOWN_SETTLE_MS)
        }
    }

    @Volatile private var endingForRebuild = false

    /** True while [cleanSlateReset] or [endForRebuild] is in flight — the UI keeps its spinner on this. */
    val resetInFlight: Boolean get() = cleanSlate.isInFlight || endingForRebuild

    private val cleanSlate =
        CleanSlateReset(
            object : CleanSlateReset.Steps {
                override fun stopSession() {
                    // Escalation context preserved: a reset is diagnostic recovery, not a fresh
                    // install — the NEXT failure should still read as "after a prior session".
                    stopImpl(preserveEscalation = true)
                }

                override fun rotateInstanceNonce(): Int {
                    val old = instanceNonce
                    instanceNonce = HostInstanceNonce.fresh(previous = old)
                    logInfo(
                        "[LIFECYCLE] host instance nonce rotated %#010x -> %#010x".format(old, instanceNonce),
                        tag = Logger.Tags.ADAPTR,
                    )
                    return instanceNonce
                }

                override suspend fun reconnect() {
                    if (released) {
                        logWarn("[LIFECYCLE] released during clean-slate wait — not reconnecting", tag = Logger.Tags.ADAPTR)
                        return
                    }
                    startImpl()
                }

                override fun onStage(stage: CleanSlateReset.Stage) {
                    logInfo("[LIFECYCLE] clean-slate reset: $stage", tag = Logger.Tags.ADAPTR)
                    when (stage) {
                        CleanSlateReset.Stage.STOPPING -> setStatusText("Resetting...")
                        CleanSlateReset.Stage.WAITING_FOR_ADAPTER -> setStatusText("Waiting for adapter...")
                        CleanSlateReset.Stage.RECONNECTING -> setStatusText("Reconnecting...")
                        CleanSlateReset.Stage.DONE -> Unit // startImpl set the live text
                    }
                }
            },
        )

    private fun sendMediaButton(index: Byte) {
        val c = client ?: return
        if (index == Ocbm.MEDIA_BTN_NEXT || index == Ocbm.MEDIA_BTN_PREV) skipAftermath.skipSent(SystemClock.elapsedRealtime())
        scope.launch(Dispatchers.IO) { c.sendMediaButton(index) }
    }

    /**
     * The window after a skip this app sent in which a focus loss or a car-issued stop is the same
     * press reaching another source too, not the driver changing source ([SkipAftermath]).
     */
    private val skipAftermath = SkipAftermath()

    /** The one Play pressed for the phone at connect: the car's deferred `play()`, or the resume setting. */
    private val connectPlay = ConnectPlay(resumeOnConnect = { MusicStore.get(context).resumeOnConnect })
    private var connectPlayJob: Job? = null

    /**
     * The first status of a session: a stopped phone may get one Play ([ConnectPlay]). True when it
     * was pressed; the session then reports PLAYING at once, as the car asked, so the connect-time
     * stop never becomes the state the car persists. If the phone has not started within
     * [CONNECT_PLAY_GRACE_MS] the report is taken back.
     */
    private fun connectPlayFor(playStatus: Int?): Boolean {
        val reason = playStatus?.let { connectPlay.onStatus(it) } ?: return false
        logInfo("[MEDIA] $reason: pressing Play for the phone, which reports its music stopped", tag = Logger.Tags.MEDIA_SESSION)
        sendMediaButton(Ocbm.MEDIA_BTN_PLAY)
        connectPlayJob?.cancel()
        connectPlayJob =
            scope.launch {
                delay(CONNECT_PLAY_GRACE_MS)
                if (PlayStatus.stopped(lastPlayStatus)) {
                    logInfo("[MEDIA] the phone did not start within ${CONNECT_PLAY_GRACE_MS}ms of Play — reporting it paused", tag = Logger.Tags.MEDIA_SESSION)
                    lastIsPlaying = false
                    pushPlaybackState()
                }
            }
        return true
    }

    // ==================== Siri / telephony (3P shell) ====================

    /**
     * Trigger Siri: the `CMD_SIRI_DOWN`/`CMD_SIRI_UP` hold pair as one tap, exactly what the macOS
     * host's `siriPress()` sends (the bare `CMD_REQUEST_SIRI` is deprecated; iOS ignores it).
     * Non-blocking; false when no subscribed session exists. Any thread.
     */
    fun requestSiri(): Boolean {
        val c = client ?: return false
        val ok = c.sendSiriPress()
        logInfo("[SIRI] press -> ${if (ok) "sent" else "dropped (not subscribed)"}", tag = Logger.Tags.ADAPTR)
        return ok
    }

    /** Press-and-hold form for an in-app button: DOWN on touch, [siriUp] on release. */
    fun siriDown(): Boolean = client?.sendSiriDown() ?: false

    fun siriUp(): Boolean = client?.sendSiriUp() ?: false

    /**
     * `[INPUT_TELEPHONY][index]` on CH_INPUT — one tap of the uid-5 telephony HID (`Ocbm.TEL_*`;
     * DTMF digit d = `TEL_DIGIT0 + d`). The call-audio path drives this for answer / end / mute and
     * keypad; the CallStyle notification's actions land here too. Non-blocking (tx queue offer);
     * false = dropped because no subscribed session exists or the queue is full. Any thread.
     */
    fun sendTelephony(index: Byte): Boolean {
        val c = client ?: return false
        val ok = c.sendTelephony(index)
        logInfo("[TEL] button $index -> ${if (ok) "sent" else "dropped"}", tag = Logger.Tags.PHONE)
        return ok
    }

    /** Main thread. Reduce one iOS call record into the notification and fan it out. */
    private fun onCallState(cs: CallState) {
        callState = if (cs.isEnded) null else cs
        logInfo(
            "[CALL_STATE] ${CallState.statusName(cs.status)} dir=${cs.direction} ${cs.logLabel}" +
                (cs.label?.let { " ($it)" } ?: ""),
            tag = Logger.Tags.PHONE,
        )
        val host = callNotificationHost ?: return
        val before = host.presentation
        host.onCallState(cs)
        val after = host.presentation
        if (after != before) notifyCallListeners(after)
    }

    private fun notifyCallListeners(p: CallRoster.Presentation) {
        callPresentationState.value = p
        val snapshot = synchronized(callStateListeners) { callStateListeners.toList() }
        snapshot.forEach { it.onCallPresentation(p) }
    }

    /**
     * Session-scoped UI state that every teardown path must drop: no session means no call, no
     * route, and the box's last PROJ_MODE/HEALTH words are stale (it re-emits both on SUBSCRIBE).
     * Posts to main because the notification host and the focus claimer are main-thread objects
     * and the teardown paths run on IO under the lifecycle mutex.
     */
    private fun clearSessionUiState() {
        scope.launch {
            callState = null
            callNotificationHost?.let { h ->
                val before = h.presentation
                h.clear()
                if (before != CallRoster.Presentation.None) notifyCallListeners(CallRoster.Presentation.None)
            }
            appFocusClaimer?.setNavigating(false)
            if (pairingCode != null) {
                pairingCode = null
                callback?.onPairingCodeChanged(null)
            }
            onBoxStatus(Ocbm.PM_NONE, 0, false)
        }
    }

    /** Main thread. */
    private fun onBoxStatus(
        mode: Byte,
        health: Int,
        known: Boolean,
    ) {
        if (mode == projectionMode && health == boxHealth && known == boxHealthKnown) return
        if (mode != projectionMode) logInfo("[STATUS] adapter mode -> ${Ocbm.pmName(mode)}", tag = Logger.Tags.ADAPTR)
        // The pairing window just closed (it closes 10 s after a new phone bonds): ask for the bond
        // list again, so a phone added through "Add phone" shows up without reopening Settings.
        val pairWindowClosed = boxHealthKnown && boxHealth and Ocbm.BH_PAIR_WINDOW != 0 && health and Ocbm.BH_PAIR_WINDOW == 0
        projectionMode = mode
        boxHealth = health
        boxHealthKnown = known
        callback?.onBoxStatusChanged(mode, health, known)
        if (pairWindowClosed) {
            logInfo("[PAIRING] window closed — refreshing the phone list", tag = Logger.Tags.ADAPTR)
            refreshDeviceList()
        }
        val text = boxStatusText()
        synchronized(boxStatusListeners) { boxStatusListeners.toList() }.forEach { it.onBoxStatus(text) }
    }

    /** One line for the dashboard: "WIRED_CP · HCI|iap2d|airplayd", or "" before the box has said anything. */
    fun boxStatusText(): String {
        val mode = if (projectionMode == Ocbm.PM_NONE && !boxHealthKnown) "" else Ocbm.pmName(projectionMode)
        val health = if (boxHealthKnown) Ocbm.bhString(boxHealth) else ""
        return listOf(mode, health).filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /**
     * Send a multi-touch event.
     *
     * Signature preserved so `MainScreen.handleTouchEvent` is untouched, including its deadband,
     * ACTION_CANCEL UP-synthesis and DOWN→MOVE demotion.
     *
     * **Two contacts, because that is exactly what Apple's descriptor holds.** The box now
     * advertises `HIDTouchScreenMultiCreateDescriptor`, which declares two `Finger` collections —
     * enough for pinch, zoom and rotate. Pointers beyond the first two are dropped rather than
     * remapped: `airplayd` would have to evict a live contact to make room, which breaks the
     * gesture already in progress.
     *
     * Ordering by [MessageSerializer.TouchPoint.id] keeps a given finger on a stable slot for the
     * life of the gesture. That matters because the box routes by the id we send here, and a
     * contact that changed slot mid-pinch would read as a jump.
     *
     * One `INPUT_TOUCH` frame goes out per pointer; the box coalesces them into the single
     * two-finger HID report the descriptor requires.
     */
    fun sendMultiTouch(touches: List<MessageSerializer.TouchPoint>) {
        val c = client ?: return
        if (touches.isEmpty()) return
        for (t in touches.sortedBy { it.id }.take(MAX_CONTACTS)) {
            val phase =
                when (t.action) {
                    MultiTouchAction.DOWN -> Ocbm.TOUCH_DOWN
                    MultiTouchAction.MOVE -> Ocbm.TOUCH_MOVE
                    MultiTouchAction.UP -> Ocbm.TOUCH_UP
                    else -> continue
                }
            // Non-blocking by construction (the client queues), so no dispatch hop is needed and the
            // touches keep their ordering with respect to each other and to earlier frames.
            c.sendTouch(phase, t.x, t.y, finger = t.id)
        }
    }

    suspend fun rebootAdapter() {
        lifecycleMutex.withLock {
            withContext(Dispatchers.IO) { rebootAdapterImpl() }
        }
        // Normally the adapter's USB_DEVICE_ATTACHED broadcast reconnects ([onUsbDeviceAttached]).
        // Should that broadcast never come, search anyway once the adapter has had time to return
        // (the OCBM box was back on USB within ~10 s on the emulator, 2026-10-20); the search itself
        // keeps scanning for a while, so a slower box is still found.
        scope.launch {
            delay(REBOOT_FALLBACK_MS)
            if (rebootAtMs != 0L) startIfDisconnected("no attach broadcast ${REBOOT_FALLBACK_MS / 1000}s after the reboot")
        }
    }

    /** When [rebootAdapter] ran (elapsedRealtime), until the adapter is seen again; 0 = not waiting. */
    @Volatile private var rebootAtMs = 0L

    /**
     * A known adapter attached (USB_DEVICE_ATTACHED broadcast, MainActivity's USB receiver).
     *
     * With the USB bridge installed this broadcast is Carlink3's only sign that the adapter is back:
     * the platform hands the attach INTENT to the bridge (the fixed USB handler), so MainActivity's
     * onNewIntent never sees it. Without this, Reboot adapter left Carlink3 DISCONNECTED until the
     * app was reopened (emulator, 2026-10-20). Carlink2's receiver had the attach action; the port
     * dropped it. A replug after Disconnect adapter reconnects the same way, as its text promises.
     */
    fun onUsbDeviceAttached() {
        if (released) return
        val since = rebootAtMs
        if (since != 0L) {
            rebootAtMs = 0L
            logInfo("[REBOOT] adapter back on USB ${(android.os.SystemClock.elapsedRealtime() - since) / 1000}s after the reboot", tag = Logger.Tags.USB)
        }
        scope.launch { startIfDisconnected("Adapter attached") }
    }

    private fun rebootAdapterImpl() {
        // Escalation counters are deliberately preserved: reboot is user-initiated recovery, not
        // a clean-slate reset, so a user who reboots after several short sessions still gets the
        // right escalation messaging on the next attempt.
        logWarn("[LIFECYCLE] Reboot adapter requested (~50 s absence expected)", tag = Logger.Tags.ADAPTR)
        cancelReconnect()
        cancelDelayedKeyframe()
        stopMicrophoneCapture()

        runCatching { client?.mgmtAction(Ocbm.MGMT_REBOOT) }

        retireVideoEpoch("adapter rebooting")
        releaseAudio()
        client?.stop()
        client = null
        transport?.stop()
        transport = null

        codecDeferred = true
        currentPhoneType = null
        currentWifi = null
        callback?.onPhoneTypeChanged(PhoneType.UNKNOWN)
        clearCachedMediaMetadata()
        clearSessionUiState()
        setState(State.DISCONNECTED)
        rebootAtMs = android.os.SystemClock.elapsedRealtime()
        setStatusText("Adapter rebooting...")
    }

    /**
     * Release all resources. IRREVERSIBLE — a new instance is required afterwards.
     */
    fun release() {
        // Gate future starts FIRST (covers callers on foreign scopes this manager cannot cancel),
        // then cancel the scope so in-flight coroutines die at their next suspension point rather
        // than racing the synchronous teardown below.
        released = true
        scope.cancel()

        stop()

        releaseAudio()

        driveMonitor?.stop()
        driveMonitor = null
        appFocusClaimer?.stop()
        appFocusClaimer = null
        callNotificationHost?.release()
        callNotificationHost = null
        if (live === this) live = null

        // Every mutation is written through, so this only drains a queued last delta — the history
        // itself is already on disk.
        knownStore?.close()
        knownStore = null

        microphoneManager?.stop()
        microphoneManager = null
        micSendExecutor.shutdownNow()

        // Unconditional, because the only other callers of these two sit inside setState's
        // DISCONNECTED branch and BOTH ways in can be swallowed: setState dispatches through
        // `scope` when off-main and scope.cancel() above kills that post, while a teardown that
        // already reached DISCONNECTED makes the later setState a no-op (oldState == newState).
        // Losing the race leaked a partial wake lock until its 2 h timeout plus an FGS
        // notification outliving the manager.
        releaseWakeLock()
        CarlinkMediaBrowserService.stopConnectionForeground(context)

        // MediaSession is service-scope owned — do NOT release it here. Detaching our callback is
        // sufficient and necessary: releasing the session fires onSessionDestroyed on every bound
        // controller, and CarLauncher does not rebind to a new token.
        //
        // Direct instance() call, not the gated getter: `released` is already true, so the getter
        // returns null and the detach would silently no-op. Compare-and-clear so we never clobber
        // a callback a newer manager already attached.
        attachedMediaControlCallback?.let { mine ->
            MediaSessionManager.clearMediaControlCallbackIf(mine)
        }
        attachedMediaControlCallback = null

        logInfo("CarlinkManager released", tag = Logger.Tags.ADAPTR)
    }

    /**
     * Handle USB device detachment, for immediate detection of a physical unplug rather than
     * waiting for transfer errors.
     */
    fun onUsbDeviceDetached() {
        logWarn("[USB] Device detached broadcast received", tag = Logger.Tags.USB)
        if (state == State.DISCONNECTED) {
            logInfo("[USB] Already disconnected, ignoring detach", tag = Logger.Tags.USB)
            return
        }
        handleError("USB device physically disconnected")
    }

    // ==================== Video lifecycle ====================

    /**
     * Open a video epoch on [surface].
     *
     * Ordering is load-bearing: the renderer must be constructed and started before the seam is
     * pointed at its pipe, or the first frames land in a pipe nobody is draining.
     */
    @Synchronized
    private fun openVideoEpoch(surface: Surface) {
        val lanes = client?.lanes ?: return
        if (videoEpoch != null) return
        if (!surface.isValid) {
            logWarn("[LIFECYCLE] Video epoch skipped — surface invalid", tag = Logger.Tags.VIDEO)
            return
        }

        val seq = videoEpochSeq.incrementAndGet()
        val pipe = SeamPipe(VIDEO_PIPE_BYTES, VIDEO_PIPE_DEPTH)
        val renderer =
            VideoRenderer(config.width, config.height, surface) {
                client?.requestKeyframe()
            }
        renderer.start()
        val thread = lanes.runConsumer("cp-video", pipe) { renderer.consume(it) }
        lanes.videoSeam.attach(pipe)

        videoEpoch = VideoEpoch(seq, pipe, renderer, thread)
        codecDeferred = false

        // Every epoch MUST ask for one, and it has to happen here rather than at the call sites.
        // A fresh VideoRenderer has an empty parameter-set cache, and VideoSeam emits the
        // opcode-1 VideoConfig exactly once per session — attach() re-emits nothing. So a
        // mid-session epoch sees only frame AUs, and it cannot ask for a keyframe itself:
        // handleMessage returns at `if (!configured)` BEFORE reaching its own !sawKeyframe
        // request. VideoSeam.onGap cannot cover it either, because frames keep decrypting and
        // seq keeps advancing whether or not a pipe is attached, so there is no gap to detect.
        // Under gm_ccpa this was free — attaching a renderer dropped the seam socket and the box
        // force-keyframed on re-dial. A virtual pipe is invisible to the box, so we ask.
        // Throttled at 500 ms client-side, so the paths that already request are unharmed.
        client?.requestKeyframe()

        logInfo("[LIFECYCLE] Video epoch #$seq open at ${config.width}x${config.height}", tag = Logger.Tags.VIDEO)
    }

    /**
     * Retire the current video epoch.
     *
     * The join is NOT optional: without it a second decoder is configured while the first is
     * still draining, and this Intel VPU's codec pool is small enough that exhausting it leaves a
     * permanently black screen.
     */
    @Synchronized
    private fun retireVideoEpoch(why: String) {
        val e = videoEpoch ?: return
        videoEpoch = null
        codecDeferred = true
        logInfo("[LIFECYCLE] Retiring video epoch #${e.seq} — $why", tag = Logger.Tags.VIDEO)

        // Detach first so no further frames enter a pipe we are closing, then close to give the
        // consumer EOF, then flag the renderer, then wait for the codec to actually be released.
        client?.lanes?.videoSeam?.attach(null)
        e.pipe.close()
        e.renderer.stop()
        runCatching { e.thread.join(1500) }
        if (e.thread.isAlive) {
            logWarn("[LIFECYCLE] Video consumer #${e.seq} did not exit within 1500 ms", tag = Logger.Tags.VIDEO)
        }
    }

    /**
     * Rebuild the decoder without dropping the session.
     */
    fun resetVideoDecoder() {
        logInfo("[DEVICE_OPS] Resetting the video decoder", tag = Logger.Tags.VIDEO)
        val surface = videoSurface ?: return
        retireVideoEpoch("decoder reset requested")
        openVideoEpoch(surface)
    }

    /**
     * Handle Surface destruction — retire the epoch IMMEDIATELY.
     *
     * Called when SurfaceView's Surface is destroyed, which happens BEFORE onStop(). Waiting
     * would leave the codec rendering into a dead surface.
     *
     * Idempotent, and it must stay that way: `VideoSurface` can fire this twice per teardown
     * (DisposableEffect onDispose plus the SurfaceHolder callback) and designates this method as
     * the sole de-dup point.
     */
    fun onSurfaceDestroyed() {
        logInfo("[LIFECYCLE] Surface destroyed — retiring video epoch", tag = Logger.Tags.VIDEO)
        surfaceUpdateJob?.cancel()
        surfaceUpdateJob = null
        pendingSurface = null
        videoSurface = null
        retireVideoEpoch("surface destroyed")
    }

    /**
     * Start video if it was deferred — i.e. a session exists and a valid Surface has arrived.
     * Both orders happen, so both sides call this.
     */
    fun startCodecIfDeferred() {
        if (!codecDeferred) return
        val surface = videoSurface
        if (surface == null || !surface.isValid) {
            logWarn("[LIFECYCLE] Deferred video start skipped — no valid surface", tag = Logger.Tags.VIDEO)
            return // stays deferred; the debounce or resumeVideo() will retry
        }
        if (client?.lanes == null) {
            logDebug("[LIFECYCLE] Deferred video start skipped — no session yet", tag = Logger.Tags.VIDEO)
            return
        }
        openVideoEpoch(surface)
    }

    /**
     * Pause video when the app goes to background.
     *
     * Retiring the epoch is also the DROP POLICY, and that is the point: `SeamPipe.write` blocks
     * by design, so a paused-but-open pipe would backpressure the OCBM read thread and stall
     * audio and control traffic too. A closed pipe returns false immediately and counts.
     */
    fun pauseVideo() {
        logInfo("[LIFECYCLE] Pausing video for background", tag = Logger.Tags.VIDEO)
        cancelDelayedKeyframe()
        retireVideoEpoch("app backgrounded")
    }

    /**
     * Resume video when the app returns to foreground.
     */
    fun resumeVideo() {
        logInfo("[LIFECYCLE] Resuming video for foreground", tag = Logger.Tags.VIDEO)
        val surface = videoSurface
        if (surface == null || !surface.isValid) {
            logInfo("[LIFECYCLE] Surface not ready — resume will happen via initialize()", tag = Logger.Tags.VIDEO)
            return
        }
        openVideoEpoch(surface)
        if (state == State.STREAMING || state == State.DEVICE_CONNECTED) {
            scheduleDelayedKeyframe()
        }
    }

    /**
     * Frames the current video epoch has handed to the Surface (0 with no epoch). A UI-side read
     * only: the loading overlay hides once video is actually on screen, which under OCBM happens
     * BEFORE [State.STREAMING] (that edge is the first media metadata, [markStreamingIfFirstMedia]),
     * and the overlay open/close path logs it so a frozen feed is distinguishable from a stale frame.
     */
    fun videoFramesRendered(): Long = videoEpoch?.renderer?.framesRendered?.get() ?: 0L

    /**
     * Where rendering has got to: the video epoch's number and the frames it has drawn, so a caller
     * can wait for a frame drawn after a given moment even across a decoder rebuild (a new epoch
     * counts from zero).
     */
    fun videoFrameMark(): Pair<Int, Long> {
        val e = videoEpoch
        return (e?.seq ?: 0) to (e?.renderer?.framesRendered?.get() ?: 0L)
    }

    /**
     * Recover video after the settings overlay closes.
     *
     * A keyframe request FIRST, not an epoch bump. The problem this solves is a stale frame while
     * the next natural IDR is up to a minute away — a keyframe fixes that for the price of one
     * frame, whereas an epoch bump costs a codec teardown, a configure and an IDR wait anyway.
     * The watchdog bumps the epoch only if the frame counter proves the keyframe did not help.
     *
     * Gated on a live video epoch, NOT on `state == STREAMING`: OCBM stamps STREAMING on the first
     * media metadata, so a session can sit in DEVICE_CONNECTED with video on screen indefinitely
     * (live-verified 2026-09-25: Back from Settings skipped the recovery there and left a stale
     * frame). A retired epoch (no session, or stopped) is the only "nothing to recover".
     */
    fun recoverVideoFromOverlay() {
        val e = videoEpoch ?: return
        logInfo("[LIFECYCLE] Recovering video after overlay close (state=$state)", tag = Logger.Tags.VIDEO)
        val before = e.renderer.framesRendered.get()
        client?.requestKeyframe()

        scope.launch {
            delay(OVERLAY_RECOVERY_WATCHDOG_MS)
            val still = videoEpoch ?: return@launch
            if (still.seq != e.seq) return@launch // already replaced
            if (still.renderer.framesRendered.get() == before) {
                logWarn(
                    "[LIFECYCLE] No frames ${OVERLAY_RECOVERY_WATCHDOG_MS}ms after overlay keyframe — rebuilding decoder",
                    tag = Logger.Tags.VIDEO,
                )
                val surface = videoSurface ?: return@launch
                retireVideoEpoch("overlay recovery watchdog")
                openVideoEpoch(surface)
            }
        }
    }

    // ==================== OCBM session callbacks ====================

    /**
     * Status text for a CT_BT_PHASE value, or null for phases with nothing to say.
     *
     * Advisory and only roughly monotonic: never gate on ordering, and treat an unknown value as
     * progress. This is the only visibility the host has into the entire Bluetooth handshake,
     * which was otherwise a silent multi-second gap.
     */
    private fun btPhaseText(phase: Byte): String? =
        when (phase) {
            Ocbm.BTP_LINK_UP -> "Phone found — linking..."
            Ocbm.BTP_AUTHENTICATING -> "Authenticating..."
            Ocbm.BTP_AUTHENTICATED -> "Authenticated"
            Ocbm.BTP_IDENTIFYING -> "Identifying..."
            Ocbm.BTP_IDENTIFIED -> "Phone identified"
            Ocbm.BTP_WIFI_HANDOFF -> "Handing off to Wi-Fi..."
            else -> null
        }

    /** CT_PROJ_MODE / CT_BOX_HEALTH — both re-emitted by the box on every fresh SUBSCRIBE, so nothing caches across reconnects. */
    private fun wireBoxStatus(c: OcbmClient) {
        c.onProjMode = { mode -> scope.launch { if (client === c) onBoxStatus(mode, boxHealth, boxHealthKnown) } }
        c.onBoxHealth = { flags -> scope.launch { if (client === c) onBoxStatus(projectionMode, flags, true) } }
    }

    /** CT_PAIRING_CODE -> the Pair / Cancel prompt ([pairingCode], [answerPairing]). */
    private fun wirePairingCode(c: OcbmClient) {
        c.onPairingCode = { code ->
            // The one value that MUST reach a human: it is matched against a prompt on the
            // iPhone, and until it is on screen the only place it exists is a logcat line nobody
            // is watching from the driver's seat. SSP Numeric Comparison needs a real yes/no on
            // BOTH ends, so the code is a PROMPT (Pair / Cancel -> answerPairing), not just text.
            scope.launch {
                if (client !== c) return@launch
                if (code.isNotEmpty()) {
                    setStatusText("Pairing code: $code")
                    pairingCode = code
                    callback?.onPairingCodeChanged(code)
                } else if (pairingCode != null) {
                    // A clear with no prompt showing is the box's replay on SUBSCRIBE (it mirrors
                    // an EMPTY /tmp/pairing_code) — not a pairing in progress. Measured 2026-09-25:
                    // it painted "Pairing..." over every fresh wired session's CONNECTING overlay.
                    setStatusText("Pairing...")
                    pairingCode = null
                    callback?.onPairingCodeChanged(null)
                }
            }
        }
    }

    /**
     * Wire [c]'s callbacks. Every one of them fires on the OCBM read thread or a client-owned
     * thread, never main — so anything touching Android state hops through [scope].
     */
    private fun wireClientCallbacks(c: OcbmClient) {
        c.onLanesArmed = { lanes -> onLanesArmed(lanes) }
        c.onLanesRetired = { onLanesRetired() }

        // The first per-stream key is the earliest truthful "a paired, keyed session exists"
        // signal — earlier than the first decoded frame, and unlike phone presence it proves the
        // crypto handshake completed.
        c.onSessionKeyed = {
            hadPriorSession = true
            scope.launch {
                // The key event and a link death can coincide (a connector jiggle at handshake).
                // Without this the stale post lands DEVICE_CONNECTED after the error path already
                // set DISCONNECTED, and both the reconnect backoff and the USB-attach intent are
                // gated on DISCONNECTED — so the app wedges at "Phone connected" with no session.
                if (client !== c) return@launch
                setState(State.DEVICE_CONNECTED)
                setStatusText("Phone connected")
                currentPhoneType = PhoneType.CARPLAY
                callback?.onPhoneTypeChanged(PhoneType.CARPLAY)
                startCodecIfDeferred()
                scheduleDelayedKeyframe()
                // Initial sync. A session that comes up while already in Drive gets no edge from
                // the monitor, so push the current value once the box can actually receive it.
                driveMonitor?.let { if (it.connected) setLimitedUI(it.driving) }
            }
        }

        c.onPhonePresence = { present ->
            scope.launch {
                if (present || state == State.DISCONNECTED) return@launch
                // Drop out of STREAMING. MainScreen keys both the loading overlay and touch
                // forwarding on STREAMING, so staying there leaves the last decoded frame frozen
                // on screen with touch still live and this very status text invisible behind it.
                // The OCBM session itself stays up by design; only the UI state reflects reality.
                if (state == State.STREAMING) setState(State.DEVICE_CONNECTED)
                setStatusText("Waiting for phone...")
            }
        }

        // Advisory only — the client's own backoff re-subscribes and tearing down here would
        // fight that recovery. Surfacing it just keeps the UI honest during the gap.
        c.onHostPresence = { present ->
            scope.launch {
                if (!present && state != State.DISCONNECTED) setStatusText("Re-establishing session...")
            }
        }

        c.onBtPhase = { phase ->
            val text = btPhaseText(phase)
            if (text != null) scope.launch { if (state != State.STREAMING) setStatusText(text) }
        }

        wirePairingCode(c)

        // Hops through `scope` like every other callback here. It previously ran inline on the
        // read thread, which let a CT_UPLINK(on) arriving mid-teardown start an AudioRecord that
        // stopImpl had already passed — a hot mic surviving into the next session, where
        // onMicGate would early-return on the stale flag and uplink at the WRONG rate. The
        // identity check closes the same window from the other side.
        c.onUplinkGate = { on, rate, ch, codec ->
            scope.launch { if (client === c) onMicGate(on, rate, ch, codec) }
        }

        c.onPhoneIdentity = { json -> scope.launch { onPhoneIdentity(json) } }

        wireBoxStatus(c)

        c.onMetadata = { marker, payload -> onMetadata(marker, payload) }

        // FATAL only. A box-side SEV_HOST_GONE is recoverable and the client's own backoff
        // re-subscribes; tearing down on it would fight that recovery.
        c.onLinkDead = { reason -> handleError("usb — link dead: $reason") }
    }

    private fun onLanesArmed(lanes: OcbmAvLanes) {
        logInfo("[SESSION] A/V lanes armed", tag = Logger.Tags.ADAPTR)

        // Where CarPlay draws. Logged only when the pane appears or goes: a resize is ~250 steps.
        lanes.videoSeam.onViewArea = { r ->
            val before = viewAreaState.value
            if (before != r) {
                if (before == null || before.isFull != r.isFull) logInfo("[VIEW_AREA] CarPlay draws $r", tag = Logger.Tags.VIDEO)
                viewAreaState.value = r
            }
        }

        // Audio consumers must start here or the pipes fill and eventually backpressure the read
        // thread. Both are flag-only on stop(); their consume threads are the sole releasers.
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        // A permanent focus loss means another AAOS source took over: a native head unit tells the
        // phone to pause, and so do we. The player re-requests focus when audible media resumes.
        val player =
            MediaAudioPlayer(
                am,
                onFocusLost = { reclaim ->
                    // ...unless the loss is the aftermath of a skip we just sent: the car gave the
                    // press to the radio as well, which started and took the focus. Keep the music.
                    if (skipAftermath.covers(SystemClock.elapsedRealtime())) {
                        logWarn(
                            "[FOCUS] lost right after a skip this app sent — the car gave the press to another source too; keeping the music, asking for the audio back",
                            tag = Logger.Tags.ADAPTR,
                        )
                        reclaim("the skip's aftermath")
                    } else {
                        sendMediaButton(Ocbm.MEDIA_BTN_PAUSE)
                    }
                },
                // Audible media while our own Siri/call sink still holds transient focus: iOS has
                // ended the session, so release the quiet sink and let AAOS hand focus back.
                onAudibleWhileSuppressed = {
                    voiceRouter?.releaseQuietSinks("media audible again", TransientFocusPolicy.MEDIA_RESUME_MIN_QUIET_MS)
                },
                onFocusEvent = { logInfo("[FOCUS] $it", tag = Logger.Tags.ADAPTR) },
                // Music became audible: re-publish the phone's state, which audibility no longer
                // overrides (1.02) — this only replays a push the dedup may have swallowed.
                onAudible = { scope.launch { pushPlaybackState() } },
            ).also { it.start() }
        mediaPlayer = player
        val router =
            VoiceRouter(
                context,
                onDuck = { ducked -> player.setDucked(ducked) },
                onAssistant = { speaking -> player.setAssistantSpeaking(speaking) },
                // Settings → Call Buffer, read when the call track opens so a change takes the next call.
                callBufferMultiplier = { CallAudioStore.get(context).callBuffer.bufferMultiplier },
                onCallAudio = { scope.launch { onCallAudioStarted() } },
            ).also { it.start() }
        voiceRouter = router

        lanes.runConsumer("cp-audio", lanes.mediaPipe) { player.consume(it) }
        lanes.runConsumer("cp-voice", lanes.voicePipe) { router.consume(it) }

        // The Surface may already be waiting; if not, initialize()'s debounce opens the epoch.
        scope.launch { startCodecIfDeferred() }
    }

    private fun onLanesRetired() {
        // Contract with OcbmAvLanes: the video pipe is OURS, so we must close it here. Lanes
        // close only the two audio pipes; a video consumer left parked on an open pipe would
        // hold its codec and burn the join timeout.
        retireVideoEpoch("lanes retired")
        releaseAudio()
        // No stream, no view area: the pane must not outlive the picture it sat beside.
        viewAreaState.value = null
    }

    private fun releaseAudio() {
        // Flag-only on both: each consume thread releases its own codec and track in its finally,
        // and it is the only thread allowed to. MediaAudioPlayer.stop() additionally releases a
        // primed but never-consumed track, which matters on every path where the media seam never
        // delivered.
        mediaPlayer?.stop()
        mediaPlayer = null
        voiceRouter?.stop()
        voiceRouter = null
    }

    // ==================== Microphone ====================

    /**
     * The box's mic gate.
     *
     * This replaces the whole riddleBox `VoiceMode` state machine. There, mic-on had to be
     * inferred from downlink audio commands, and the inference had a genuine hazard —
     * `PHONECALL_START` arrives ~130 ms BEFORE `SIRI_STOP`, so a naive reading killed the call's
     * microphone. Here the box states it outright and carries the negotiated format with it.
     */
    private fun onMicGate(
        on: Boolean,
        rate: Int,
        channels: Int,
        codec: Int,
    ) {
        voiceRouter?.onUplinkGate(on)
        if (on) {
            // A renegotiation (CVSD -> mSBC keeps the rate at 16 kHz) must restart capture: the
            // early-return in startMicrophoneCapture would otherwise leave a PCM uplink running on
            // a wideband SCO socket — silent corruption with no local symptom.
            if (isMicrophoneCapturing && microphoneManager?.matchesFormat(rate, channels, codec) == false) {
                logInfo("[MIC] uplink format changed (${rate}Hz ${channels}ch codec=$codec) — restarting capture", tag = Logger.Tags.MIC)
                stopMicrophoneCapture()
            }
            micRate = rate
            micChannels = channels
            micCodec = codec
            startMicrophoneCapture(rate, channels, codec)
        } else {
            stopMicrophoneCapture()
            carPathSilentThisCall = false
        }
    }

    private fun startMicrophoneCapture(
        rate: Int,
        channels: Int,
        codec: Int,
    ) {
        if (isMicrophoneCapturing) return
        val mgr = microphoneManager ?: return

        // mSBC captures 16 kHz mono whatever the gate said (MicProfile.captureFormatFor); log a
        // disagreement rather than silently encode pitch-shifted speech.
        val (r, c) = MicProfile.captureFormatFor(rate, channels, codec)
        if (r != rate || c != channels) {
            logWarn("[MIC] gate said ${rate}Hz ${channels}ch but codec $codec captures ${r}Hz ${c}ch", tag = Logger.Tags.MIC)
        }
        val input = callMicInput()
        if (!mgr.start(captureProfileFor(r, c), codec, input)) {
            logError("[MIC] Capture failed to start", tag = Logger.Tags.MIC)
            return
        }
        isMicrophoneCapturing = true
        micInput = input

        // 20 ms of PCM per tick, matching the box's own RTP packetization (PCM); for mSBC up to a
        // few whole 60-byte packets per tick with the ring buffer carrying the 7.5 ms remainder.
        // The cadence and the drain size must change together or the stream underruns.
        val chunkSize = MicProfile.uplinkDrainBytes(rate, channels, codec)
        val generation = micSendGeneration.incrementAndGet()
        micSendFuture?.cancel(false)
        var failures = 0
        micSendFuture =
            micSendExecutor.scheduleAtFixedRate({
                if (generation != micSendGeneration.get()) return@scheduleAtFixedRate
                try {
                    sendMicrophoneData(chunkSize)
                    failures = 0
                } catch (e: Exception) {
                    // Rate-limited: at 20 ms a persistent failure would log 50x/sec.
                    if (failures++ % 250 == 0) {
                        logError("[MIC] send threw (cadence kept alive, failures=$failures)", tag = Logger.Tags.MIC, throwable = e)
                    }
                }
            }, 0, MicProfile.TICK_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)

        logInfo("[MIC] Capture started at ${r}Hz ${c}ch codec=$codec (drain ${chunkSize}B/tick)", tag = Logger.Tags.MIC)
        startEchoMonitor(r, c)
    }

    /**
     * The first caller audio of a call has arrived ([VoiceRouter]'s call track opened).
     *
     * The box opens the mic at the call stream's SETUP, before the phone sends any audio, so the
     * capture normally started before the app could know it was a call: [callMicInput] saw no call
     * and took the OS's choice, which made "Car call path" a no-op, and the echo monitor never
     * started. A call placed from Siri is the same, its mic already open. Now that it is known,
     * move the capture to the car's call input when that is the setting (a short restart at the
     * start of the call), and start the echo monitor either way.
     */
    private fun onCallAudioStarted() {
        if (!isMicrophoneCapturing) return
        val wanted = callMicInput()
        if (wanted != null && wanted.id != micInput?.id) {
            logInfo(
                "[CALL_AUDIO] call audio started with the mic on ${micInput?.let { AudioRouteInfo.device(it) } ?: "the OS choice"} — " +
                    "moving it to ${AudioRouteInfo.device(wanted)}",
                tag = Logger.Tags.MIC,
            )
            stopMicrophoneCapture()
            startMicrophoneCapture(micRate, micChannels, micCodec)
            return
        }
        if (callEchoMonitor == null) {
            val (r, c) = MicProfile.captureFormatFor(micRate, micChannels, micCodec)
            startEchoMonitor(r, c)
        }
    }

    // ---- Call echo monitor (ported from Carlink2 [223]) ------------------------------------------

    @Volatile private var callEchoMonitor: CallEchoMonitor? = null

    /**
     * During a phone call, measure how much of the caller's voice comes back in the mic audio we
     * send, and how late ([CallEchoMonitor], [ECHO_MON] lines every 3 s, a summary at call end).
     * Taps the caller's PCM at the call track and the mic PCM before encoding. Needs both at the
     * same rate, mono; otherwise it says so once and stays quiet. When Automatic put the call on
     * the car call path, the same tap feeds its [SilenceGuard] ([onCarPathSilent]).
     */
    private fun startEchoMonitor(
        rate: Int,
        channels: Int,
    ) {
        stopEchoMonitor()
        val router = voiceRouter ?: return
        val mic = microphoneManager ?: return
        if (channels != 1 || !router.isCallActive()) return
        val callMic = CallAudioStore.get(context).callMic
        val monitor =
            CallEchoMonitor(
                sampleRate = rate,
                bufferStats = { router.callTrackStats() },
                routing = { "callMic=${callMic.label} mic=${mic.routedInput()} ${router.callTrackStats()} os=${android.os.Build.DISPLAY}" },
                log = { logInfo(it, tag = Logger.Tags.MIC) },
            )
        var mismatchLogged = false
        router.callPcmTap = { pcm, off, len, r, ch ->
            if (r == rate && ch == 1) {
                monitor.onDownlink(pcm, off, len)
            } else if (!mismatchLogged) {
                mismatchLogged = true
                logInfo("[ECHO_MON] caller audio is ${r}Hz ${ch}ch, mic ${rate}Hz: can't compare them this call", tag = Logger.Tags.MIC)
            }
        }
        // Automatic on the car call path is guarded; a path the driver picked by hand is trusted as is.
        val guard = if (callMic.isAutomatic && micInput != null) SilenceGuard(rate) else null
        mic.uplinkPcmTap = { pcm, off, len ->
            monitor.onUplink(pcm, off, len)
            if (guard?.feed(pcm, off, len) == true) scope.launch { onCarPathSilent() }
        }
        callEchoMonitor = monitor
        logInfo("[ECHO_MON] start: ${rate}Hz callMic=${callMic.label} guard=${if (guard != null) "on" else "off"}", tag = Logger.Tags.MIC)
    }

    private fun stopEchoMonitor() {
        voiceRouter?.callPcmTap = null
        microphoneManager?.uplinkPcmTap = null
        callEchoMonitor?.stop()
        callEchoMonitor = null
    }

    /** Why the parked call-audio tests can't run right now, or null when they can. */
    fun audioTestBlockedReason(): String? =
        when {
            voiceRouter?.isCallActive() == true -> "a phone call is active"
            isMicrophoneCapturing -> "Siri or a call is using the microphone"
            else -> null
        }

    /** Settings → Call buffer's track multiplier, so the echo test's track matches the call's. */
    val callBufferMultiplier: Int get() = CallAudioStore.get(context).callBuffer.bufferMultiplier

    /**
     * Settings → Call Mic (Carlink2 [225]/[235]): the input a phone call records from. GM's
     * Voice_Uplink bus (the car's processed call mic, what GM's own CarPlay records) when the car
     * lists it, a call's downlink is open and the driver chose "Car call path"; otherwise null, so
     * VOICE_COMMUNICATION and the OS/HAL pick the device and processing. Siri always takes null.
     * One [CALL_AUDIO] line per capture names the choice, so logs from different GM builds compare.
     */
    private fun callMicInput(): android.media.AudioDeviceInfo? {
        val store = CallAudioStore.get(context)
        val pref = store.callMic
        val callActive = voiceRouter?.isCallActive() == true
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val key = pref.deviceKey
        val (input, why) =
            when {
                !callActive -> null to "no call yet (Siri, or the call's audio hasn't started)"
                am == null -> null to "no AudioManager"
                pref.isAutomatic -> automaticInput(am, store)
                key != null -> CallPaths.find(am, key)?.let { it to "your choice" } ?: (null to "your choice isn't listed by the car now")
                else -> null to "system choice"
            }
        logInfo(
            "[CALL_AUDIO] callMic=${pref.label} callActive=$callActive " +
                "input=${input?.let { AudioRouteInfo.device(it) } ?: "OS choice"} ($why) os=${android.os.Build.DISPLAY}",
            tag = Logger.Tags.MIC,
        )
        return input
    }

    /** Set for the rest of a call once Automatic's [SilenceGuard] found the car call path silent. */
    @Volatile private var carPathSilentThisCall = false

    /**
     * Automatic: the input the first-launch check chose for this car software ([checkCallPathIfNeeded]).
     * Not checked yet (no mic permission at launch, or a failed check) means try the car call path
     * anyway: the guard catches a silent one within seconds.
     */
    private fun automaticInput(
        am: android.media.AudioManager,
        store: CallAudioStore,
    ): Pair<android.media.AudioDeviceInfo?, String> {
        if (carPathSilentThisCall) return null to "Automatic: the car call path went silent this call"
        val auto = store.autoPath?.takeIf { it.osBuild == android.os.Build.FINGERPRINT }
        val key =
            when {
                auto == null -> null
                auto.deviceKey == null -> return null to "Automatic: ${auto.reason}"
                else -> auto.deviceKey
            }
        if (key != null) return CallPaths.find(am, key)?.let { it to "Automatic: ${auto?.reason}" } ?: (null to "Automatic: its input isn't listed now")
        val bus = CallPaths.candidates(am).firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUS }
        return bus to if (bus != null) "Automatic: not checked yet, trying the car call path" else "Automatic: this car lists no call input"
    }

    /**
     * Automatic's guard tripped: the car call path has carried digital silence since the call's mic
     * opened. Move this call to the system's choice and remember it, so later calls start there too
     * (until the car's software changes and the first-launch check runs again).
     */
    private fun onCarPathSilent() {
        if (!isMicrophoneCapturing || carPathSilentThisCall) return
        logWarn("[CALL_AUDIO] Automatic: the car call path is silent — switching this call to the system choice", tag = Logger.Tags.MIC)
        carPathSilentThisCall = true
        CallAudioStore.get(context).setAutoPath(
            AutoCallPath(null, android.os.Build.FINGERPRINT, "the car call path went silent during a call"),
        )
        stopMicrophoneCapture()
        startMicrophoneCapture(micRate, micChannels, micCodec)
    }

    /**
     * Automatic's silent first-launch check, re-run when the car's software changes: record about a
     * second from the car call path with nothing playing and keep it if it carries sound
     * ([CallPaths.probeCarCallPath]). Runs off the main thread, never during Siri, a call or an
     * audio test. Called at startup and when microphone permission is granted.
     */
    fun checkCallPathIfNeeded() {
        val store = CallAudioStore.get(context)
        if (store.autoPath?.osBuild == android.os.Build.FINGERPRINT) return
        scope.launch(Dispatchers.IO) {
            if (audioTestBlockedReason() != null || !AudioTestLock.tryLock()) return@launch
            try {
                val probe = CallPaths.probeCarCallPath(context)
                logInfo("[CALL_AUDIO] Automatic checked this car: ${probe.reason} (conclusive=${probe.conclusive})", tag = Logger.Tags.MIC)
                if (probe.conclusive) {
                    store.setAutoPath(AutoCallPath(probe.device?.let { CallPaths.key(it) }, android.os.Build.FINGERPRINT, probe.reason))
                }
            } finally {
                AudioTestLock.unlock()
            }
        }
    }

    /**
     * Map the box-negotiated format onto [MicrophoneCaptureManager]'s capture profile.
     *
     * That manager still keys its format off riddleBox's `decodeType` table. An unmapped rate
     * would silently fall back to 16 kHz mono and reach Siri pitch-shifted with no error
     * anywhere, so an unknown format is called out rather than quietly accepted.
     */
    private fun captureProfileFor(
        rate: Int,
        channels: Int,
    ): Int =
        MicProfile.decodeTypeFor(rate, channels) ?: run {
            logWarn(
                "[MIC] Box negotiated ${rate}Hz ${channels}ch, which has no capture profile — " +
                    "falling back to 16 kHz mono; the uplink will be WRONG",
                tag = Logger.Tags.MIC,
            )
            MicProfile.FALLBACK_DECODE_TYPE
        }

    private fun stopMicrophoneCapture() {
        if (!isMicrophoneCapturing) return
        micSendGeneration.incrementAndGet() // invalidate any queued run before cancelling
        micSendFuture?.cancel(false)
        micSendFuture = null
        stopEchoMonitor()
        micInput = null
        microphoneManager?.stop()
        isMicrophoneCapturing = false
        logInfo("[MIC] Capture stopped", tag = Logger.Tags.MIC)
    }

    private fun sendMicrophoneData(chunkSize: Int) {
        if (!isMicrophoneCapturing) return
        // PCM: S16LE straight out to CH_MIC — the box converts to the big-endian the iPhone wants.
        // Do NOT "symmetry-fix" this against the big-endian DOWNLINK: a swapped uplink reaches
        // Siri as noise with no local symptom. mSBC: each callback is one 60-byte eSCO packet, one
        // CH_MIC message each (MIC_CHUNK = 16384 never splits it).
        microphoneManager?.drainUplink(chunkSize) { client?.sendMicPcm(it) }
    }

    // ==================== Metadata ====================

    /**
     * Per-driver settings ([DriverProfiles]): the iPhone that just identified itself becomes the
     * active driver, and Turn alert, Lane guidance, Directions on cluster and Limit CarPlay while
     * driving switch to its settings.
     * Nothing changes when it is the same iPhone as last time. "Ask iPhone for lanes" was already
     * sent with this connection's subscribe; a change of driver applies it from the next one.
     */
    private fun applyDriverProfile(
        mac: String,
        name: String,
    ) {
        if (!DriverProfiles.onPhone(context, mac, name)) return
        DriverProfiles.reloadActive(context)
        logInfo("[PROFILE] driver settings switched to $mac: directions=${ClusterNavigation.isEnabled(context)}", tag = Logger.Tags.PHONE)
    }

    /**
     * `CT_PHONE_IDENT` — the connected phone's own description of itself.
     *
     * Both fields earn their place: `name` is what the user set in Settings > General > About > Name,
     * and `deviceID` is the BR/EDR MAC, which is what makes this joinable against the bonded MACs in
     * `MGMT_INFO`. Until this existed the device list could only show bare hex, and nothing said
     * which bonded phone was the live one.
     */
    private fun onPhoneIdentity(json: String) {
        if (json.isEmpty()) {
            connectedPhoneMac = null
            return
        }
        val obj =
            runCatching { JSONObject(json) }.getOrNull() ?: run {
                logWarn("[PHONE] CT_PHONE_IDENT is not JSON: $json", tag = Logger.Tags.PHONE)
                return
            }
        val name = obj.optString("name")
        val mac = obj.optString("deviceID").lowercase()
        if (mac.isEmpty()) return
        connectedPhoneMac = mac
        _connectedBtMac = mac
        if (name.isNotEmpty()) phoneNames[mac] = name
        applyDriverProfile(mac, name)

        // Remember it. This is the ONLY source of a device name anywhere in the system — the box's
        // link-key record has no room for one — so without this the list reverts to bare hex after
        // every app restart. Also lifts any forget-suppression: a user who forgets and re-pairs
        // inside the window must not stare at an empty list.
        val now = System.currentTimeMillis()
        synchronized(deviceListLock) { recentlyForgotten.remove(mac) }
        knownStore?.update { snap ->
            val prev = snap.devices[mac]
            snap.copy(
                devices =
                    snap.devices +
                        (
                            mac to
                                KnownDevice(
                                    mac = mac,
                                    name = name.ifEmpty { prev?.name },
                                    model = obj.optString("model").ifEmpty { prev?.model },
                                    osName = obj.optString("osName").ifEmpty { prev?.osName },
                                    osVersion = obj.optString("osVersion").ifEmpty { prev?.osVersion },
                                    firstSeenMs = prev?.firstSeenMs ?: now,
                                    lastConnectedMs = now,
                                )
                        ),
            )
        }
        logInfo(
            "[PHONE] ${if (name.isEmpty()) LogRedactor.line(mac) else "<phone>"} (${obj.optString("model")}, " +
                "${obj.optString("osName")} ${obj.optString("osVersion")})",
            tag = Logger.Tags.PHONE,
        )
        // The list may already be on screen with a MAC for a label; rebuild it with the real name.
        rebuildDeviceList()
    }

    /**
     * Rebuild the merged view from history + the box's bond list, and publish it.
     *
     * The single place the list is produced, so every input change funnels through the same rules.
     * It goes through [mutateDeviceList] specifically so the recently-forgotten filter applies to
     * PERSISTED entries too — merging outside that lock would let the box's stale snapshot, which
     * still lists a device for ~4-5 s while it restarts wireless, resurrect a just-forgotten phone
     * into permanent storage. That is strictly worse than the transient resurrection the filter was
     * originally written for.
     */
    private fun rebuildDeviceList() {
        val snap = knownStore?.snapshot ?: KnownDeviceSnapshot()
        val merged =
            synchronized(deviceListLock) {
                mergeDeviceList(
                    known = snap.devices,
                    bondedMacs = bondedMacs,
                    suppressed = recentlyForgotten.keys.toSet(),
                    order = snap.order,
                    formatLastSeen = ::formatLastSeen,
                )
            }
        if (merged == _pairedDevices) return
        mutateDeviceList { merged }
        callback?.onDeviceListChanged(_pairedDevices)
        notifyDeviceListeners()
    }

    /** Human-readable "last seen" for a wall-clock timestamp, in the head unit's locale. */
    private fun formatLastSeen(ms: Long): String =
        android.text.format.DateUtils
            .getRelativeTimeSpanString(ms, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS)
            .toString()

    // ---- Drive logging ---------------------------------------------------------------------------

    /**
     * Settings → Record drive logs. Starts/stops the [DriveLog] file and, on a live link, arms or
     * disarms the box's CH_LOG stream so box lines land in the same file as the app's.
     */
    fun setDriveLogging(on: Boolean) {
        logWarn("[UI_ACTION] Drive logging -> ${if (on) "on" else "off"}", tag = Logger.Tags.ADAPTR)
        DriveLogging.setEnabled(context, on)
        val c = client ?: return
        if (on || LaneActivity.askedThisConnection.value) {
            armBoxLogStream(c)
        } else {
            c.setBoxLogStream(false)
            c.onBoxLog = null
        }
    }

    /**
     * After SUBSCRIBE (the box resets the stream to off per session): stream box lines into the drive
     * log, and watch them for a refused lane request while one is out.
     */
    private fun armBoxLogStream(c: OcbmClient) {
        if (!DriveLog.recording && !LaneActivity.askedThisConnection.value) return
        c.onBoxLog = { entries ->
            if (DriveLog.recording) DriveLog.boxEntries(entries)
            watchLaneRefusal(entries)
        }
        c.setBoxLogStream(true)
    }

    /**
     * The iPhone rejects an identification it does not accept (`RX 0x1D03 IdentificationRejected`
     * in the box log), which costs the whole CarPlay session. When the rejected one asked for lanes,
     * stop asking and reset the connection so the box gets the proven set again; otherwise every
     * retry would carry the same request. Backfilled lines are an earlier session's and are skipped.
     */
    private fun watchLaneRefusal(entries: List<BoxLogEntry>) {
        if (!LaneActivity.askedThisConnection.value) return
        val refusal = entries.firstOrNull { it.flags and Ocbm.LOG_F_BACKFILL == 0 && "IdentificationRejected" in it.text } ?: return
        LaneActivity.noteAsked(false)
        LaneGuidanceStore.get(context).markRefused(System.currentTimeMillis())
        logWarn(
            "[LANES] the iPhone refused the identification that asked for lanes (${refusal.text.take(LANE_REFUSAL_LOG_CHARS)}); " +
                "turned 'Ask iPhone for lanes' off, resetting the connection",
            tag = Logger.Tags.NAVI,
        )
        scope.launch { cleanSlateReset() }
    }
    // ---- Pairing window (security plan 2026-10-21 §3: MGMT_PAIR_WINDOW, CAP_PAIR_WINDOW) ----------

    /** The box lets new phones pair only inside a window the app opens; without it, today's open pairing. */
    val pairWindowSupported: Boolean get() = client?.supportsPairWindow == true

    /** The box reports the window open (CT_BOX_HEALTH BH_PAIR_WINDOW). */
    val pairWindowOpen: Boolean get() = boxHealth and Ocbm.BH_PAIR_WINDOW != 0

    /** Open the pairing window for [seconds] (the box clamps to 1..300), or close it with 0. */
    fun setPairWindow(seconds: Int) {
        logWarn("[PAIRING] window ${if (seconds > 0) "open for ${seconds}s" else "close"} requested", tag = Logger.Tags.ADAPTR)
        scope.launch(Dispatchers.IO) {
            val arg = byteArrayOf((seconds and BYTE_MASK).toByte(), ((seconds shr Byte.SIZE_BITS) and BYTE_MASK).toByte())
            val status = client?.mgmtAction(Ocbm.MGMT_PAIR_WINDOW, arg)
            logWarn("[PAIRING] MGMT_PAIR_WINDOW status=${status ?: "no answer"}", tag = Logger.Tags.ADAPTR)
        }
    }

    // ---- Phone priority (enigy/ocbm phone-priority: MGMT_SET_POLICY / MGMT_CONNECT_DEVICE) -------

    /** True when the box advertises CAP_PHONE_POLICY; without it the priority controls stay hidden. */
    val phonePolicySupported: Boolean get() = client?.supportsPhonePolicy == true

    /** Whether the box calls known phones on its own when it starts (the app's durable copy). */
    val autoConnectPhones: Boolean get() = knownStore?.snapshot?.autoConnect ?: true

    /**
     * Move a phone one place up ([delta] = -1) or down (+1) in the reconnect order. The whole list on
     * screen becomes the explicit order, so what the user sees is exactly what the box is told.
     */
    fun movePhone(
        btMac: String,
        delta: Int,
    ) {
        val order = _pairedDevices.map { it.btMac.lowercase() }.toMutableList()
        val i = order.indexOf(btMac.lowercase())
        val j = i + delta
        if (i < 0 || j !in order.indices) return
        order[i] = order[j].also { order[j] = order[i] }
        logInfo("[DEVICE_MGMT] Priority -> ${order.joinToString()}", tag = Logger.Tags.ADAPTR)
        knownStore?.update { it.copy(priority = order, preferredMac = order.first()) }
        rebuildDeviceList()
        pushPhonePolicy("reorder")
    }

    fun setAutoConnectPhones(on: Boolean) {
        logInfo("[DEVICE_MGMT] Auto-connect -> $on", tag = Logger.Tags.ADAPTR)
        knownStore?.update { it.copy(autoConnect = on) }
        pushPhonePolicy("auto-connect")
    }

    /** Ask the box to call this bonded phone now. The box never interrupts a phone that is projecting. */
    fun connectPhone(btMac: String) {
        if (!phonePolicySupported) return
        scope.launch(Dispatchers.IO) {
            val status = client?.mgmtAction(Ocbm.MGMT_CONNECT_DEVICE, btMac.uppercase().toByteArray(Charsets.US_ASCII))
            logInfo("[DEVICE_MGMT] CONNECT_DEVICE($btMac) status=$status", tag = Logger.Tags.ADAPTR)
        }
    }

    /**
     * Send the order on screen and the auto-connect flag to the box. The app is the durable copy, so
     * this also runs at every session start; the box writes flash only when the policy changed.
     */
    private fun pushPhonePolicy(reason: String) {
        if (!phonePolicySupported) return
        val snap = knownStore?.snapshot ?: return
        val body = phonePolicyJson(_pairedDevices.map { it.btMac }, snap.autoConnect)
        scope.launch(Dispatchers.IO) {
            val status = client?.mgmtAction(Ocbm.MGMT_SET_POLICY, body.toByteArray(Charsets.UTF_8))
            logInfo("[DEVICE_MGMT] SET_POLICY ($reason) $body status=$status", tag = Logger.Tags.ADAPTR)
        }
    }

    /**
     * Re-assert the policy once the session is up. btd only listens once the box has brought its
     * radios up after SUBSCRIBE, so try a few times; every try is idempotent on the box.
     */
    private fun syncPhonePolicyAfterSubscribe() {
        scope.launch {
            for (waitMs in POLICY_SYNC_DELAYS_MS) {
                delay(waitMs)
                if (!phonePolicySupported) return@launch
                val snap = knownStore?.snapshot ?: return@launch
                val body = phonePolicyJson(_pairedDevices.map { it.btMac }, snap.autoConnect)
                val status = withContext(Dispatchers.IO) { client?.mgmtAction(Ocbm.MGMT_SET_POLICY, body.toByteArray(Charsets.UTF_8)) }
                logInfo("[DEVICE_MGMT] SET_POLICY (session) $body status=$status", tag = Logger.Tags.ADAPTR)
                if (status == 0) return@launch
            }
        }
    }

    /**
     * Carlink3: route guidance (0x5201 summary, with the live maneuver index) and maneuver (0x5202,
     * one per route step) records. The summary also claims/releases NAVIGATION app focus (the 3P way
     * to tell AAOS the phone is now the navigator); both feed the cluster/HUD trip.
     */
    private fun onNavigationJson(obj: JSONObject) {
        when (obj.optString("kind")) {
            "routeGuidance" -> {
                if (obj.has("routeGuidanceState")) {
                    val active = obj.optInt("routeGuidanceState") != 0
                    scope.launch { appFocusClaimer?.setNavigating(active) }
                }
                OcbmNavigationFeed.onRouteGuidance(obj)
            }
            "maneuver" -> OcbmNavigationFeed.onManeuver(obj)
            "laneGuidance" -> OcbmNavigationFeed.onLaneGuidance(obj)
        }
    }

    private fun onMetadata(
        marker: Int,
        payload: ByteArray,
    ) {
        when (marker) {
            Ocbm.META_JSON -> {
                val obj = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull() ?: return
                when (obj.optString("kind")) {
                    "nowPlaying" -> scope.launch { processNowPlaying(obj) }
                    "callState" -> CallState.fromJson(obj)?.let { cs -> scope.launch { onCallState(cs) } }
                    // 0 = no route. Claiming NAVIGATION app focus is the 3P way to tell AAOS the
                    // phone is now the navigator; a native maps app mid-route is told it lost it.
                    // Carlink3: both records also drive the cluster/HUD trip (OcbmNavigationFeed).
                    "routeGuidance", "maneuver", "laneGuidance" -> onNavigationJson(obj)
                    else -> Unit
                }
            }
            Ocbm.META_ARTWORK -> {
                if (payload.size < 2) return
                val id = payload[0].toInt() and 0xFF
                val jpeg = payload.copyOfRange(1, payload.size)
                synchronized(artworkById) { artworkById[id] = jpeg }
                // Art can arrive before OR after the track that references it, so attach on
                // whichever side lands second.
                if (pendingArtworkId == id) scope.launch { attachArtwork(id) }
            }
            Ocbm.META_CMD -> onCommandPlist(payload)
            else -> Unit // META_CORNERMASK is not consumed by the media surface
        }
    }

    /**
     * An inbound iPhone `POST /command`, forwarded verbatim as a binary plist.
     *
     * The one we act on is `requestUI` — Apple's own words for it are "the function to call when the
     * controller requests accessory UI" (`AirPlayReceiverSession.h:189`). That is what the user taps
     * when they pick our OEM icon on the CarPlay home screen to get back to this app, so it is the
     * return path for the icon advertised in `oemIconConfig`.
     *
     * Runs on the OCBM read thread, so it parses and hands off — [BinaryPlist] returns null rather
     * than throwing on anything malformed, because an exception here would take the dispatch loop
     * down with it. Unrecognised verbs are logged once each: the box forwards every command iOS
     * sends, and a silent drop is how the previous one of these went unnoticed.
     */
    private fun onCommandPlist(payload: ByteArray) {
        val d = BinaryPlist.parseDict(payload)
        if (d == null) {
            logWarn("[META] META_CMD payload is not a readable plist (${payload.size} B)", tag = Logger.Tags.ADAPTR)
            return
        }
        when (val type = d["type"] as? String) {
            "requestUI" -> {
                val url = (d["params"] as? Map<*, *>)?.get("url") as? String
                logInfo("[META] requestUI${url?.let { " ($it)" } ?: ""} — bringing the app forward", tag = Logger.Tags.ADAPTR)
                scope.launch { callback?.onHostUIPressed() }
            }
            null -> logWarn("[META] META_CMD has no string 'type'", tag = Logger.Tags.ADAPTR)
            else -> if (unhandledCommands.add(type)) logInfo("[META] command '$type' (not handled)", tag = Logger.Tags.ADAPTR)
        }
    }

    private fun attachArtwork(id: Int) {
        val jpeg = synchronized(artworkById) { artworkById[id] } ?: return
        lastAlbumCover = jpeg
        publishMetadata()
    }

    /**
     * Each change in the phone's own play status, to the drive log (added in v35), so each
     * steering-wheel press can be matched to what the phone, and so the session, said at that moment.
     */
    private fun logPlayStatusChange(status: Int?) {
        if (status == null || status == lastPlayStatus) return
        val name =
            when (status) {
                0 -> "stop"
                1 -> "play"
                2 -> "pause"
                3 -> "seek forward"
                4 -> "seek back"
                else -> "unknown"
            }
        logInfo("[MEDIA] phone playbackStatus ${lastPlayStatus ?: "-"} -> $status ($name)", tag = Logger.Tags.MEDIA_SESSION)
        lastPlayStatus = status
    }

    /**
     * The song length to publish for this record ([TrackChangeHold]). A new song's length that arrives
     * ahead of its title is held and the current length kept, so the car never gets the old title with
     * the new length; the title then takes the held length instead of a cleared one. If no title comes
     * within [TrackChangeHold.TITLE_WAIT_MS], the length is published under the current title.
     */
    private fun songLength(
        titleChanged: Boolean,
        reported: Long?,
        elapsed: Long?,
        current: Long,
    ): Long {
        val now = SystemClock.elapsedRealtime()
        if (reported != null && reported > 0) lastLengthAtMs = now
        if (titleChanged) {
            heldLengthJob?.cancel()
            val held = heldLength
            heldLength = null
            if (held != null && reported == null) logInfo("[MEDIA] title arrived after its length; both sent together", tag = Logger.Tags.MEDIA_SESSION)
            // A length that came in its own record just before the title is this song's, not the last's.
            val ledBy = if (TrackChangeHold.lengthLedTitle(lastLengthAtMs, now)) current else lastDuration
            return reported ?: held ?: ledBy
        }
        if (!TrackChangeHold.startsHold(reported, current, elapsed)) return reported ?: lastDuration
        if (heldLength == null) {
            logInfo("[MEDIA] new song length ${reported}ms arrived before its title; holding it", tag = Logger.Tags.MEDIA_SESSION)
            heldLengthJob =
                scope.launch {
                    delay(TrackChangeHold.TITLE_WAIT_MS)
                    releaseHeldLength()
                }
        }
        heldLength = reported
        return current
    }

    /** No title came for a held length: publish it under the current title. Main thread. */
    private fun releaseHeldLength() {
        val held = heldLength ?: return
        heldLength = null
        lastDuration = held
        logInfo("[MEDIA] no new title within ${TrackChangeHold.TITLE_WAIT_MS}ms; length ${held}ms sent under the current title", tag = Logger.Tags.MEDIA_SESSION)
        publishMetadata()
    }

    /**
     * The session's PLAYING bit, which AAOS uses to pick its primary media source — the session the
     * steering-wheel keys and the HUD title follow. It is the phone's own play status ([PlayStatus]:
     * play, pause and stop count; a seek leaves it), nothing else: every PAUSED↔PLAYING flip is a
     * transition the car acts on, and counting audible audio as playing (1.01) added flips of its own.
     */
    private fun pushPlaybackState() {
        mediaSessionManager?.updatePlaybackState(playing = lastIsPlaying, position = lastPosition)
        nowPlayingState.value = NowPlayingState(nowPlayingInfo(), lastIsPlaying, lastPosition, lastPositionAtMs)
    }

    /** The cached track to the media session and the pane's media card together. Main thread. */
    private fun publishMetadata() {
        val info = nowPlayingInfo()
        mediaSessionManager?.updateMetadata(info)
        nowPlayingState.value = nowPlayingState.value.copy(info = info)
    }

    /** The cached track as one value for the session. Main thread (all `lastMedia*` writers are). */
    private fun nowPlayingInfo(): NowPlayingInfo =
        NowPlayingInfo(
            title = lastMediaSongName,
            artist = lastMediaArtistName,
            album = lastMediaAlbumName,
            appName = lastMediaAppName,
            genre = lastMediaGenre,
            composer = lastMediaComposer,
            trackNumber = lastTrackNumber,
            trackCount = lastTrackCount,
            durationMs = lastDuration,
            albumArt = lastAlbumCover,
        )

    /**
     * Consume one `nowPlaying` record.
     *
     * iAP2 NowPlaying is a DELTA stream: an elapsed-only frame must not blank the title, and the
     * full attribute set is pushed on track change. That is the same shape the riddleBox path had
     * to handle, so the merge and change-detection logic below is carried over intact — only the
     * field names changed.
     */
    private fun processNowPlaying(obj: JSONObject) {
        val newSongName = obj.optString("title").takeIf { it.isNotEmpty() }

        // Snapshot before the song-change reset so incremental updates are still detectable after
        // the caches are overwritten. Without this, an artist-only frame was silently dropped and
        // the cluster showed a title with no artist until the next track.
        val previousSongName = lastMediaSongName
        val previousArtist = lastMediaArtistName
        val previousAlbum = lastMediaAlbumName
        val previousAppName = lastMediaAppName
        val previousDuration = lastDuration
        val titleChanged = newSongName != null && newSongName != previousSongName
        if (titleChanged) clearTrackForNewTitle()

        newSongName?.let { lastMediaSongName = it }
        val textChanged = mergeTextFields(obj, previousArtist, previousAlbum, previousAppName) or mergeTrackFields(obj)
        val albumCover = mergeArtwork(obj)
        val elapsed = if (obj.has("elapsedMs")) obj.optLong("elapsedMs") else null
        val duration =
            songLength(
                titleChanged = titleChanged,
                reported = if (obj.has("durationMs")) obj.optLong("durationMs") else null,
                elapsed = elapsed,
                current = previousDuration,
            )
        val position = elapsed ?: lastPosition
        // playbackStatus: 0 stop, 1 play, 2 pause, 3/4 seek. 0 is the clear-snapshot signal. A seek is
        // not a pause ([PlayStatus]): treating it as one had the session reporting PAUSED for 17-24 s of
        // audible music after each track change (10-23), and AAOS routes the steering-wheel keys to
        // whichever session is PLAYING, so presses in those windows went to the radio.
        val playStatus = if (obj.has("playbackStatus")) obj.optInt("playbackStatus") else null
        val isPlaying = PlayStatus.playing(playStatus, lastIsPlaying)
        logPlayStatusChange(playStatus)
        val pressedPlay = connectPlayFor(playStatus)

        lastDuration = duration
        lastPosition = position
        if (elapsed != null) lastPositionAtMs = SystemClock.elapsedRealtime()
        lastIsPlaying = isPlaying || pressedPlay

        publishIfChanged(titleChanged || textChanged || albumCover != null, duration, previousDuration)
        pushPlaybackState()
        markStreamingIfFirstMedia()
    }

    /**
     * Publish the track when something in it changed. Broad on purpose, so incremental artist / album /
     * app-name / duration-only updates are not suppressed; position-only ticks still short-circuit
     * because none of these change.
     */
    private fun publishIfChanged(
        wordsOrArtChanged: Boolean,
        duration: Long,
        previousDuration: Long,
    ) {
        if (!wordsOrArtChanged && (duration <= 0 || duration == previousDuration)) return
        publishMetadata()
        CarlinkMediaBrowserService.updateNowPlaying(lastMediaSongName, lastMediaArtistName)
    }

    /** A new title: the previous song's attributes go, so none of them is shown under the new one. The app name stays — it rarely changes mid-session. */
    private fun clearTrackForNewTitle() {
        lastMediaSongName = null
        lastMediaArtistName = null
        lastMediaAlbumName = null
        lastMediaGenre = null
        lastMediaComposer = null
        lastTrackNumber = 0
        lastTrackCount = 0
        lastAlbumCover = null
        lastDuration = 0L
        lastPosition = 0L
    }

    /**
     * First real media is the STREAMING edge.
     *
     * riddleBox used the first video DATA frame; the seam's first decoded frame would be
     * equivalent, but metadata is the signal that actually means "there is something to show".
     * Stamping [lastStreamingStartMs] here is also what makes Pattern C measurable — it is the
     * start of the window a short-lived session is measured against.
     */
    private fun markStreamingIfFirstMedia() {
        if (state != State.DEVICE_CONNECTED) return
        lastStreamingStartMs = SystemClock.elapsedRealtime()
        setState(State.STREAMING)
    }

    /**
     * Merge artist / album / appName from a delta record into the cache.
     *
     * Returns whether any of them actually CHANGED — which is the whole reason the caller passes
     * the pre-merge values in: iAP2 sends these incrementally, so an artist-only frame must still
     * trigger a publish even though the title did not move.
     */
    private fun mergeTextFields(
        obj: JSONObject,
        previousArtist: String?,
        previousAlbum: String?,
        previousAppName: String?,
    ): Boolean {
        var changed = false
        obj.optString("artist").takeIf { it.isNotEmpty() }?.let {
            lastMediaArtistName = it
            if (it != previousArtist) changed = true
        }
        obj.optString("album").takeIf { it.isNotEmpty() }?.let {
            lastMediaAlbumName = it
            if (it != previousAlbum) changed = true
        }
        obj.optString("appName").takeIf { it.isNotEmpty() }?.let {
            lastMediaAppName = it
            if (it != previousAppName) changed = true
        }
        return changed
    }

    /** Genre / composer / track position — the rest of what the macOS host renders. Delta-merged like the text. */
    private fun mergeTrackFields(obj: JSONObject): Boolean {
        var changed = false
        obj.optString("genre").takeIf { it.isNotEmpty() }?.let {
            if (it != lastMediaGenre) changed = true
            lastMediaGenre = it
        }
        obj.optString("composer").takeIf { it.isNotEmpty() }?.let {
            if (it != lastMediaComposer) changed = true
            lastMediaComposer = it
        }
        if (obj.has("trackNumber")) {
            val n = obj.optInt("trackNumber")
            if (n != lastTrackNumber) changed = true
            lastTrackNumber = n
        }
        if (obj.has("trackCount")) {
            val n = obj.optInt("trackCount")
            if (n != lastTrackCount) changed = true
            lastTrackCount = n
        }
        return changed
    }

    /**
     * Note the track's artwork id and return the bytes if they have already arrived.
     *
     * Match on the LOW BYTE: the wire id in `META_ARTWORK` is one byte while `nowPlaying`'s
     * `artworkId` is the full iAP2 integer, so a full-int comparison silently drops every artwork
     * with an id >= 256.
     */
    private fun mergeArtwork(obj: JSONObject): ByteArray? {
        if (!obj.has("artworkId")) return null
        val id = obj.optInt("artworkId") and 0xFF
        pendingArtworkId = id
        val bytes = synchronized(artworkById) { artworkById[id] } ?: return null
        lastAlbumCover = bytes
        return bytes
    }

    // ==================== Private Methods ====================

    private fun setState(newState: State) {
        // HAZARD: the callback dispatch and updateMediaSessionState fire OUTSIDE the CAS, so two
        // concurrent setState calls could dispatch in unspecified order even though each
        // getAndSet is atomic. Safe today because callers are the OCBM read thread and main at
        // non-overlapping phases — but that is a property of the callers, not of the
        // AtomicReference. A third caller on another thread needs a lock around the whole body.
        val oldState = currentState.getAndSet(newState)
        if (oldState != newState) {
            callback?.onStateChanged(newState)
            if (Looper.myLooper() == Looper.getMainLooper()) {
                updateMediaSessionState(newState)
            } else {
                // Binder work (FGS start/stop, wake-lock acquire) must not run on the OCBM read
                // thread. Main executes posts in order, so transitions keep their sequence.
                scope.launch { updateMediaSessionState(newState) }
            }
        }
    }

    @Volatile private var lastStatusText = ""

    private fun setStatusText(text: String) {
        // Timestamped in the trail so a drive log shows how long each connecting step really lasts.
        if (text != lastStatusText) logInfo("[STATUS] $text", tag = Logger.Tags.ADAPTR)
        lastStatusText = text
        callback?.onStatusTextChanged(text)
        // Mirror status to the MediaSession placeholder so cluster, cardview and CarMediaApp all
        // show the same state the app UI shows. Gated on != STREAMING: once real track metadata
        // is flowing, status text would clobber the live title/artist.
        if (state != State.STREAMING) {
            mediaSessionManager?.updatePlaceholderArtist(text)
        }
    }

    /**
     * Wire the projection state machine to the MediaSession active-flag.
     *
     * The session is INACTIVE by default and ACTIVE only once a phone is actually keyed. This
     * keeps Carlink out of AAOS's playback-primary slot when nothing is projecting.
     *
     * KNOWN OS DEFICIENCY — a stale homescreen Media card after force-stop is an AAOS platform
     * bug reproduced identically on first-party apps: CarLauncher caches the pre-force-stop
     * MediaController and never rebinds to the new session token. This wiring is kept because it
     * is semantically correct, not because it resolves that.
     */
    private fun updateMediaSessionState(state: State) {
        when (state) {
            State.CONNECTING -> {
                // Activate NOW with a "preparing to play" arbitration signal, at USB-attach time.
                // Without it the session stays idle through CONNECTING and only goes BUFFERING
                // later — by which time an already-PLAYING source (e.g. FM) is undisplaceable,
                // because CarMediaService only lets a newly-PLAYING source displace a
                // non-PLAYING one.
                mediaSessionManager?.setProjectionActive(connectingPhase = true)
                CarlinkMediaBrowserService.startConnectionForeground(context)
                acquireWakeLock()
            }

            State.DISCONNECTED -> {
                connectPlay.disarm()
                connectPlayJob?.cancel()
                mediaSessionManager?.setInactive()
                CarlinkMediaBrowserService.clearNowPlaying()
                // Keep the FGS through an active reconnect backoff. This branch runs POSTED to
                // main, and scheduleReconnect has usually already issued its own start by the
                // time it executes — stopping here would land AFTER that start and poison the
                // queued intent, leaving no LMK protection for the whole backoff window.
                if (reconnectJob?.isActive != true) {
                    CarlinkMediaBrowserService.stopConnectionForeground(context)
                }
                releaseWakeLock()
            }

            State.DEVICE_CONNECTED -> {
                // A session: the phone's first play status decides whether Play is pressed for it.
                connectPlay.arm()
                // Re-publish with phase-specific text now that the handshake is behind us and we
                // are only waiting for the first media.
                mediaSessionManager?.setProjectionActive(connectingPhase = false)
                CarlinkMediaBrowserService.startConnectionForeground(context)
            }

            State.STREAMING -> {
                CarlinkMediaBrowserService.startConnectionForeground(context)
                acquireWakeLock()
                // Session is already ACTIVE from DEVICE_CONNECTED; metadata drives the rest.
            }
        }
    }

    /**
     * Acquire (or refresh) a partial wake lock so USB transfers and heartbeats continue when the
     * app is backgrounded.
     *
     * The lock is non-reference-counted, so a repeat acquire simply restarts the safety timeout
     * and one release fully releases it. The periodic refresh exists because a session longer
     * than the timeout silently lost the lock mid-drive with no re-acquisition path.
     */
    @Synchronized
    private fun acquireWakeLock() {
        val wasHeld = wakeLock.isHeld
        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
        if (!wasHeld) {
            logInfo("[WAKE_LOCK] Acquired partial wake lock", tag = Logger.Tags.USB)
        } else {
            logDebug("[WAKE_LOCK] Refreshed wake lock timeout", tag = Logger.Tags.USB)
        }
        wakeLockRefreshJob?.cancel()
        wakeLockRefreshJob =
            scope.launch {
                while (isActive) {
                    delay(WAKE_LOCK_REFRESH_INTERVAL_MS)
                    if (wakeLock.isHeld) {
                        wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
                        logDebug("[WAKE_LOCK] Periodic refresh", tag = Logger.Tags.USB)
                    }
                }
            }
    }

    /**
     * Release the wake lock and stop the periodic refresh. Synchronized with [acquireWakeLock]:
     * both run from setState on either the read thread or main, and an interleaved cancel/launch
     * pair could orphan a refresh job.
     */
    @Synchronized
    private fun releaseWakeLock() {
        wakeLockRefreshJob?.cancel()
        wakeLockRefreshJob = null
        if (wakeLock.isHeld) {
            wakeLock.release()
            logInfo("[WAKE_LOCK] Released wake lock", tag = Logger.Tags.USB)
        }
    }

    /**
     * Error handler for session failures.
     *
     * Callable from the OCBM read thread, the detach broadcast (main), and client-owned threads —
     * a physical unplug fires it from two at once. The public entry posts under [lifecycleMutex]
     * so executions serialize and the blocking teardown runs on IO.
     */
    private fun handleError(error: String) {
        // Capture session identity AT POST TIME: the queued impl may run after a new session has
        // come up (fast unplug/replug where a new start() wins the mutex first). Without this, a
        // stale error would tear down a healthy session.
        val erroredClient = client
        scope.launch {
            lifecycleMutex.withLock {
                withContext(Dispatchers.IO) { handleErrorImpl(error, erroredClient) }
            }
        }
    }

    private fun handleErrorImpl(
        error: String,
        erroredClient: OcbmClient?,
    ) {
        if (erroredClient != null && erroredClient !== client) {
            logDebug("[ERROR] Stale error from a replaced session, ignoring: $error", tag = Logger.Tags.ADAPTR)
            return
        }
        // Dedup: a second report of the same physical failure arrives with everything already
        // torn down. Skip — but only when there is genuinely nothing to do.
        if (client == null && transport == null && state == State.DISCONNECTED) {
            logDebug("[ERROR] Duplicate error report after teardown, ignoring: $error", tag = Logger.Tags.ADAPTR)
            return
        }
        // hadPriorSession is intentionally NOT reset here (only in stop()), so escalation can
        // distinguish "broken after a prior session" from "never connected".

        logError("Adapter error: $error", tag = Logger.Tags.ADAPTR)

        cancelDelayedKeyframe()
        currentPhoneType = null
        currentWifi = null
        codecDeferred = true
        callback?.onPhoneTypeChanged(PhoneType.UNKNOWN)
        clearCachedMediaMetadata()
        clearSessionUiState()
        stopMicrophoneCapture()

        retireVideoEpoch("session error")
        releaseAudio()

        // Skip the graceful CT_STOP: every path into here is a dead pipe (no HELLO_ACK, a failed
        // SUBSCRIBE write, USB detach, link dead), so the write could only block for the bulk
        // timeout. The box's heartbeat watchdog declares us gone on its own.
        client?.stop(sendStop = false)
        client = null
        transport?.stop()
        transport = null

        val isNoResponse = error.contains("no initial response")
        if (isNoResponse) consecutiveNoResponse++ else consecutiveNoResponse = 0

        if (lastStreamingStartMs > 0) {
            val sessionDuration = SystemClock.elapsedRealtime() - lastStreamingStartMs
            if (sessionDuration < SHORT_SESSION_THRESHOLD_MS) {
                shortLivedStreamingCount++
            } else {
                // A session survived past the threshold — the link is fundamentally stable, so
                // clear the instability history. This, not connect, is the correct reset point.
                shortLivedStreamingCount = 0
            }
            lastStreamingStartMs = 0L
        }

        setState(State.DISCONNECTED)

        // Escalation status is set AFTER scheduleReconnect: it unconditionally writes
        // "Reconnecting (n/5)...", which would otherwise instantly clobber the advice.
        if (isUsbDisconnectError(error)) {
            scheduleReconnect()
            if (consecutiveNoResponse >= 2) {
                setStatusText("Adapter not responding — reboot adapter")
                logWarn("[ESCALATION] Pattern A: $consecutiveNoResponse consecutive no-response errors", tag = Logger.Tags.USB)
            } else if (shortLivedStreamingCount >= SHORT_SESSION_ESCALATION_COUNT) {
                setStatusText("Connection unstable — reboot adapter")
                logWarn("[ESCALATION] Pattern C: $shortLivedStreamingCount short-lived sessions", tag = Logger.Tags.USB)
            } else if (isNoResponse) {
                setStatusText("Adapter not responding — reconnecting...")
            }
        }
    }

    /**
     * Failure exit for [startImpl] before a client exists. These paths used to just return, which
     * silently killed an in-progress reconnect chain: after an adapter reboot (~50 s to
     * re-enumerate) attempt 1 fired at +2 s, the adapter search timed out, and the remaining attempts
     * never ran despite MAX_RECONNECT_ATTEMPTS.
     */
    private fun handleFailedStart(statusMsg: String) {
        transport?.stop()
        transport = null
        setState(State.DISCONNECTED)
        setStatusText(statusMsg)
        if (reconnectAttempts > 0) {
            logInfo("[RECONNECT] start() failed pre-session ($statusMsg) — continuing chain", tag = Logger.Tags.USB)
            scheduleReconnect()
        }
    }

    private fun isUsbDisconnectError(error: String): Boolean {
        val lowerError = error.lowercase()
        return lowerError.contains("disconnect") ||
            lowerError.contains("detach") ||
            lowerError.contains("transfer") ||
            lowerError.contains("usb") ||
            lowerError.contains("no initial response")
    }

    /**
     * Schedule an auto-reconnect with exponential backoff: 2s, 4s, 8s, 16s, 30s (capped), then
     * give up so a dead adapter cannot spin forever.
     *
     * The delays also matter to the BOX: it escalates on five presence edges in twenty seconds,
     * climbing to a reboot that spends from a persistent budget. Backing off keeps us clear of it.
     */
    private fun scheduleReconnect() {
        reconnectJob?.cancel()

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            logWarn(
                "[RECONNECT] Max attempts ($MAX_RECONNECT_ATTEMPTS) reached, giving up. " +
                    "noResponse=$consecutiveNoResponse shortSessions=$shortLivedStreamingCount hadPrior=$hadPriorSession",
                tag = Logger.Tags.USB,
            )
            val giveUpMessage =
                when {
                    consecutiveNoResponse >= 2 -> "Adapter not responding — reboot adapter"
                    shortLivedStreamingCount >= SHORT_SESSION_ESCALATION_COUNT -> "Connection unstable — reboot adapter"
                    hadPriorSession -> "Phone not reconnecting — reboot adapter"
                    else -> "Adapter not responding — unplug and replug adapter"
                }
            // reconnectAttempts resets but the escalation counters do NOT: a user who taps
            // reconnect after give-up should still see the right advice on the first failure.
            reconnectAttempts = 0
            setStatusText(giveUpMessage)
            CarlinkMediaBrowserService.stopConnectionForeground(context)
            return
        }

        CarlinkMediaBrowserService.startConnectionForeground(context)

        val backoff =
            minOf(
                INITIAL_RECONNECT_DELAY_MS * (1L shl reconnectAttempts),
                MAX_RECONNECT_DELAY_MS,
            )
        reconnectAttempts++

        logInfo("[RECONNECT] Scheduling attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS in ${backoff}ms", tag = Logger.Tags.USB)
        setStatusText("Reconnecting ($reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)...")

        reconnectJob =
            scope.launch {
                delay(backoff)
                if (state == State.DISCONNECTED) {
                    logInfo("[RECONNECT] Attempting reconnection...", tag = Logger.Tags.USB)
                    try {
                        withContext(Dispatchers.IO) { start() }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Expected: a pre-session failure continues the chain via
                        // handleFailedStart→scheduleReconnect, which cancels THIS job.
                        throw e
                    } catch (e: Exception) {
                        logError("[RECONNECT] Reconnection failed: ${e.message}", tag = Logger.Tags.USB)
                    }
                } else {
                    logInfo("[RECONNECT] Already connected, cancelling reconnect", tag = Logger.Tags.USB)
                    reconnectAttempts = 0
                }
            }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
    }

    /**
     * Schedule keyframe requests.
     *
     * 1. Initial (2.5 s): a cold-start safety net. The session's natural IDR normally decodes
     *    fine, but on this Intel VPU a first-session decoder can come up poisoned, and a fresh
     *    IDR on a now-warm codec clears it.
     * 2. Periodic (30 s): passive self-healing against silent mid-session decoder corruption.
     *    The gap watchdog only catches complete decode failure, not progressive degradation from
     *    corrupted reference frames, and a periodic IDR is the only fix for that.
     *
     * Both are platform mitigations, independent of protocol — they survived the migration
     * unchanged apart from the verb they send. The request is throttled client-side, so this
     * cannot collide with the seam's own gap-driven requests.
     */
    @Synchronized
    private fun scheduleDelayedKeyframe() {
        frameIntervalJob?.cancel()
        frameIntervalJob =
            scope.launch(Dispatchers.IO) {
                logInfo("[FRAME_INTERVAL] Keyframe schedule armed (2.5s initial, 30s periodic)", tag = Logger.Tags.VIDEO)
                delay(2500)
                client?.requestKeyframe()
                var requestCount = 0
                while (isActive) {
                    delay(30000)
                    requestCount++
                    val sent = client?.requestKeyframe() ?: false
                    logDebug("[FRAME_INTERVAL] Periodic keyframe #$requestCount sent=$sent", tag = Logger.Tags.VIDEO)
                }
            }
    }

    @Synchronized
    private fun cancelDelayedKeyframe() {
        if (frameIntervalJob?.isActive == true) {
            logDebug("[FRAME_INTERVAL] Cancelling pending keyframe schedule", tag = Logger.Tags.VIDEO)
            frameIntervalJob?.cancel()
        }
        frameIntervalJob = null
    }

    private fun log(message: String) {
        logDebug(message, tag = Logger.Tags.ADAPTR)
    }
}

/** How long the phone gets to start after the connect-time Play before the session reports it paused after all. */
private const val CONNECT_PLAY_GRACE_MS = 3_000L
