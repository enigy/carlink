package com.enigy.carprobe

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
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
 */
class ProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var video: TextureView
    private var player: MediaPlayer? = null

    // Calibration by eye: contrast and per-channel gain, applied to the test video as a colour matrix.
    private var contrast = 1f
    private val gains = floatArrayOf(1f, 1f, 1f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 20f }
        logView = TextView(this).apply { textSize = 14f }
        video = TextureView(this)
        val row1 =
            row(
                button("Start logging") { startLogging() },
                button("Stop") { stopService(Intent(this, ProbeService::class.java)).also { say("logging stopped") } },
                button("Snapshot now") { thread { Snapshots.all(this, null) }.also { say("snapshot taken (no car service in the activity)") } },
                button("Export logs") { thread { val r = ProbeLog.export(this); main.post { say(r) } } },
            )
        val row2 =
            row(
                button("Video test (parked)") { videoTest() },
                button("Stop video") { stopVideo("stopped by hand") },
                button("Save calibration") { ProbeLog.log("display", "what" to "calibration chosen", "contrast" to contrast, "gains" to gains.toList()).also { say("calibration saved to the log") } },
            )
        val sliders =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(slider("Contrast") { contrast = it; applyFilter() })
                addView(slider("Red") { gains[0] = it; applyFilter() })
                addView(slider("Green") { gains[1] = it; applyFilter() })
                addView(slider("Blue") { gains[2] = it; applyFilter() })
            }
        val left =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(32, 32, 32, 32)
                addView(status)
                addView(row1)
                addView(row2)
                addView(sliders)
                addView(ScrollView(context).apply { addView(logView) }, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(left, LinearLayout.LayoutParams(0, -1, 1f))
                addView(FrameLayout(context).apply { addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER)) }, LinearLayout.LayoutParams(0, -1, 1f))
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
        say("logging; leave it running for the drive, then Stop and Export")
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

    /**
     * Item 5 and 7: fetch an HLS playlist (internet?), play it into a TextureView (start time, size, errors), count
     * decoded frames and UI frame times for [PHASE_MS] plain, then [PHASE_MS] with a colour matrix over the video.
     */
    private fun videoTest() {
        stopVideo("restart")
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
            if (fetch.isSuccess) main.post { play() } else main.post { say("no internet for the stream: ${fetch.exceptionOrNull()?.message}") }
        }
    }

    private fun play() {
        val st = video.surfaceTexture ?: return say("video surface not ready, try again")
        val frames = FrameCounter()
        video.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = Unit

                override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) = Unit

                override fun onSurfaceTextureDestroyed(s: SurfaceTexture) = true

                override fun onSurfaceTextureUpdated(s: SurfaceTexture) {
                    frames.decoded++
                }
            }
        val started = System.nanoTime()
        val mp = MediaPlayer()
        player = mp
        mp.setSurface(Surface(st))
        mp.setOnPreparedListener {
            ProbeLog.log("video", "what" to "prepared", "ms" to ms(started), "size" to "${it.videoWidth}x${it.videoHeight}")
            it.start()
        }
        mp.setOnInfoListener { _, what, extra ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                ProbeLog.log("video", "what" to "first frame", "ms" to ms(started))
                main.post { measure(frames) }
            } else {
                ProbeLog.log("video", "what" to "info", "code" to what, "extra" to extra)
            }
            false
        }
        mp.setOnVideoSizeChangedListener { _, w, h -> ProbeLog.log("video", "what" to "video size", "size" to "${w}x$h") }
        mp.setOnErrorListener { _, what, extra ->
            ProbeLog.log("video", "what" to "error", "code" to what, "extra" to extra, "ms" to ms(started))
            main.post { say("video error $what/$extra") }
            true
        }
        runCatching {
            mp.setDataSource(HLS_URL)
            mp.prepareAsync()
        }.onFailure { ProbeLog.log("video", "what" to "setDataSource", "error" to it) }
        say("video test: starting the stream")
    }

    /** Plain for PHASE_MS, then with the colour matrix for PHASE_MS; logs decoded fps and UI frame times for each. */
    private fun measure(frames: FrameCounter) {
        frames.phase("plain")
        main.postDelayed({
            frames.report()
            setFilter(ColorMatrix(floatArrayOf(1.1f, 0f, 0f, 0f, -10f, 0f, 1.0f, 0f, 0f, -10f, 0f, 0f, 0.9f, 0f, -10f, 0f, 0f, 0f, 1f, 0f)))
            frames.phase("colour matrix")
            main.postDelayed({
                frames.report()
                applyFilter()
                say("video test done; see the log")
            }, PHASE_MS)
        }, PHASE_MS)
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

    private fun stopVideo(why: String) {
        player?.let {
            runCatching { it.stop() }
            it.release()
            ProbeLog.log("video", "what" to "stopped", "why" to why)
        }
        player = null
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
            addView(TextView(context).apply { text = label; textSize = 18f; width = 200 })
            addView(
                SeekBar(context).apply {
                    max = 100
                    progress = 50
                    setOnSeekBarChangeListener(
                        object : SeekBar.OnSeekBarChangeListener {
                            // 0..100 -> 0.5..1.5
                            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) = onValue(0.5f + p / 100f)

                            override fun onStartTrackingTouch(s: SeekBar) = Unit

                            override fun onStopTrackingTouch(s: SeekBar) = Unit
                        },
                    )
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
        }

    private fun row(vararg views: View) =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEach { addView(it) }
        }

    private fun button(
        label: String,
        onClick: () -> Unit,
    ) = Button(this).apply {
        text = label
        textSize = 18f
        setOnClickListener { onClick() }
    }

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
    }
}
