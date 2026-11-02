package com.carlink.ui

import android.view.MotionEvent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.carlink.BuildConfig
import com.carlink.CarlinkManager
import com.carlink.R
import com.carlink.logging.logDebug
import com.carlink.logging.logInfo
import com.carlink.protocol.MessageSerializer
import com.carlink.protocol.MultiTouchAction
import com.carlink.protocol.PhoneType
import com.carlink.ui.adaptive.asWindowInsets
import com.carlink.ui.components.BoxStatusLine
import com.carlink.ui.components.BridgeNotStartingPanel
import com.carlink.ui.components.BridgeSetupPanel
import com.carlink.ui.components.CarlinkDialog
import com.carlink.ui.components.ConversionPanelOr
import com.carlink.ui.components.ForestFogEffect
import com.carlink.ui.components.InstallPanelOr
import com.carlink.ui.components.LoadingStatusView
import com.carlink.ui.components.LoadingSteps
import com.carlink.ui.components.NO_BACKGROUND_BRUSH
import com.carlink.ui.components.PhoneSetupPanelOr
import com.carlink.ui.components.UpdatePanelOr
import com.carlink.ui.components.VideoSurface
import com.carlink.ui.components.VideoSurfaceState
import com.carlink.ui.components.rememberScene
import com.carlink.ui.components.rememberVideoSurfaceState
import com.carlink.ui.pane.SplitPane
import com.carlink.ui.settings.BackgroundLogoStore
import com.carlink.ui.settings.ResetConnectionDialog
import com.carlink.ui.theme.AutomotiveDimens
import com.carlink.usb.CarlinkBridge
import com.carlink.util.EdgeInsets
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Poll period for the first-frame watch while DEVICE_CONNECTED. */
private const val FIRST_FRAME_POLL_MS = 100L

/**
 * Session-scoped UI state, one instance per [CarlinkManager] (a Reset Device rebuild replaces the
 * manager and therefore this too, so stale callbacks / touch state from the old manager can never
 * leak into the new session).
 */
@Stable
private class SessionUiState {
    var connectionState by mutableStateOf(CarlinkManager.State.DISCONNECTED)
    var statusText by mutableStateOf("Connect Adapter")

    /** Container (content-area) dimensions, used for the adapter OPEN / declared-panel resolution. */
    var containerSize by mutableStateOf(IntSize.Zero)

    /** Set once initialize() has run with a real surface + size; gates the one-shot start effect. */
    var initializedForStart by mutableStateOf(false)

    /** True once the decoder has put a frame on the Surface in DEVICE_CONNECTED (polled; see [SessionEffects]). */
    var videoOnScreen by mutableStateOf(false)

    /** The wireless pairing code awaiting Pair / Cancel (CT_PAIRING_CODE), or null. */
    var pairingCode by mutableStateOf<String?>(null)

    /**
     * carlink_native hid its loading overlay (and forwarded touch) from the FIRST VIDEO FRAME, which
     * was its STREAMING edge. OCBM stamps STREAMING on the first media metadata instead, so the same
     * behaviour needs the frame counter as well: projecting = STREAMING, or DEVICE_CONNECTED with
     * video already on screen.
     */
    val projecting: Boolean
        get() = connectionState == CarlinkManager.State.STREAMING || (connectionState == CarlinkManager.State.DEVICE_CONNECTED && videoOnScreen)

    // Plain fields, not snapshot state: only the touch handler reads/mutates them on the UI thread.
    var lastTouchTime = 0L
    val activeTouches = mutableMapOf<Int, TouchPoint>()
}

/**
 * Main projection screen: the CarPlay video (SurfaceView / HWC overlay) with touch forwarding,
 * and — while no session is streaming — the loading overlay (logo, spinner, status line) with the
 * Settings / Reset Device buttons, exactly the carlink_native main page. During a live session the
 * OEM "Exit" icon in CarPlay (`requestUI`) opens Settings via [onNavigateToSettings].
 *
 * The video surface is laid out to the DECLARED PANEL the session was built for
 * ([CarlinkManager.displayProfile]`.surfaceInsets` off the edge-to-edge window: the visible bars
 * plus the parity pixel): the same rectangle that went out in CT_SUBSCRIBE and that the decoder is
 * sized to, so touch — normalised over the surface — lands where iOS drew.
 */
