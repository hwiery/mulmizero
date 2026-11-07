package com.mulmizero.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.mulmizero.app.core.Direction
import com.mulmizero.app.core.EventType
import com.mulmizero.app.core.RiskComponent
import com.mulmizero.app.core.RiskLevel
import com.mulmizero.app.core.TimelinePoint
import com.mulmizero.app.core.TurnKind
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object Palette {
    val bg = Color.parseColor("#0B0F17")
    val card = Color.parseColor("#161C27")
    val card2 = Color.parseColor("#1E2633")
    val track = Color.parseColor("#2A3342")
    val text = Color.parseColor("#F2F5FA")
    val sub = Color.parseColor("#9AA6B8")
    val accent = Color.parseColor("#2F6BFF")
    val amber = Color.parseColor("#F5A524")
    val green = Color.parseColor("#2BD17E")
    val red = Color.parseColor("#FF5A5F")
    val purple = Color.parseColor("#A97BFF")
    val cyan = Color.parseColor("#3CC8E8")

    fun level(l: RiskLevel) = when (l) {
        RiskLevel.CALM -> green
        RiskLevel.WATCH -> cyan
        RiskLevel.CARE -> amber
        RiskLevel.MAX -> red
    }

    fun event(t: EventType) = when (t) {
        EventType.TURN -> cyan
        EventType.CARE, EventType.CLIMATE -> amber
        EventType.COACH -> purple
        EventType.SICK -> red
        EventType.ROUTE -> green
        EventType.SDK -> accent
        EventType.ASK -> Color.parseColor("#FFD866")
        EventType.END -> green
        else -> sub
    }
}

private fun Context.dp(v: Float) = v * resources.displayMetrics.density

/** Ring gauge 0..100 with a threshold tick (mockup screen ①). */
class GaugeView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var score = 0.0; set(v) { field = v; invalidate() }
    var threshold = 50; set(v) { field = v; invalidate() }
    var level = RiskLevel.CALM; set(v) { field = v; invalidate() }

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.text; strokeCap = Paint.Cap.ROUND }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.text; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.sub; textAlign = Paint.Align.CENTER }

    override fun onDraw(c: Canvas) {
        val size = min(width, height).toFloat()
        val stroke = size * 0.085f
        val r = size / 2 - stroke
        val cx = width / 2f; val cy = height / 2f
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        ring.strokeWidth = stroke
        ring.color = Palette.track
        c.drawArc(oval, 135f, 270f, false, ring)
        ring.color = Palette.level(level)
        c.drawArc(oval, 135f, (270f * (score / 100.0).coerceIn(0.0, 1.0)).toFloat(), false, ring)
        // threshold tick
        val a = Math.toRadians(135.0 + 270.0 * threshold / 100.0)
        tick.strokeWidth = stroke * 0.18f
        val r1 = r - stroke * 0.75f; val r2 = r + stroke * 0.75f
        c.drawLine(
            (cx + r1 * Math.cos(a)).toFloat(), (cy + r1 * Math.sin(a)).toFloat(),
            (cx + r2 * Math.cos(a)).toFloat(), (cy + r2 * Math.sin(a)).toFloat(), tick,
        )
        big.textSize = size * 0.30f
        c.drawText(score.roundToInt().toString(), cx, cy + size * 0.08f, big)
        small.textSize = size * 0.075f
        c.drawText("멀미 위험 지수", cx, cy + size * 0.21f, small)
        small.color = Palette.level(level)
        small.textSize = size * 0.065f
        c.drawText(level.label + " · 기준 $threshold", cx, cy + size * 0.44f, small)
        small.color = Palette.sub
    }
}

/** Score over time with threshold line and event markers. */
class TimelineView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var points: List<TimelinePoint> = emptyList(); set(v) { field = v; invalidate() }
    var windowSize = 135

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Palette.amber; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.argb(50, 245, 165, 36) }
    private val thr = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Palette.red }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.track }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.sub }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val pad = context.dp(10f)
        val left = context.dp(44f); val right = width - pad; val top = pad; val bottom = height - context.dp(28f)
        label.textSize = context.dp(17f)
        for (v in listOf(0, 50, 100)) {
            val y = bottom - (bottom - top) * v / 100f
            c.drawLine(left, y, right, y, grid)
            c.drawText("$v", pad, y + context.dp(6f), label)
        }
        val n = max(windowSize, points.size)
        fun x(i: Int) = left + (right - left) * i / (n - 1).toFloat()
        fun y(s: Double) = (bottom - (bottom - top) * (s / 100.0).coerceIn(0.0, 1.0)).toFloat()
        if (points.size >= 2) {
            // threshold (personalised threshold can change during the trip)
            thr.strokeWidth = context.dp(2f)
            thr.pathEffect = DashPathEffect(floatArrayOf(context.dp(8f), context.dp(6f)), 0f)
            val tp = Path()
            points.forEachIndexed { i, p -> if (i == 0) tp.moveTo(x(i), y(p.threshold.toDouble())) else tp.lineTo(x(i), y(p.threshold.toDouble())) }
            c.drawPath(tp, thr)

            val path = Path(); val area = Path()
            points.forEachIndexed { i, p ->
                if (i == 0) { path.moveTo(x(i), y(p.score)); area.moveTo(x(i), bottom); area.lineTo(x(i), y(p.score)) }
                else { path.lineTo(x(i), y(p.score)); area.lineTo(x(i), y(p.score)) }
            }
            area.lineTo(x(points.size - 1), bottom); area.close()
            c.drawPath(area, fill)
            line.strokeWidth = context.dp(4f)
            c.drawPath(path, line)
            points.forEachIndexed { i, p ->
                val m = p.marker ?: return@forEachIndexed
                dot.color = Palette.event(m)
                c.drawCircle(x(i), y(p.score), context.dp(7f), dot)
                dot.color = Palette.card
                c.drawCircle(x(i), y(p.score), context.dp(3f), dot)
            }
        }
        label.textSize = context.dp(16f)
        c.drawText("초 →  (점선: 개인 케어 기준)", left, height - context.dp(6f), label)
    }
}

