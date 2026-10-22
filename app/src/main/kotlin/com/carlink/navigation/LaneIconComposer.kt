package com.carlink.navigation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import androidx.car.app.model.CarIcon
import androidx.car.app.navigation.model.Maneuver
import androidx.core.graphics.drawable.IconCompat
import com.carlink.logging.Logger
import com.carlink.logging.logInfo
import com.carlink.logging.logWarn
import com.carlink.ui.settings.LaneIconMode
import com.carlink.ui.settings.LaneTestConfig
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws lane guidance into the turn icon GM's cluster and HUD already show (design "Lane layouts
 * inside the HUD turn icon", 2026-10-21). With Android Auto phones GM shows lanes in that same spot,
 * because Google Maps draws them into its turn image; this does the same for CarPlay routes.
 *
 * - [LaneIconMode.ARROW]: the turn arrow shrunk into the top of the square, the lane row beneath.
 * - [LaneIconMode.LANES]: the whole square is lanes; the lanes to be in are lit on a teal band.
 * - [lanesImage]: a separate lane strip for `Step.setLanesImage`, one cell per lane, which GM places itself.
 * - [LaneTestConfig.card]: a calibration card in place of the turn icon, to see how GM crops,
 *   scales and tints it.
 *
 * Everything is 512 px, like [com.carlink.navigation.compose.ComposedIconStore]'s icons, white and
 * grey on transparent. The last result is kept, so a step's distance ticks reuse one bitmap.
 */
internal object LaneIconComposer {
    private const val SIZE = 512

    // The lane strip for `Step.setLanesImage`: one cell per lane rather than a fixed 3:1 strip. GM
    // fits the picture into its own lane slot, so a mostly empty 512 px strip with two lanes in it came
    // out small on the cluster (truck, 2026-10-21). Only as wide as the lanes need, and the arrows fill
    // the height with a heavier stroke. The slot is 74 dp tall at most (the library's 500 x 74 dp
    // box, and the 6:1 the lane strip card found, truck 2026-10-22), so the picture's height cannot
    // grow on the HUD: no margin, the caps reach the edges, and a stroke and head as heavy as fit.
    private const val STRIP_CELL_W = 120
    private const val STRIP_HEIGHT = 160
    private const val STRIP_STROKE = 26f
    private const val STRIP_HEAD = 40f

    private const val LANE_ON = Color.WHITE
    private val LANE_OFF = Color.rgb(0x70, 0x78, 0x7A)
    private val DIVIDER = Color.rgb(0x4A, 0x52, 0x54)
    private val BAND = Color.rgb(0x14, 0x34, 0x3B)

    // ARROW layout: the arrow's box at the top, the lane row under it.
    private val ARROW_BOX = RectF(106f, 8f, 406f, 308f)
    private val ARROW_LANES = RectF(24f, 336f, 488f, 496f)
    private val ALL_LANES = RectF(16f, 40f, 496f, 472f)

    private const val MAX_STROKE = 18f
    private const val MIN_STROKE = 6f
    private const val STROKE_PER_LANE = 0.12f
    private const val GLYPH_HEIGHT_PER_LANE = 2.4f
    private const val HEAD_PER_LANE = 0.15f
    private const val MIN_HEAD = 8f
    private const val TURN_REACH = 0.3f
    private const val HEAD_SPREAD_DEG = 40.0
    private const val DIVIDER_WIDTH = 4f
    private val DIVIDER_DASH = floatArrayOf(14f, 12f)
    private const val BAND_INSET = 4f
    private const val BAND_RADIUS = 16f

    private const val STRAIGHT_MAX = 10
    private const val SLIGHT_MAX = 45
    private const val U_TURN_MIN = 175

    private data class Key(
        val base: Maneuver,
        val lanes: List<LaneHint>,
        val mode: LaneIconMode,
        val card: Boolean,
    )

    private val lock = Any()
    private var lastKey: Key? = null
    private var lastManeuver: Maneuver? = null
    private var lastStripLanes: List<LaneHint>? = null
    private var lastStrip: CarIcon? = null
    private val card: Bitmap by lazy { CalibrationCard.draw(SIZE) }

