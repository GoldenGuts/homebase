package in_.weenja.hawidgets.widgets

import android.content.Context
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import in_.weenja.hawidgets.Cfg
import in_.weenja.hawidgets.R
import in_.weenja.hawidgets.ha.Snapshot
import in_.weenja.hawidgets.ui.Align
import in_.weenja.hawidgets.ui.Painter
import in_.weenja.hawidgets.ui.Palette
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Sky view's sun arc as a widget: the card itself is the sky (night / golden hour / day gradient),
 * the sun (or moon) travels the arc between sunrise and sunset, stars at night.
 */
class SkyWidget : CardWidget() {
    override val freshness = false
    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val spots = ArrayList<Hotspot>()
        val cfg = a.cfg
        val sun = snap[cfg["sun"]]
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val nextRise = sun?.str("next_rising")?.let { try { Instant.parse(it) } catch (e: Exception) { null } }
        val nextSet = sun?.str("next_setting")?.let { try { Instant.parse(it) } catch (e: Exception) { null } }
        val el = sun?.num("elevation") ?: 0.0
        val day = sun?.state == "above_horizon"
        // today's rise/set: when the sun is up, sunrise was < 24 h before the next one
        val rise = if (day && nextRise != null) nextRise.minus(Duration.ofDays(1)) else nextRise
        val set = if (day || nextSet == null || nextRise == null || nextSet.isBefore(nextRise)) nextSet else nextSet.minus(Duration.ofDays(1))
        val progress: Float = when {
            day && rise != null && set != null -> ((now.epochSecond - rise.epochSecond).toFloat() / max(1L, set.epochSecond - rise.epochSecond)).coerceIn(0f, 1f)
            !day && set != null && rise != null -> ((now.epochSecond - set.epochSecond).toFloat() / max(1L, rise.epochSecond - set.epochSecond)).coerceIn(0f, 1f)
            else -> 0.5f
        }
        val golden = day && el <= 12
        val grad = when { !day -> intArrayOf(0xff0b1026.toInt(), 0xff1b2350.toInt(), 0xff3a3f7a.toInt())
            golden -> intArrayOf(0xff7b61ff.toInt(), 0xffff7a59.toInt(), 0xffffc36b.toInt())
            else -> intArrayOf(0xff3fa9ff.toInt(), 0xff8fd3ff.toInt(), 0xffdff3ff.toInt()) }
        val ink = if (day && !golden) 0xff0b2540.toInt() else Color.WHITE
        val sub = Palette.alpha(ink, .72f)

        // sky
        val full = RectF(0f, 0f, p.w, p.h)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, p.h, grad, floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        p.canvas.drawRoundRect(full, 22f, 22f, paint)
        if (!day) {
            val rnd = java.util.Random(7)
            repeat((p.w * p.h / 900f).toInt().coerceIn(20, 90)) {
                val sx = rnd.nextFloat() * p.w; val sy = rnd.nextFloat() * p.h * .7f
                p.circle(sx, sy, .6f + rnd.nextFloat() * 1.1f, Color.argb((90 + rnd.nextInt(140)), 255, 255, 255))
            }
        }

        // header
        val w = Find.weather(cfg, snap)
        val cond = w?.state ?: ""
        val temp = w?.num("temperature")
        val padX = 20f
        val fmt = DateTimeFormatter.ofPattern("HH:mm")
        p.text(if (!day) "Night" else if (golden) "Golden hour" else "Daylight", padX, 28f, 14f, 700, ink)
        val right = listOfNotNull(temp?.let { "${it.roundToInt()}°" }, cond.takeIf { it.isNotEmpty() }?.let { Wx.label(it) }).joinToString(" · ")
        p.text(right, p.w - padX, 28f, 12f, 500, sub, Align.RIGHT)

        // arc: a half-ellipse from the left horizon to the right horizon
        val hy = p.h - 34f
        val ax = padX + 14f; val bx = p.w - padX - 14f
        val cx = (ax + bx) / 2; val rx = (bx - ax) / 2
        val ry = (hy - 52f).coerceAtLeast(30f)
        val arc = Path()
        val steps = 60
        for (i in 0..steps) {
            val t = i / steps.toFloat(); val ang = PI * (1 - t)
            val x = cx + rx * cos(ang).toFloat(); val y = hy - ry * sin(ang).toFloat()
            if (i == 0) arc.moveTo(x, y) else arc.lineTo(x, y)
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f; color = Palette.alpha(ink, .35f); pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 5f), 0f) }
        p.canvas.drawPath(arc, stroke)
        // travelled part solid
        val done = Path()
        for (i in 0..(steps * progress).toInt()) {
            val t = i / steps.toFloat(); val ang = PI * (1 - t)
            val x = cx + rx * cos(ang).toFloat(); val y = hy - ry * sin(ang).toFloat()
            if (i == 0) done.moveTo(x, y) else done.lineTo(x, y)
        }
        stroke.pathEffect = null; stroke.strokeWidth = 3f; stroke.color = Palette.alpha(ink, .9f)
        p.canvas.drawPath(done, stroke)
        p.line(ax - 10f, hy, bx + 10f, hy, Palette.alpha(ink, .35f), 1f)
        // the sun / moon
        val ang = PI * (1 - progress)
        val sx = cx + rx * cos(ang).toFloat(); val sy = hy - ry * sin(ang).toFloat()
        if (day) {
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.alpha(0xffffe27a.toInt(), .45f); maskFilter = android.graphics.BlurMaskFilter(12f, android.graphics.BlurMaskFilter.Blur.NORMAL) }
            p.canvas.drawCircle(sx, sy, 14f, glow)
            p.circle(sx, sy, 9f, 0xffffe27a.toInt())
        } else {
            p.icon(R.drawable.ic_moon_waning_crescent, sx, sy, 22f, Color.WHITE)
        }
        // times under the horizon
        val riseS = rise?.atZone(zone)?.format(fmt) ?: "—"; val setS = set?.atZone(zone)?.format(fmt) ?: "—"
        p.icon(R.drawable.ic_weather_sunset_up, ax, hy + 16f, 14f, sub)
        p.text(riseS, ax + 10f, hy + 16f, 11f, 600, sub)
        val sw = p.measure(setS, 11f, 600)
        p.text(setS, bx, hy + 16f, 11f, 600, sub, Align.RIGHT)
        p.icon(R.drawable.ic_weather_sunset_down, bx - sw - 10f, hy + 16f, 14f, sub)
        // centre caption: elevation / time to sunset or sunrise
        val cap = if (day && set != null) "sets in ${hm(Duration.between(now, set))} · ${el.roundToInt()}° up"
                  else if (!day && rise != null) "rises in ${hm(Duration.between(now, rise))}" else ""
        p.text(cap, cx, hy + 16f, 11f, 500, sub, Align.CENTER, maxW = rx * 2 - 120f)
        spots.add(Hotspot(RectF(0f, 0f, p.w, p.h), a.open(cfg["path_sky"])))
        return spots
    }

    private fun hm(d: Duration): String { val m = d.toMinutes().coerceAtLeast(0); return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m" }
}
