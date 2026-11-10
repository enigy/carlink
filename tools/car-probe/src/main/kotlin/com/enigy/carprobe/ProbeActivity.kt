package com.enigy.carprobe

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Parked: start or stop the drive log, take a snapshot, run the video test (item 5: can this head unit play an HLS
 * stream, how fast does it start; item 7: what a colour-correction pass over video costs), try calibration values by
 * eye, and export the logs to Downloads/CarlinkProbe for a USB stick.
 *
 * Laid out for a finger on the car's screen (0.2, after the 10 Nov drive, where the buttons were too small and too
 * close): a few big buttons with room between them. The video gets a box of its own that opens for the test and
 * closes with its Close button; while it is open, the calibration sliders take the buttons' place.
 */
class ProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var video: TextureView
    private lateinit var videoBox: View
    private lateinit var buttons: View
    private lateinit var calibration: View
    private lateinit var videoButton: Button
    private var player: MediaPlayer? = null

    // One video test at a time: on the 10 Nov drive three taps in a row started three players, and the frame counts
    // read 0. Each test has a number, and a callback from a test that has since been closed does nothing.
    private var test = 0
    private var busy = false
    private var waitingForSurface = false
    private var frames: FrameCounter? = null
    private val phases = ArrayList<Runnable>()

    // Calibration by eye: contrast and per-channel gain, applied to the test video as a colour matrix.
    private var contrast = 1f
    private val gains = floatArrayOf(1f, 1f, 1f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = text(STATUS_SP).apply { setPadding(0, 0, 0, dp(GAP)) }
        logView = text(LOG_SP)
        videoButton = button("Video test (parked)") { videoTest() }
        buttons =
            column(
                row(button("Start logging") { startLogging() }, button("Stop logging") { stopLogging() }),
                row(button("Snapshot now") { snapshot() }, button("Export logs") { export() }),
                row(videoButton),
            )
        calibration =
            column(
                slider("Contrast") { contrast = it; applyFilter() },
                slider("Red") { gains[0] = it; applyFilter() },
                slider("Green") { gains[1] = it; applyFilter() },
                slider("Blue") { gains[2] = it; applyFilter() },
                row(button("Save calibration") { saveCalibration() }),
            ).apply { visibility = View.GONE }
        val controls =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(PAD), dp(PAD), dp(PAD), dp(PAD))
                addView(status)
                addView(buttons)
                addView(calibration)
                addView(ScrollView(context).apply { addView(logView) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(GAP) })
            }
        video = TextureView(this).apply { surfaceTextureListener = surfaceListener }
        videoBox =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(PAD), dp(PAD), dp(PAD), dp(PAD))
                visibility = View.GONE
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(text(STATUS_SP).apply { text = "Video test" }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                        addView(button("Close video") { closeVideo() }, LinearLayout.LayoutParams(dp(CLOSE_WIDTH), WRAP_CONTENT))
                    },
                )
                addView(
                    FrameLayout(context).apply { addView(video, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER)) },
                    LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(GAP) },
                )
            }
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(controls, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
                addView(videoBox, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            }
        setContentView(root)
        ProbeLog.open(this)
        ProbeLog.listener = { main.post { refresh() } }
        refresh()
        say("Start logging asks for the permissions first; tap Start logging again after answering.")
    }

    override fun onDestroy() {
        ProbeLog.listener = null
        stopVideo("activity closed")
        super.onDestroy()
    }

    private fun startLogging() {
        val missing =
            packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty() && !askedOnce) {
            askedOnce = true
            requestPermissions(missing.toTypedArray(), 1)
            return
        }
        startForegroundService(Intent(this, ProbeService::class.java))
        say("logging; leave it running for the drive, then Stop logging and Export logs")
    }

    private var askedOnce = false

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        ProbeLog.log("perm", "what" to "request answered", "results" to permissions.zip(grantResults.toList()).associate { (p, g) -> p to (g == PackageManager.PERMISSION_GRANTED) })
        startLogging()
    }

    private fun stopLogging() {
        stopService(Intent(this, ProbeService::class.java))
        say("logging stopped")
    }

    private fun snapshot() {
        thread { Snapshots.all(this, null) }
        say("snapshot taken (no car service in the activity)")
    }

    private fun export() {
        thread { val r = ProbeLog.export(this); main.post { say(r) } }
    }

    private fun saveCalibration() {
        ProbeLog.log("display", "what" to "calibration chosen", "contrast" to contrast, "gains" to gains.toList())
        say("calibration saved to the log")
    }

    /**
     * Item 5 and 7: fetch an HLS playlist (internet?), play it into a TextureView (start time, size, errors), count
     * decoded frames and UI frame times for [PHASE_MS] plain, then [PHASE_MS] with a colour matrix over the video.
     * A tap while a test runs does nothing; Close video ends it.
     */
    private fun videoTest() {
        if (busy) return say("the video test is still running; Close video ends it")
        busy = true
        val mine = ++test
        videoButton.isEnabled = false
        openVideo()
        say("video test: fetching the playlist")
        thread {
            val started = System.nanoTime()
            val fetch =
                runCatching {
                    val c = URL(HLS_URL).openConnection() as HttpURLConnection
                    c.connectTimeout = TIMEOUT_MS
                    c.readTimeout = TIMEOUT_MS
                    val code = c.responseCode
                    val size = c.inputStream.use { it.readBytes().size }
                    "HTTP $code, $size B"
                }
            ProbeLog.log("video", "what" to "playlist fetch", "url" to HLS_URL, "result" to (fetch.getOrNull() ?: fetch.exceptionOrNull()), "ms" to ms(started))
            main.post {
                if (mine != test) return@post
                if (fetch.isSuccess) {
                    play(mine)
                } else {
                    say("no internet for the stream: ${fetch.exceptionOrNull()?.message}")
                    done()
                }
            }
        }
    }

    private fun play(mine: Int) {
        val st = video.surfaceTexture
        if (st == null) {
            // The box has only just opened: start when its surface exists.
            waitingForSurface = true
            return
        }
        val counter = FrameCounter()
        frames = counter
        val started = System.nanoTime()
        val mp = MediaPlayer()
        player = mp
        mp.setSurface(Surface(st))
        mp.setOnPreparedListener {
            if (mine != test) return@setOnPreparedListener
            ProbeLog.log("video", "what" to "prepared", "ms" to ms(started), "size" to "${it.videoWidth}x${it.videoHeight}")
            it.start()
        }
        mp.setOnInfoListener { _, what, extra ->
            if (mine == test) {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    ProbeLog.log("video", "what" to "first frame", "ms" to ms(started))
                    main.post { if (mine == test) measure(counter) }
                } else {
                    ProbeLog.log("video", "what" to "info", "code" to what, "extra" to extra)
                }
            }
            false
        }
        mp.setOnVideoSizeChangedListener { _, w, h -> if (mine == test) ProbeLog.log("video", "what" to "video size", "size" to "${w}x$h") }
        mp.setOnErrorListener { _, what, extra ->
            if (mine == test) {
                ProbeLog.log("video", "what" to "error", "code" to what, "extra" to extra, "ms" to ms(started))
                main.post { say("video error $what/$extra"); done() }
            }
            true
        }
        runCatching {
            mp.setDataSource(HLS_URL)
            mp.prepareAsync()
        }.onFailure {
            ProbeLog.log("video", "what" to "setDataSource", "error" to it)
            done()
        }
        say("video test: starting the stream")
    }

    /** Plain for PHASE_MS, then with the colour matrix for PHASE_MS; logs decoded fps and UI frame times for each. */
    private fun measure(counter: FrameCounter) {
        counter.phase("plain")
        later(PHASE_MS) {
            counter.report()
            setFilter(ColorMatrix(floatArrayOf(1.1f, 0f, 0f, 0f, -10f, 0f, 1.0f, 0f, 0f, -10f, 0f, 0f, 0.9f, 0f, -10f, 0f, 0f, 0f, 1f, 0f)))
            counter.phase("colour matrix")
            later(PHASE_MS) {
                counter.report()
                applyFilter()
                done()
                say("video test done; see the log. Try the sliders by eye, or Close video.")
            }
        }
    }

    private fun later(
        delayMs: Long,
        block: () -> Unit,
    ) {
        val r = Runnable { block() }
        phases += r
        main.postDelayed(r, delayMs)
    }

    /** The test is over (done or failed); the video, if any, keeps playing until Close video. */
    private fun done() {
        busy = false
        videoButton.isEnabled = true
    }

    private val surfaceListener =
        object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                s: SurfaceTexture,
                w: Int,
                h: Int,
            ) {
                if (waitingForSurface) {
                    waitingForSurface = false
                    play(test)
                }
            }

            override fun onSurfaceTextureSizeChanged(
                s: SurfaceTexture,
                w: Int,
                h: Int,
            ) = Unit

            override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true

            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {
                frames?.let { it.decoded++ }
            }
        }

    private inner class FrameCounter : Choreographer.FrameCallback {
        var decoded = 0
        private var name = ""
        private var phaseStart = 0L
        private var lastFrameNs = 0L
        private val intervals = ArrayList<Long>()
        private var running = false

        fun phase(n: String) {
            name = n
            decoded = 0
            intervals.clear()
            phaseStart = System.nanoTime()
            lastFrameNs = 0L
            if (!running) {
                running = true
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (lastFrameNs != 0L) intervals += (frameTimeNanos - lastFrameNs) / NS_PER_US
            lastFrameNs = frameTimeNanos
            if (player != null) Choreographer.getInstance().postFrameCallback(this) else running = false
        }

        fun report() {
            val secs = (System.nanoTime() - phaseStart) / 1e9
            val sorted = intervals.sorted()
            val p95 = if (sorted.isEmpty()) null else sorted[(sorted.size * P95).toInt().coerceAtMost(sorted.size - 1)] / 1000.0
            ProbeLog.log(
                "display",
                "what" to "video frame rate",
                "phase" to name,
                "decodedFps" to "%.1f".format(decoded / secs),
                "uiFps" to "%.1f".format(intervals.size / secs),
                "uiP95Ms" to p95,
                "jankOver20Ms" to intervals.count { it > JANK_US },
            )
        }
    }

    /** The box and the sliders show; the main buttons step aside while it is open. */
    private fun openVideo() {
        videoBox.visibility = View.VISIBLE
        buttons.visibility = View.GONE
        calibration.visibility = View.VISIBLE
    }

    private fun closeVideo() {
        stopVideo("closed by hand")
        videoBox.visibility = View.GONE
        calibration.visibility = View.GONE
        buttons.visibility = View.VISIBLE
        say("video closed")
    }

    private fun stopVideo(why: String) {
        test++ // whatever the closed test still had coming does nothing now
        phases.forEach { main.removeCallbacks(it) }
        phases.clear()
        waitingForSurface = false
        frames = null
        player?.let {
            runCatching { it.stop() }
            it.release()
            ProbeLog.log("video", "what" to "stopped", "why" to why)
        }
        player = null
        if (::videoButton.isInitialized) done()
    }

    private fun applyFilter() {
        val c = contrast
        val t = (1 - c) * HALF
        setFilter(ColorMatrix(floatArrayOf(c * gains[0], 0f, 0f, 0f, t, 0f, c * gains[1], 0f, 0f, t, 0f, 0f, c * gains[2], 0f, t, 0f, 0f, 0f, 1f, 0f)))
    }

    private fun setFilter(m: ColorMatrix) {
        video.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
    }

    private fun slider(
        label: String,
        onValue: (Float) -> Unit,
    ): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(BUTTON_SP).apply { text = label }, LinearLayout.LayoutParams(dp(LABEL_WIDTH), WRAP_CONTENT))
            addView(
                SeekBar(context).apply {
                    max = 100
                    progress = 50
                    // A thumb and track a finger can find while parked.
                    thumb =
                        GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setSize(dp(THUMB), dp(THUMB))
                            setColor(THUMB_COLOR)
                        }
                    minHeight = dp(TRACK)
                    maxHeight = dp(TRACK)
                    setPadding(dp(THUMB / 2), 0, dp(THUMB / 2), 0)
                    setOnSeekBarChangeListener(
                        object : SeekBar.OnSeekBarChangeListener {
                            // 0..100 -> 0.5..1.5
                            override fun onProgressChanged(
                                s: SeekBar,
                                p: Int,
                                fromUser: Boolean,
                            ) = onValue(0.5f + p / 100f)

                            override fun onStartTrackingTouch(s: SeekBar) = Unit

                            override fun onStopTrackingTouch(s: SeekBar) = Unit
                        },
                    )
                },
                LinearLayout.LayoutParams(0, dp(SLIDER_HEIGHT), 1f),
            )
        }

    private fun column(vararg views: View) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            views.forEach { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(GAP) }) }
        }

    /** Buttons side by side, equal widths, [GAP] apart. */
    private fun row(vararg views: View) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(GAP) }) }
        }

    private fun button(
        label: String,
        onClick: () -> Unit,
    ) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, BUTTON_SP)
        minHeight = dp(BUTTON_HEIGHT)
        minimumHeight = dp(BUTTON_HEIGHT)
        setOnClickListener { onClick() }
    }

    private fun text(sp: Float) = TextView(this).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, sp) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun say(text: String) {
        status.text = text
    }

    private fun refresh() {
        logView.text = ProbeLog.recentLines().asReversed().joinToString("\n")
    }

    private fun ms(startNs: Long) = (System.nanoTime() - startNs) / 1_000_000

    private companion object {
        // Apple's public HLS example (H.264, several bitrates).
        const val HLS_URL = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8"
        const val TIMEOUT_MS = 10_000
        const val PHASE_MS = 15_000L
        const val NS_PER_US = 1_000L
        const val JANK_US = 20_000L
        const val P95 = 0.95
        const val HALF = 128f

        // Layout, in dp and sp: big targets for a finger in a parked car, with room between them.
        const val PAD = 24
        const val GAP = 20
        const val BUTTON_HEIGHT = 80
        const val CLOSE_WIDTH = 240
        const val LABEL_WIDTH = 140
        const val SLIDER_HEIGHT = 64
        const val THUMB = 40
        const val TRACK = 8
        const val STATUS_SP = 24f
        const val BUTTON_SP = 22f
        const val LOG_SP = 16f
        const val THUMB_COLOR = 0xFF3D7BD9.toInt()
    }
}