    /**
     * [base] with its icon redrawn for the lane tests, or [base] itself when they draw nothing:
     * the card when [LaneTestConfig.card] is on, else lanes per [LaneTestConfig.icon] when there
     * are [lanes]. Any failure falls back to [base]; the turn icon must never go missing.
     */
    fun maneuver(
        base: Maneuver,
        lanes: List<LaneHint>,
        tests: LaneTestConfig,
        context: Context,
    ): Maneuver {
        if (!tests.card && (tests.icon == LaneIconMode.OFF || lanes.isEmpty())) return base
        val key = Key(base, lanes, tests.icon, tests.card)
        synchronized(lock) {
            if (key == lastKey && lastKey?.base === base) lastManeuver?.let { return it }
        }
        val built =
            runCatching {
                val bitmap = if (tests.card) card else drawTurnIcon(base, lanes, tests.icon, context)
                base.withIcon(bitmap)
            }.onFailure {
                logWarn("[LANES] could not draw the lane icon, keeping the plain one: ${it.message}", tag = Logger.Tags.NAVI)
            }.getOrNull() ?: return base
        logInfo(
            "[LANES] turn icon: " + if (tests.card) "calibration card" else "${tests.icon.label}, ${describe(lanes)}",
            tag = Logger.Tags.NAVI,
        )
        synchronized(lock) {
            lastKey = key
            lastManeuver = built
        }
        return built
    }

    /** The lanes alone, one [STRIP_CELL_W] cell each, for `Step.setLanesImage`. */
    fun lanesImage(lanes: List<LaneHint>): CarIcon {
        synchronized(lock) {
            if (lanes == lastStripLanes) lastStrip?.let { return it }
        }
        val width = max(1, lanes.size) * STRIP_CELL_W
        val bmp = Bitmap.createBitmap(width, STRIP_HEIGHT, Bitmap.Config.ARGB_8888)
        val area = RectF(0f, 0f, width.toFloat(), STRIP_HEIGHT.toFloat())
        drawLanes(Canvas(bmp), lanes, area, band = false, bold = true)
        val icon = CarIcon.Builder(IconCompat.createWithBitmap(bmp)).build()
        synchronized(lock) {
            lastStripLanes = lanes
            lastStrip = icon
        }
        return icon
    }

    /** "4 lanes: 0 0 90*(+0) 90*" for the trail; * marks a lane to be in, (+…) its other directions. */
    fun describe(lanes: List<LaneHint>): String =
        "${lanes.size} lanes: " +
            lanes.joinToString(" ") { lane ->
                lane.angle.toString() + (if (lane.recommended) "*" else "") + if (lane.others.isEmpty()) "" else lane.others.joinToString(",", "(+", ")")
            }

