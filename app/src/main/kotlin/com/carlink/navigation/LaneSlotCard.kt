package com.carlink.navigation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat

/**
 * Settings › Cluster › Lane strip calibration card: test pictures for GM's lane slot, where the
 * cluster and HUD show `Step.setLanesImage`. GM fits the picture into that slot itself, so how
 * large the lanes can get depends on the slot's shape, and nothing tells Carlink3 what it is (the
 * one-cell-per-lane strip of v19 came out only a little larger, truck 2026-10-22).
 *
 * The card steps through [SHAPES], all [HEIGHT] px tall, one every [FRAME_MS]: a filled box with a
 * white edge, a dashed line at every square and its shape in large type. While a picture is
 * narrower than the slot, GM sizes it by height and the label stays the same size; once it is
 * wider, GM sizes it by width and the label shrinks. The widest shape whose label is still full
 * size is the slot's. An edge cut off means GM crops instead of fitting.
 */
internal object LaneSlotCard {
    /** Width over height of each picture, in the order shown. */
    private val SHAPES = intArrayOf(1, 2, 3, 4, 5, 6, 8)
    private const val HEIGHT = 160
    const val FRAME_MS = 4_000L

    private val FILL = Color.rgb(0x1E, 0x6F, 0x7F)
    private const val EDGE = 8f
    private const val DIVIDER_WIDTH = 4f
    private val DIVIDER_DASH = floatArrayOf(12f, 10f)
    private const val LABEL_SIZE = 76f
    private const val SIZE_TEXT = 26f
    private const val LABEL_PAD = 10f

    /** A step with a lane image must carry lanes: three straight ones, the middle one to be in. */
    val LANES = listOf(LaneHint(0, false), LaneHint(0, true), LaneHint(0, false))

    private val images = arrayOfNulls<CarIcon>(SHAPES.size)

    /** Which picture shows at [nowMs] (elapsed realtime): each in turn, [FRAME_MS] apiece. */
    fun frameAt(nowMs: Long): Int = ((nowMs / FRAME_MS) % SHAPES.size).toInt()

    /** "4:1, 640 x 160 px", for the trail. */
    fun label(frame: Int): String = "${SHAPES[frame]}:1, ${SHAPES[frame] * HEIGHT} x $HEIGHT px"

    fun image(frame: Int): CarIcon =
        synchronized(images) {
            images[frame] ?: CarIcon.Builder(IconCompat.createWithBitmap(draw(SHAPES[frame]))).build().also { images[frame] = it }
        }

    private fun draw(shape: Int): Bitmap {
        val w = shape * HEIGHT
        val h = HEIGHT.toFloat()
        val bmp = Bitmap.createBitmap(w, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(FILL)
        val pen =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Color.WHITE
                strokeWidth = DIVIDER_WIDTH
                pathEffect = DashPathEffect(DIVIDER_DASH, 0f)
            }
        for (i in 1 until shape) {
            val x = (i * HEIGHT).toFloat()
            c.drawLine(x, EDGE, x, h - EDGE, pen)
        }
        pen.pathEffect = null
        pen.strokeWidth = EDGE
        c.drawRect(EDGE / 2, EDGE / 2, w - EDGE / 2, h - EDGE / 2, pen)

        val text =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
                color = Color.WHITE
                textSize = LABEL_SIZE
            }
        val label = "$shape:1"
        val half = text.measureText(label) / 2 + LABEL_PAD
        val cx = w / 2f
        val box = Paint().apply { color = FILL }
        c.drawRect(cx - half, EDGE, cx + half, h - EDGE, box)
        c.drawText(label, cx, h * 0.56f, text)
        text.typeface = Typeface.DEFAULT
        text.textSize = SIZE_TEXT
        c.drawText("$w x $HEIGHT", cx, h * 0.82f, text)
        return bmp
    }
}