@Composable
fun MainScreen(
    carlinkManager: CarlinkManager,
    onNavigateToSettings: () -> Unit,
    onStateChanged: (CarlinkManager.State) -> Unit = {},
    bridge: BridgeSetup = BridgeSetup(),
) {
    val ui = remember(carlinkManager) { SessionUiState() }
    val surfaceState = rememberVideoSurfaceState()
    val scope = rememberCoroutineScope()
    var showResetConfirm by remember(carlinkManager) { mutableStateOf(false) }
    SessionEffects(carlinkManager, ui, surfaceState, onNavigateToSettings, onStateChanged)

    // The surface rect comes from the SAME profile the config was built from — the bars the
    // session's display mode keeps visible plus the parity pixel — not from the live Compose
    // insets, which flip with transient bar reveals and would move the surface out from under
    // the pushed geometry. The safe area (cutout / waterfall / corners) only pads the buttons.
    val profile = carlinkManager.displayProfile
    val surfaceInsets = profile?.surfaceInsets ?: EdgeInsets.NONE
    val safeAreaInsets = profile?.safeAreaInsets ?: EdgeInsets.NONE

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        ProjectionSurface(
            carlinkManager,
            ui,
            surfaceState,
            Modifier.fillMaxSize().windowInsetsPadding(surfaceInsets.asWindowInsets()).clipToBounds(),
        )
        // The split layout's pane, over the part of the frame CarPlay leaves free; same rectangle as
        // the surface so its fraction of the frame is the pane's fraction of the screen.
        if (ui.projecting) {
            SplitPane(carlinkManager, Modifier.fillMaxSize().windowInsetsPadding(surfaceInsets.asWindowInsets()))
        }
        if (!ui.projecting) {
            LoadingOverlay(carlinkManager, ui.statusText, bridge)
            TopActions(
                onNavigateToSettings = onNavigateToSettings,
                onResetConnection = { showResetConfirm = true },
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .windowInsetsPadding(WindowInsets.safeDrawing.union(safeAreaInsets.asWindowInsets()))
                        .padding(24.dp),
            )
        }
    }

    // Reset Device = the SAME clean-slate reset as Settings ▸ Reset Connection, same confirmation.
    if (showResetConfirm) {
        ResetConnectionDialog(
            onConfirm = {
                logInfo("[UI_ACTION] Reset Device confirmed", tag = "UI")
                showResetConfirm = false
                scope.launch { carlinkManager.cleanSlateReset() }
            },
            onDismiss = { showResetConfirm = false },
        )
    }

    // The box published an SSP Numeric-Comparison code: the user must confirm on BOTH ends.
    ui.pairingCode?.let { code ->
        PairingCodeDialog(
            code = code,
            onAnswer = { accept ->
                logInfo("[UI_ACTION] Pairing ${if (accept) "PAIR" else "CANCEL"} for code $code", tag = "UI")
                carlinkManager.answerPairing(accept)
            },
        )
    }
}

/**
 * Wireless pairing prompt. The code is matched against the one on the iPhone; Pair sends
 * `CT_PAIR_CONFIRM 1`, Cancel `CT_PAIR_CONFIRM 0`. Not dismissable by scrim/back: an unanswered
 * prompt is cancelled by the box itself after ~55 s, and a stray tap must not be read as either.
 */
@Composable
private fun PairingCodeDialog(
    code: String,
    onAnswer: (Boolean) -> Unit,
) {
    CarlinkDialog(
        title = "Pair with iPhone?",
        text = "Confirm that this code matches the one shown on the iPhone.",
        confirmLabel = "Pair",
        onConfirm = { onAnswer(true) },
        onDismiss = { onAnswer(false) },
        icon = Icons.Default.Link,
        dismissible = false,
    ) {
        Text(
            text = code.chunked((code.length + 1) / 2).joinToString(" "),
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
        )
    }
}