    private fun drawTurnIcon(
        base: Maneuver,
        lanes: List<LaneHint>,
        mode: LaneIconMode,
        context: Context,
    ): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (mode == LaneIconMode.ARROW) {
            drawBaseIcon(canvas, base, context)
            drawLanes(canvas, lanes, ARROW_LANES, band = false)
        } else {
            drawLanes(canvas, lanes, ALL_LANES, band = true)
        }
        return bmp
    }

    /** The original turn icon (composed bitmap or static vector), fitted into [ARROW_BOX]. */
    private fun drawBaseIcon(
        canvas: Canvas,
        base: Maneuver,
        context: Context,
    ) {
        val drawable = base.icon?.icon?.loadDrawable(context) ?: return
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: SIZE
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: SIZE
        val scale = min(ARROW_BOX.width() / w, ARROW_BOX.height() / h)
        val dw = w * scale
        val dh = h * scale
        val left = ARROW_BOX.centerX() - dw / 2
        val top = ARROW_BOX.centerY() - dh / 2
        drawable.setBounds(left.toInt(), top.toInt(), (left + dw).toInt(), (top + dh).toInt())
        drawable.draw(canvas)
    }

    /**
     * A row of lane arrows inside [area]: dashed dividers, lit lanes to be in, the rest grey. A
     * lane's other directions are drawn grey under its main one, so a straight-or-right lane taken
     * right shows both arrows with the right one lit.
     */
    private fun drawLanes(
        canvas: Canvas,
        lanes: List<LaneHint>,
        area: RectF,
        band: Boolean,
        bold: Boolean = false,
    ) {
        if (lanes.isEmpty()) return
        val n = lanes.size
        val cw = area.width() / n
        // [bold]: the lane strip, whose arrows fill the cell height (round caps reach its edges).
        val stroke = if (bold) STRIP_STROKE else (cw * STROKE_PER_LANE).coerceIn(MIN_STROKE, MAX_STROKE)
        val glyphH = if (bold) area.height() - stroke else min(area.height() - 2 * stroke, cw * GLYPH_HEIGHT_PER_LANE)
        val head = if (bold) STRIP_HEAD else max(MIN_HEAD, cw * HEAD_PER_LANE)
        val glyphTop = area.centerY() - glyphH / 2
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BAND }
        if (band) {
            lanes.forEachIndexed { i, lane ->
                if (!lane.recommended) return@forEachIndexed
                val x = area.left + cw * i
                canvas.drawRoundRect(RectF(x + BAND_INSET, area.top, x + cw - BAND_INSET, area.bottom), BAND_RADIUS, BAND_RADIUS, fill)
            }
        }
        val divider =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = DIVIDER
                style = Paint.Style.STROKE
                strokeWidth = DIVIDER_WIDTH
                pathEffect = DashPathEffect(DIVIDER_DASH, 0f)
            }
        for (i in 1 until n) {
            val x = area.left + cw * i
            canvas.drawLine(x, area.top, x, area.bottom, divider)
        }
        val pen =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = stroke
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
        lanes.forEachIndexed { i, lane ->
            val cx = area.left + cw * (i + 0.5f)
            pen.color = LANE_OFF
            val rows = glyphTop..glyphTop + glyphH
            lane.others.forEach { canvas.drawPath(glyph(cx, rows, cw, it, head), pen) }
            pen.color = if (lane.recommended) LANE_ON else LANE_OFF
            canvas.drawPath(glyph(cx, rows, cw, lane.angle, head), pen)
        }
    }

    /**
     * One lane arrow for an Apple lane angle: 0 straight, +90 right, -90 left, ±180 back, drawn over
     * [rows] (top..bottom) in a lane [cw] wide, with an arrowhead [head] long.
     */
    private fun glyph(
        cx: Float,
        rows: ClosedFloatingPointRange<Float>,
        cw: Float,
        angle: Int,
        head: Float,
    ): Path {
        val top = rows.start
        val bot = rows.endInclusive
        val h = bot - top
        val reach = cw * TURN_REACH
        val s = if (angle < 0) -1f else 1f
        val a = abs(angle)
        val p = Path()
        p.moveTo(cx, bot)
        when {
            a <= STRAIGHT_MAX -> {
                p.lineTo(cx, top)
                arrowHead(p, PointF(cx, top), PointF(0f, -1f), head)
            }
            a <= SLIGHT_MAX -> {
                val midY = top + h * 0.45f
                p.lineTo(cx, midY)
                p.lineTo(cx + s * reach, top)
                arrowHead(p, PointF(cx + s * reach, top), PointF(s * reach, top - midY), head)
            }
            a < U_TURN_MIN -> {
                val endY = top + h * 0.2f
                p.lineTo(cx, top + h * 0.5f)
                p.quadTo(cx, endY, cx + s * reach, endY)
                arrowHead(p, PointF(cx + s * reach, endY), PointF(s, 0f), head)
            }
            else -> {
                val turnY = top + h * 0.15f
                val endY = top + h * 0.55f
                p.lineTo(cx, top + h * 0.35f)
                p.cubicTo(cx, turnY, cx + s * reach, turnY, cx + s * reach, top + h * 0.35f)
                p.lineTo(cx + s * reach, endY)
                arrowHead(p, PointF(cx + s * reach, endY), PointF(0f, 1f), head)
            }
        }
        return p
    }

    /** Two strokes back from [tip] at ±[HEAD_SPREAD_DEG] around the direction [dir] points. */
    private fun arrowHead(
        p: Path,
        tip: PointF,
        dir: PointF,
        size: Float,
    ) {
        val x = tip.x
        val y = tip.y
        val len = hypot(dir.x, dir.y).takeIf { it > 0f } ?: return
        val bx = -dir.x / len
        val by = -dir.y / len
        val rad = Math.toRadians(HEAD_SPREAD_DEG)
        val c = cos(rad).toFloat()
        val sn = sin(rad).toFloat()
        p.moveTo(x + (bx * c - by * sn) * size, y + (bx * sn + by * c) * size)
        p.lineTo(x, y)
        p.lineTo(x + (bx * c + by * sn) * size, y + (-bx * sn + by * c) * size)
    }

    /** A copy of this maneuver with a bitmap icon; the type and roundabout fields are kept. */
    private fun Maneuver.withIcon(bitmap: Bitmap): Maneuver {
        val b = Maneuver.Builder(type)
        if (roundaboutExitNumber > 0) b.setRoundaboutExitNumber(roundaboutExitNumber)
        if (roundaboutExitAngle > 0) b.setRoundaboutExitAngle(roundaboutExitAngle)
        b.setIcon(CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build())
        return b.build()
    }
}

