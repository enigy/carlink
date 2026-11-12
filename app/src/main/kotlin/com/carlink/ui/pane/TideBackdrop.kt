package com.carlink.ui.pane

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.carlink.car.DrivingState
import com.carlink.car.VehicleReadings
import com.carlink.fuel.LowFuel
import com.carlink.ui.settings.VehicleStore
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * "Drift + Tide" (owner, 12 Nov, the pane backgrounds canvas), behind the Fuel card and the clock while nothing plays
 * ([PaneBackdrop.TIDE]): Drift's slow colour fields fill water whose line is the tank's level ([TideMath.waterline]), and
 * above it the pane's own day or night colour carries a faint drift of the same colours. Low fuel ([LowFuel]) turns the
 * water amber. The clock's water is a low swell under its speed and temperature; an unknown level draws none. The line
 * glides to a new level or card. Fields and waves move only parked, with the app in front and Android's animations on:
 * moving, the scene holds still, nothing drifting at the edge of the driver's eye.
 */
@Composable
internal fun TideBackdrop(
    fuel: Boolean,
    night: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val vehicle by VehicleReadings.state.collectAsState()
    val parked by DrivingState.parked.collectAsState()
    val lowAt by remember(context) { VehicleStore.get(context) }.lowFuelAt.collectAsState()
    val low = fuel && LowFuel.isLow(vehicle, lowAt, vehicle.distanceImperial(Locale.getDefault()))
    val line by animateFloatAsState(TideMath.waterline(fuel, vehicle.fuelFraction()), tween(LINE_MS), label = "waterline")
    var resumed by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        resumed = true
        onPauseOrDispose { resumed = false }
    }
    // The scene's own clock: it runs only while the scene may move, so it stops and starts where it was.
    var elapsedMs by remember { mutableLongStateOf(0L) }
    val running = parked && resumed && ValueAnimator.areAnimatorsEnabled()
    LaunchedEffect(running) {
        var last = SystemClock.uptimeMillis()
        while (running) {
            delay(FRAME_MS)
            val now = SystemClock.uptimeMillis()
            elapsedMs += now - last
            last = now
        }
    }
    val palette = TidePalette.of(night, low)
    val water = remember { Path() }
    val back = remember { Path() }
    val crest = remember { Path() }
    Canvas(modifier = modifier) {
        val t = elapsedMs
        val u = size.width / TideMath.UNITS
        ABOVE.forEachIndexed { i, f -> drawField(f, palette.above[i], 0f, u, t) }
        val top = size.height * line - CREST * u
        if (top >= size.height) return@Canvas
        wave(back, top - BACK_RISE * u, TideMath.shift(t, BACK_MS, reverse = true) * size.width, u, closed = true)
        drawPath(back, palette.backFill)
        wave(water, top, TideMath.shift(t, FRONT_MS, reverse = false) * size.width, u, closed = true)
        clipPath(water) {
            drawRect(Brush.verticalGradient(listOf(palette.tintTop, palette.tintBottom), startY = top, endY = size.height))
            IN_WATER.forEachIndexed { i, f -> drawField(f, palette.water[i], top, u, t) }
        }
        wave(crest, top, TideMath.shift(t, FRONT_MS, reverse = false) * size.width, u, closed = false)
        drawPath(crest, palette.crest, style = Stroke(width = 2.dp.toPx()))
    }
}

/** The scene's sums, in plain Kotlin so they are tested without a screen. Lengths are in the canvas's units: its pane was 576 wide. */
internal object TideMath {
    const val UNITS = 576f

    /** The clock's swell: the canvas's 600 of 768, just above its speed and temperature. */
    const val SWELL = 0.78f

    /** No water: the line below the pane. */
    const val NONE = 1.08f

    /** The water's line, as a fraction of the pane's height from the top: the tank's [level] on the fuel pane (none when unknown), the swell on the clock's. */
    fun waterline(
        fuel: Boolean,
        level: Float?,
    ): Float =
        when {
            !fuel -> SWELL
            level == null -> NONE
            else -> 1f - level.coerceIn(0f, 1f)
        }

    /** How far a field has drifted at [t]: 0 at rest to 1 at its far point over [periodMs], eased, and back; [reverse] starts at the far point. */
    fun pingPong(
        t: Long,
        periodMs: Long,
        reverse: Boolean = false,
    ): Float {
        val phase = (t % (2 * periodMs)).toFloat() / periodMs
        val p = if (phase <= 1f) phase else 2f - phase
        val eased = ((1 - cos(PI * p)) / 2).toFloat()
        return if (reverse) 1f - eased else eased
    }

    /** A wave's slide at [t], in pane widths: 0 to -1 over [periodMs], or -1 to 0 when [reverse]; one period is a pane wide, so it loops without a seam. */
    fun shift(
        t: Long,
        periodMs: Long,
        reverse: Boolean,
    ): Float {
        val p = (t % periodMs).toFloat() / periodMs
        return if (reverse) p - 1f else -p
    }
}