/** The effects that bind the manager to the surface: init, one-shot start, night mode, state fan-out. */
@Composable
private fun SessionEffects(
    carlinkManager: CarlinkManager,
    ui: SessionUiState,
    surfaceState: VideoSurfaceState,
    onNavigateToSettings: () -> Unit,
    onStateChanged: (CarlinkManager.State) -> Unit,
) {
    // The callback object below is registered once per surface/size; read the latest lambdas
    // through rememberUpdatedState so a recomposed caller never leaves a stale capture inside it.
    val navigate by rememberUpdatedState(onNavigateToSettings)
    val stateSink by rememberUpdatedState(onStateChanged)

    LaunchedEffect(ui.connectionState) {
        logInfo("[UI_STATE] MainScreen connection state: ${ui.connectionState}", tag = "UI")
        stateSink(ui.connectionState)
        // Streaming gate drops UP/CANCEL while paused — clear stale pointers when the session
        // leaves STREAMING so they can't resurface as phantom MOVE pointers on resume.
        if (ui.connectionState != CarlinkManager.State.STREAMING) ui.activeTouches.clear()
    }

    // First-frame watch: while DEVICE_CONNECTED (video may already be decoding, STREAMING not yet
    // stamped) poll the frame counter so the overlay lifts on the first rendered frame, as the
    // carlink_native STREAMING edge did. Cancelled by the state change that keys it.
    // A frame rendered since entering the state, not the count so far: when the phone walks away the
    // manager drops STREAMING back to DEVICE_CONNECTED to show "Waiting for phone", and the old
    // session's frames lifted the overlay at once over the last frozen picture (2026-10-22).
    LaunchedEffect(ui.connectionState) {
        ui.videoOnScreen = false
        if (ui.connectionState != CarlinkManager.State.DEVICE_CONNECTED) return@LaunchedEffect
        val (startEpoch, startFrames) = carlinkManager.videoFrameMark()
        while (true) {
            val (epoch, frames) = carlinkManager.videoFrameMark()
            if (frames > 0 && (epoch != startEpoch || frames > startFrames)) break
            delay(FIRST_FRAME_POLL_MS)
        }
        logInfo("[UI_STATE] First video frame on screen in DEVICE_CONNECTED — lifting loading overlay", tag = "UI")
        ui.videoOnScreen = true
    }

    // Forward the AAOS day/night state to CarPlay: on reaching STREAMING and on every mid-session
    // theme flip (MainActivity handles the uiMode config change without recreating).
    val darkTheme = isSystemInDarkTheme()
    LaunchedEffect(darkTheme, ui.connectionState) {
        if (ui.connectionState == CarlinkManager.State.STREAMING) carlinkManager.setNightMode(darkTheme)
    }

    // Surface init for the adapter — uses container (content-area) dimensions. Idempotent across
    // Surface recreations; start() runs once per manager (the decoupled effect below).
    LaunchedEffect(surfaceState.surface, ui.containerSize) {
        val surface = surfaceState.surface ?: return@LaunchedEffect
        val size = ui.containerSize
        if (size.width <= 0 || size.height <= 0) return@LaunchedEffect
        val adapterWidth = size.width and 1.inv()
        val adapterHeight = size.height and 1.inv()
        logInfo(
            "[CARLINK_RESOLUTION] Container size: ${adapterWidth}x$adapterHeight (surface: ${surfaceState.width}x${surfaceState.height})",
            tag = "UI",
        )
        carlinkManager.initialize(
            surface = surface,
            surfaceWidth = adapterWidth,
            surfaceHeight = adapterHeight,
            callback =
                object : CarlinkManager.Callback {
                    override fun onStateChanged(state: CarlinkManager.State) {
                        ui.connectionState = state
                    }

                    override fun onStatusTextChanged(text: String) {
                        ui.statusText = text
                    }

                    override fun onHostUIPressed() {
                        // OEM "Exit" icon pressed in CarPlay → open Settings over the live video.
                        logInfo("[UI_NAV] Host UI requested — opening Settings", tag = "UI")
                        navigate()
                    }

                    override fun onPhoneTypeChanged(phoneType: PhoneType) {
                        logInfo("[UI_SURFACE] Phone type changed: $phoneType", tag = "UI")
                    }

                    override fun onPairingCodeChanged(code: String?) {
                        ui.pairingCode = code
                    }
                },
        )
        ui.initializedForStart = true
    }

    // One-shot start per manager, DECOUPLED from the surface/size effect above: a surface swap or
    // late layout pass mid-connect must not cancel start(), and surface/size churn can't cancel
    // this effect because it is keyed only on the manager.
    LaunchedEffect(carlinkManager) {
        snapshotFlow { ui.initializedForStart }.first { it }
        carlinkManager.start()
    }
}