/** Horizontal bars for each risk component (why the score is what it is). */
class FactorBarsView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var components: List<RiskComponent> = emptyList(); set(v) { field = v; invalidate() }
    var multiplierText = ""; set(v) { field = v; invalidate() }

    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.text }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.sub; textAlign = Paint.Align.RIGHT }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        if (components.isEmpty()) return
        val rows = components.size + 1
        val rowH = height / rows.toFloat()
        val labelW = width * 0.40f
        val valueW = width * 0.15f
        txt.textSize = min(rowH * 0.56f, context.dp(22f))
        sub.textSize = txt.textSize * 0.9f
        val barH = rowH * 0.30f
        components.forEachIndexed { i, comp ->
            val cy = rowH * i + rowH / 2
            c.drawText(comp.label, 0f, cy + txt.textSize * 0.35f, txt)
            val x0 = labelW; val x1 = width - valueW
            bar.color = Palette.track
            c.drawRoundRect(RectF(x0, cy - barH / 2, x1, cy + barH / 2), barH / 2, barH / 2, bar)
            val frac = (comp.points / comp.maxPoints).coerceIn(0.0, 1.0).toFloat()
            bar.color = when {
                frac > 0.66 -> Palette.amber
                frac > 0.33 -> Palette.cyan
                else -> Palette.green
            }
            if (frac > 0.01f) c.drawRoundRect(RectF(x0, cy - barH / 2, x0 + (x1 - x0) * frac, cy + barH / 2), barH / 2, barH / 2, bar)
            c.drawText("%.0f/%.0f".format(comp.points, comp.maxPoints), width.toFloat(), cy + txt.textSize * 0.35f, sub)
        }
        val cy = rowH * components.size + rowH / 2
        sub.textAlign = Paint.Align.LEFT
        c.drawText(multiplierText, 0f, cy + txt.textSize * 0.35f, sub)
        sub.textAlign = Paint.Align.RIGHT
    }
}

/** Big arrow for the next manoeuvre (mockup screen ②). */
class TurnArrowView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var kind: TurnKind? = null; set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    override fun onDraw(c: Canvas) {
        val k = kind ?: return
        val s = min(width, height).toFloat()
        p.strokeWidth = s * 0.09f
        p.color = if (k.severity >= 1.0) Palette.amber else if (k.isMajor) Palette.cyan else Palette.sub
        val cx = width / 2f; val bottom = height * 0.88f; val top = height * 0.15f
        val path = Path()
        val dirSign = when (k.direction) { Direction.LEFT -> -1f; Direction.RIGHT -> 1f; Direction.NONE -> 0f }
        path.moveTo(cx, bottom)
        if (dirSign == 0f) {
            path.lineTo(cx, top)
            c.drawPath(path, p)
            arrowHead(c, cx, top, 0f, -1f, s)
            return
        }
        val bend = if (k.severity >= 1.0) 0.5f else 0.35f
        val midY = top + (bottom - top) * bend
        path.lineTo(cx, midY)
        val endX = cx + dirSign * s * 0.36f
        val endY = if (k.severity >= 1.0) midY + s * 0.12f else midY
        path.lineTo(endX, endY)
        c.drawPath(path, p)
        val dx = endX - cx; val dy = endY - midY
        val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        arrowHead(c, endX, endY, dx / len, dy / len, s)
    }

    private fun arrowHead(c: Canvas, x: Float, y: Float, ux: Float, uy: Float, s: Float) {
        val h = s * 0.16f
        val px = -uy; val py = ux
        val path = Path()
        path.moveTo(x - ux * h + px * h * 0.7f, y - uy * h + py * h * 0.7f)
        path.lineTo(x + ux * h * 0.2f, y + uy * h * 0.2f)
        path.lineTo(x - ux * h - px * h * 0.7f, y - uy * h - py * h * 0.7f)
        c.drawPath(path, p)
    }
}