/** Settings › Cluster › Turn icon calibration card, drawn once (see [LaneIconComposer]). Positions are for 512 px. */
private object CalibrationCard {
    /** White, greys, then the app's teal, amber, red and a green. */
    private val BARS =
        intArrayOf(
            Color.WHITE,
            Color.rgb(0x9E, 0x9E, 0x9E),
            Color.rgb(0x4A, 0x4A, 0x4A),
            Color.rgb(0x5F, 0xD5, 0xED),
            Color.rgb(0xFF, 0xB9, 0x51),
            Color.rgb(0xFF, 0x54, 0x49),
            Color.rgb(0x4C, 0xD9, 0x64),
        )

    /** A different colour in each corner, so a flip or rotation shows. */
    private val CORNERS =
        listOf(
            Triple(Color.rgb(0xFF, 0x54, 0x49), 16f to 16f, "TL"),
            Triple(Color.rgb(0x5F, 0xD5, 0xED), 448f to 16f, "TR"),
            Triple(Color.rgb(0xFF, 0xB9, 0x51), 16f to 448f, "BL"),
            Triple(Color.rgb(0x5B, 0x8C, 0xFF), 448f to 448f, "BR"),
        )

    /**
     * The calibration card: white edge and ticks (cropping), a 64 px grid (scaling), four coloured
     * corners (flips) and colour bars (does GM keep colour or tint everything one shade).
     */
    fun draw(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(Color.rgb(0x1A, 0x22, 0x24))
        paint.style = Paint.Style.STROKE
        paint.color = Color.rgb(0x3A, 0x42, 0x43)
        paint.strokeWidth = 2f
        for (g in 64 until size step 64) {
            c.drawLine(g.toFloat(), 0f, g.toFloat(), size.toFloat(), paint)
            c.drawLine(0f, g.toFloat(), size.toFloat(), g.toFloat(), paint)
        }
        paint.color = Color.WHITE
        paint.strokeWidth = 3f
        for (x in 32 until size step 32) {
            val len = if (x % 64 == 0) 36f else 22f
            val v = x.toFloat()
            c.drawLine(v, 8f, v, len, paint)
            c.drawLine(v, size - 8f, v, size - len, paint)
            c.drawLine(8f, v, len, v, paint)
            c.drawLine(size - 8f, v, size - len, v, paint)
        }
        paint.strokeWidth = 4f
        c.drawLine(256f, 216f, 256f, 296f, paint)
        c.drawLine(216f, 256f, 296f, 256f, paint)
        paint.strokeWidth = 8f
        c.drawRect(4f, 4f, size - 4f, size - 4f, paint)

        paint.style = Paint.Style.FILL
        BARS.forEachIndexed { i, color ->
            paint.color = color
            c.drawRect(32f + i * 64, 336f, 96f + i * 64, 408f, paint)
        }
        drawLabels(c, paint)
        return bmp
    }

    private fun drawLabels(
        c: Canvas,
        paint: Paint,
    ) {
        val text =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
        CORNERS.forEach { (color, at, label) ->
            paint.color = color
            c.drawRect(at.first, at.second, at.first + 48, at.second + 48, paint)
            text.color = Color.BLACK
            text.textSize = 20f
            c.drawText(label, at.first + 24, at.second + 32, text)
        }
        text.color = Color.WHITE
        text.textSize = 48f
        c.drawText("CARLINK3", 256f, 150f, text)
        text.color = Color.rgb(0x9E, 0x9E, 0x9E)
        text.textSize = 28f
        c.drawText("512 x 512", 256f, 196f, text)
        text.color = Color.WHITE
        c.drawText("L", 120f, 452f, text)
        c.drawText("R", 392f, 452f, text)
    }
}