/** The SurfaceView, keyed on the manager so a rebuild inflates a fresh one (and a fresh HWC plane). */
@Composable
private fun ProjectionSurface(
    carlinkManager: CarlinkManager,
    ui: SessionUiState,
    surfaceState: VideoSurfaceState,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier) {
        // Track the content area for the adapter OPEN resolution.
        val containerPx = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }
        LaunchedEffect(containerPx) {
            if (containerPx.width > 0 && containerPx.height > 0) ui.containerSize = containerPx
        }
        key(carlinkManager) {
            VideoSurface(
                modifier = Modifier.fillMaxSize(),
                onSurfaceAvailable = { surface, width, height ->
                    logInfo("[UI_SURFACE] Surface available: ${width}x$height", tag = "UI")
                    surfaceState.onSurfaceAvailable(surface, width, height)
                },
                onSurfaceDestroyed = {
                    logInfo("[UI_SURFACE] Surface destroyed", tag = "UI")
                    surfaceState.onSurfaceDestroyed()
                    carlinkManager.onSurfaceDestroyed()
                },
                onSurfaceSizeChanged = { width, height ->
                    logInfo("[UI_SURFACE] Surface size changed: ${width}x$height", tag = "UI")
                    surfaceState.onSurfaceSizeChanged(width, height)
                },
                onTouchEvent = { event ->
                    // Read state through the holder — this lambda is captured once in the
                    // AndroidView factory; a pre-computed val would snapshot DISCONNECTED forever.
                    if (ui.projecting) {
                        if (BuildConfig.DEBUG) {
                            val now = System.currentTimeMillis()
                            if (now - ui.lastTouchTime > 1000) {
                                logDebug(
                                    "[UI_TOUCH] touch: action=${event.actionMasked}, pointers=${event.pointerCount}" +
                                        ", surface=${surfaceState.width}x${surfaceState.height}" +
                                        ", container=${ui.containerSize.width}x${ui.containerSize.height}",
                                    tag = "UI",
                                )
                                ui.lastTouchTime = now
                            }
                        }
                        handleTouchEvent(
                            event,
                            ui.activeTouches,
                            carlinkManager,
                            surfaceState.width,
                            surfaceState.height,
                            ui.containerSize.width,
                            ui.containerSize.height,
                        )
                        // A lift on CarPlay's Dock tells the pane the driver left the app in front.
                        if (event.actionMasked == MotionEvent.ACTION_UP && surfaceState.width > 0 && surfaceState.height > 0) {
                            carlinkManager.onCarPlayTap(event.x / surfaceState.width, event.y / surfaceState.height)
                        }
                    }
                    true
                },
            )
        }
    }
}

/**
 * The connecting screen, from Carlink2: the chosen scene (Settings → Display → Loading screen),
 * drifting fog over the forest of the GMC and Chevrolet scenes only, the logo, and the status folded
 * into three ethereal steps with a plain action line when the driver has to do something. The box's own status line sits at the
 * bottom edge, dimmed, for the bench. Shown while no session streams.
 */
@Composable
private fun LoadingOverlay(
    carlinkManager: CarlinkManager,
    statusText: String,
    bridge: BridgeSetup,
) {
    val context = LocalContext.current
    val backgroundLogo by BackgroundLogoStore.get(context).flow.collectAsState()
    var projectionMode by remember(carlinkManager) { mutableStateOf(carlinkManager.projectionMode) }
    DisposableEffect(carlinkManager) {
        // Fires on every CT_PROJ_MODE / CT_BOX_HEALTH change, on the main thread.
        val l = CarlinkManager.BoxStatusListener { projectionMode = carlinkManager.projectionMode }
        carlinkManager.addBoxStatusListener(l)
        onDispose { carlinkManager.removeBoxStatusListener(l) }
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // ContentScale.Crop fills the panel and centre-crops the overflow (the scene is ~2.49:1,
        // so almost nothing is cropped) — no stretching. Same scene for both logos.
        // Decoded at panel width off the main thread (the photo scenes ship as large originals). The
        // gradient underneath is "No background", and covers a photo's first frames while it decodes.
        val panelWidth = LocalWindowInfo.current.containerSize.width
        val scene = rememberScene(backgroundLogo.drawableRes, panelWidth.coerceAtLeast(1))
        Box(modifier = Modifier.fillMaxSize().background(NO_BACKGROUND_BRUSH))
        if (scene != null) {
            Image(
                bitmap = scene,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (backgroundLogo.showsFog) ForestFogEffect()
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            // Half an inch below dead-centre, so the icon and words sit lower over the scene.
            modifier = Modifier.offset(y = 80.dp),
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_phone_projection),
                contentDescription = "Carlink",
                modifier = Modifier.height(220.dp),
            )
            Spacer(modifier = Modifier.height(24.dp))
            ConnectingWords(carlinkManager, statusText, bridge, projectionMode) { CarlinkBridge.openAppInfo(context) }
        }
        BoxStatusLine(
            carlinkManager,
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = 16.dp)
                .alpha(BOX_STATUS_ALPHA),
        )
    }
}