/** Drift + Tide's colours by day or night, the water amber when the fuel is low: the canvas's values. */
internal class TidePalette(
    val above: List<Color>,
    val water: List<Color>,
    val tintTop: Color,
    val tintBottom: Color,
    val backFill: Color,
    val crest: Color,
) {
    companion object {
        fun of(
            night: Boolean,
            low: Boolean,
        ): TidePalette =
            when {
                night && low -> NIGHT_LOW
                night -> NIGHT
                low -> DAY_LOW
                else -> DAY
            }

        private val DAY_ABOVE = listOf(rgba(165, 208, 255, 0.32f), rgba(255, 207, 184, 0.30f))
        private val NIGHT_ABOVE = listOf(rgba(20, 90, 112, 0.28f), rgba(43, 47, 122, 0.28f))
        private val DAY =
            TidePalette(
                DAY_ABOVE,
                listOf(rgba(140, 196, 255, 0.75f), rgba(200, 186, 255, 0.70f), rgba(160, 226, 206, 0.62f), rgba(255, 196, 170, 0.62f)),
                rgba(190, 222, 255, 0.55f),
                rgba(214, 204, 255, 0.45f),
                rgba(170, 210, 250, 0.30f),
                rgba(70, 130, 200, 0.45f),
            )
        private val NIGHT =
            TidePalette(
                NIGHT_ABOVE,
                listOf(rgba(20, 110, 135, 0.70f), rgba(52, 58, 150, 0.65f), rgba(16, 96, 86, 0.55f), rgba(110, 52, 120, 0.55f)),
                rgba(16, 70, 90, 0.55f),
                rgba(30, 34, 90, 0.55f),
                rgba(30, 90, 120, 0.40f),
                rgba(120, 200, 230, 0.40f),
            )
        private val DAY_LOW =
            TidePalette(
                DAY_ABOVE,
                listOf(rgba(255, 190, 140, 0.75f), rgba(255, 214, 150, 0.70f), rgba(255, 176, 170, 0.60f), rgba(255, 200, 120, 0.60f)),
                rgba(255, 214, 170, 0.55f),
                rgba(255, 190, 150, 0.45f),
                rgba(255, 205, 150, 0.35f),
                rgba(200, 120, 40, 0.55f),
            )
        private val NIGHT_LOW =
            TidePalette(
                NIGHT_ABOVE,
                listOf(rgba(140, 80, 20, 0.65f), rgba(120, 50, 40, 0.55f), rgba(150, 100, 30, 0.50f), rgba(110, 60, 20, 0.55f)),
                rgba(90, 55, 15, 0.60f),
                rgba(70, 35, 25, 0.60f),
                rgba(110, 70, 20, 0.45f),
                rgba(255, 180, 90, 0.45f),
            )
    }
}

/** How a field drifts (the canvas's three): from a still offset and size to its far point and back. */
private enum class Drift(
    val dx: Float,
    val dy: Float,
    val from: Float,
    val to: Float,
) {
    A(150f, 110f, 1f, 1.18f),
    B(-140f, -90f, 1.08f, 0.94f),
    C(110f, -130f, 0.96f, 1.12f),
}

/** One colour field at rest: its centre and its size, in the canvas's units, how it drifts, and how long one way takes. */
private class Field(
    val cx: Float,
    val cy: Float,
    val width: Float,
    val drift: Drift,
    val periodMs: Long,
    val reverse: Boolean = false,
)

// The canvas's fields: two faint ones over the pane, four in the water (from the water's top).
private val ABOVE = listOf(Field(130f, 150f, 540f, Drift.A, 38_000), Field(480f, 400f, 520f, Drift.B, 46_000))
private val IN_WATER =
    listOf(
        Field(110f, 240f, 460f, Drift.A, 40_000),
        Field(470f, 330f, 480f, Drift.B, 48_000),
        Field(230f, 470f, 420f, Drift.C, 52_000),
        Field(500f, 560f, 400f, Drift.A, 56_000, reverse = true),
    )

/** A field drawn at [t]: a soft circle of [color] fading out to its edge, [originY] down the pane. */
private fun DrawScope.drawField(
    f: Field,
    color: Color,
    originY: Float,
    u: Float,
    t: Long,
) {
    val e = TideMath.pingPong(t, f.periodMs, f.reverse)
    val d = f.drift
    val radius = f.width * FIELD_REACH * (d.from + (d.to - d.from) * e) * u
    val center = Offset((f.cx + d.dx * e) * u, originY + (f.cy + d.dy * e) * u)
    drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center = center, radius = radius), radius = radius, center = center)
}

/**
 * The canvas's wave into [path]: crests and troughs [CREST] either side of a line [CREST] below [top], one period a
 * pane wide, drawn twice from [x0] so a slide of one period loops without a seam; [closed] fills down to the bottom.
 */
private fun DrawScope.wave(
    path: Path,
    top: Float,
    x0: Float,
    u: Float,
    closed: Boolean,
) {
    val mid = top + CREST * u
    val trough = top + 2 * CREST * u
    path.reset()
    path.moveTo(x0, mid)
    for (k in 0 until 2) {
        val x = x0 + k * TideMath.UNITS * u
        path.cubicTo(x + 96 * u, top, x + 192 * u, top, x + 288 * u, mid)
        path.cubicTo(x + 384 * u, trough, x + 480 * u, trough, x + 576 * u, mid)
    }
    if (!closed) return
    path.lineTo(x0 + 2 * TideMath.UNITS * u, size.height)
    path.lineTo(x0, size.height)
    path.close()
}

private fun rgba(
    r: Int,
    g: Int,
    b: Int,
    a: Float,
) = Color(r, g, b, (a * 255).roundToInt())

// The canvas's timings and sizes.
private const val FRAME_MS = 42L
private const val LINE_MS = 1_500
private const val FRONT_MS = 18_000L
private const val BACK_MS = 26_000L
private const val CREST = 20f
private const val BACK_RISE = 12f
private const val FIELD_REACH = 0.48f