/**
 * Under the logo: what the driver can do about the bridge, or a panel (a stock adapter's conversion, the
 * adapter's one-file install, pairing an iPhone), or else the status words.
 */
@Composable
private fun ConnectingWords(
    carlinkManager: CarlinkManager,
    statusText: String,
    bridge: BridgeSetup,
    projectionMode: Byte,
    onOpenAppInfo: () -> Boolean,
) {
    when {
        // Carlink2 [210]/[211]: what the driver can do about the bridge, in place of the words.
        !bridge.installed -> BridgeSetupPanel(onOpenFiles = bridge.onOpenFiles)
        statusText.startsWith(BRIDGE_TROUBLE_PREFIX) -> BridgeNotStartingPanel(onOpenAppInfo = onOpenAppInfo)
        // A stock adapter's conversion, then an adapter update in flight or just ended, then the adapter's
        // one-file install (its trial and way back), then pairing an iPhone, before the words; the adapter's own
        // projection mode starts the last step: it flips to WIRELESS_CP/WIRED_CP when CarPlay really begins,
        // ahead of "Phone connected".
        else ->
            ConversionPanelOr(carlinkManager) {
                UpdatePanelOr(carlinkManager) {
                    InstallPanelOr(carlinkManager) {
                        PhoneSetupPanelOr(carlinkManager) {
                            LoadingStatusView(
                                statusText = statusText,
                                phoneConnected = LoadingSteps.projectionStarting(projectionMode),
                                androidAuto = LoadingSteps.isAndroidAuto(projectionMode),
                            )
                        }
                    }
                }
            }
    }
}

/** The box status line is for the bench, not the driver: present, but dimmed. */
private const val BOX_STATUS_ALPHA = 0.55f

/** Every bridge failure CarlinkManager reports starts this way ("USB bridge isn't responding", "… is out of date", "… could not open the adapter"). */
private const val BRIDGE_TROUBLE_PREFIX = "USB bridge"

/**
 * Carlink2 [210]: whether the USB bridge is installed, and the connecting screen's way to install it
 * (opens the head unit's Files app; returns false when none could be opened).
 */
data class BridgeSetup(
    val installed: Boolean = true,
    val onOpenFiles: () -> Boolean = { false },
)

/** Top-left "Settings" and "Reset Device" (full session rebuild) buttons of the loading overlay. */
@Composable
private fun TopActions(
    onNavigateToSettings: () -> Unit,
    onResetConnection: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val contentPadding = PaddingValues(horizontal = AutomotiveDimens.ButtonPaddingHorizontal, vertical = AutomotiveDimens.ButtonPaddingVertical)
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        FilledTonalButton(
            onClick = onNavigateToSettings,
            modifier = Modifier.heightIn(min = AutomotiveDimens.ButtonMinHeight),
            contentPadding = contentPadding,
        ) {
            Icon(imageVector = Icons.Default.Settings, contentDescription = "Settings", modifier = Modifier.size(AutomotiveDimens.IconSize))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Settings", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FilledTonalButton(
            onClick = onResetConnection,
            modifier = Modifier.heightIn(min = AutomotiveDimens.ButtonMinHeight),
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = colorScheme.errorContainer, contentColor = colorScheme.onErrorContainer),
            contentPadding = contentPadding,
        ) {
            Icon(imageVector = Icons.Default.RestartAlt, contentDescription = "Reset Device", modifier = Modifier.size(AutomotiveDimens.IconSize))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = "Reset Device", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ==================== Touch forwarding ====================

/** In-memory per-pointer touch record (normalized 0..1 coords + last action) for deduping MOVE spam. */
private data class TouchPoint(
    val x: Float,
    val y: Float,
    val action: MultiTouchAction,
)

/**
 * Handle touch on the SurfaceView. CarPlay multitouch (type 0x17): normalize to 0..1 of the
 * SurfaceView. A deadband suppresses sub-pixel MOVE spam.
 */
private fun handleTouchEvent(
    event: MotionEvent,
    activeTouches: MutableMap<Int, TouchPoint>,
    carlinkManager: CarlinkManager,
    surfaceWidth: Int,
    surfaceHeight: Int,
    containerWidth: Int,
    containerHeight: Int,
) {
    if (surfaceWidth == 0 || surfaceHeight == 0 || containerWidth == 0 || containerHeight == 0) return

    // ACTION_CANCEL terminates ALL pointers of the gesture, not just actionIndex (which is
    // always 0 for CANCEL). Marking only one pointer UP left the rest in the map forever —
    // re-sent as phantom held fingers in every later frame until Reset Connection.
    if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
        if (activeTouches.isEmpty()) return
        val touchList =
            activeTouches.entries.map { entry ->
                MessageSerializer.TouchPoint(
                    x = entry.value.x,
                    y = entry.value.y,
                    action = MultiTouchAction.UP,
                    id = entry.key,
                )
            }
        activeTouches.clear()
        carlinkManager.sendMultiTouch(touchList)
        return
    }

    val pointerIndex = event.actionIndex
    val pointerId = event.getPointerId(pointerIndex)

    val action =
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> MultiTouchAction.DOWN
            MotionEvent.ACTION_MOVE -> MultiTouchAction.MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> MultiTouchAction.UP
            else -> return
        }

    // Clamp: MotionEvent coords legitimately go outside the view during drags that exit
    // the surface; out-of-range values (-0.01, 1.03) must not reach the wire.
    val x = (event.getX(pointerIndex) / surfaceWidth).coerceIn(0f, 1f)
    val y = (event.getY(pointerIndex) / surfaceHeight).coerceIn(0f, 1f)

    var changed = false
    when (action) {
        MultiTouchAction.DOWN -> {
            activeTouches[pointerId] = TouchPoint(x, y, action)
            changed = true
        }

        MultiTouchAction.MOVE -> {
            for (i in 0 until event.pointerCount) {
                val id = event.getPointerId(i)
                val px = (event.getX(i) / surfaceWidth).coerceIn(0f, 1f)
                val py = (event.getY(i) / surfaceHeight).coerceIn(0f, 1f)
                activeTouches[id]?.let { existing ->
                    // Deadband ≈ 0.3% of the normalized surface — suppresses sub-pixel MOVE spam.
                    val dx = kotlin.math.abs(existing.x - px) * 1000
                    val dy = kotlin.math.abs(existing.y - py) * 1000
                    if (dx > 3 || dy > 3) {
                        activeTouches[id] = TouchPoint(px, py, MultiTouchAction.MOVE)
                        changed = true
                    }
                }
            }
        }

        MultiTouchAction.UP -> {
            // Only announce UP for pointers we actually announced DOWN for — a pointer
            // whose DOWN was swallowed (zero-size guard, mid-gesture stream start) would
            // otherwise emit a spurious lone UP for an id the peer never saw.
            if (activeTouches.containsKey(pointerId)) {
                activeTouches[pointerId] = TouchPoint(x, y, action)
                changed = true
            }
        }

        else -> {}
    }

    // Send only when something actually changed. Before, a MOVE where no pointer beat the
    // deadband still re-sent identical coordinates at input rate (the deadband saved
    // nothing on the USB path), and a MOVE with an empty map (DOWN swallowed by the
    // zero-size guard) emitted an empty 0x17 frame.
    if (!changed || activeTouches.isEmpty()) return

    val touchList =
        activeTouches.entries.map { entry ->
            MessageSerializer.TouchPoint(
                x = entry.value.x,
                y = entry.value.y,
                action = entry.value.action,
                id = entry.key,
            )
        }

    carlinkManager.sendMultiTouch(touchList)
    activeTouches.entries.removeIf { it.value.action == MultiTouchAction.UP }
    // DOWN is an edge event: demote to MOVE after its first send so a stationary finger
    // in a multi-touch gesture is not re-announced as a fresh DOWN dozens of times/sec.
    for (entry in activeTouches.entries) {
        if (entry.value.action == MultiTouchAction.DOWN) {
            entry.setValue(entry.value.copy(action = MultiTouchAction.MOVE))
        }
    }
}
