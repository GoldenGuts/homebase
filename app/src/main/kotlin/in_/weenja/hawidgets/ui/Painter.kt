package in_.weenja.hawidgets.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.appcompat.content.res.AppCompatResources
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Inter, loaded once per process from assets. */
object Fonts {
    private val cache = HashMap<Int, Typeface>()
    fun get(ctx: Context, weight: Int): Typeface = cache.getOrPut(weight) {
        val file = when {
            weight >= 800 -> "Inter-ExtraBold.ttf"
            weight >= 700 -> "Inter-Bold.ttf"
            weight >= 600 -> "Inter-SemiBold.ttf"
            weight >= 500 -> "Inter-Medium.ttf"
            else -> "Inter-Regular.ttf"
        }
        try { Typeface.createFromAsset(ctx.assets, "fonts/$file") } catch (e: Exception) { Typeface.DEFAULT }
    }
}

enum class Align { LEFT, CENTER, RIGHT }

/**
 * Canvas wrapper that works in dp. Every widget draws through this so the bitmap and the tap zones
 * (also in dp) line up. `scale` = px per dp.
 */
class Painter(val ctx: Context, val w: Float, val h: Float, val scale: Float, val p: Palette) {
    val bitmap: Bitmap = Bitmap.createBitmap(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    init { canvas.scale(scale, scale) }

    companion object {
        /** When set (debug end-to-end runs), every string drawn is added here. */
        @JvmStatic var trace: MutableList<String>? = null
    }

    fun px(dp: Float) = dp * scale

    // ------------------------------------------------------------ shapes

    fun rect(l: Float, t: Float, r: Float, b: Float) = RectF(l, t, r, b)

    fun rrect(r: RectF, radius: Float, color: Int, shadow: Float = 0f) {
        fill.shader = null; fill.color = color
        if (shadow > 0f) fill.setShadowLayer(shadow, 0f, shadow / 2.5f, Color.argb(90, 0, 0, 0))
        canvas.drawRoundRect(r, radius, radius, fill)
        if (shadow > 0f) fill.clearShadowLayer()
    }

    fun rrect(r: RectF, radius: Float, color: Long, shadow: Float = 0f) = rrect(r, radius, color.toInt(), shadow)

    /** CSS `linear-gradient(<angle>deg, a, b)` on a rounded rect. */
    fun gradient(r: RectF, radius: Float, grad: Pair<Long, Long>, angleDeg: Float = 160f, shadow: Float = 0f, alpha: Int = 255) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = sin(rad).toFloat(); val dy = -cos(rad).toFloat()
        val len = abs(r.width() * dx) + abs(r.height() * dy)
        val cx = r.centerX(); val cy = r.centerY()
        fill.shader = LinearGradient(cx - dx * len / 2, cy - dy * len / 2, cx + dx * len / 2, cy + dy * len / 2,
            Palette.alpha(grad.first, alpha / 255f), Palette.alpha(grad.second, alpha / 255f), Shader.TileMode.CLAMP)
        fill.color = Color.WHITE
        if (shadow > 0f) fill.setShadowLayer(shadow, 0f, shadow / 2.5f, Color.argb(90, 0, 0, 0))
        canvas.drawRoundRect(r, radius, radius, fill)
        fill.clearShadowLayer(); fill.shader = null
    }

    fun outline(r: RectF, radius: Float, color: Int, width: Float = 1f) {
        stroke.color = color; stroke.strokeWidth = width
        canvas.drawRoundRect(r, radius, radius, stroke)
    }

    fun circle(cx: Float, cy: Float, radius: Float, color: Int) {
        fill.shader = null; fill.color = color
        canvas.drawCircle(cx, cy, radius, fill)
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float = 1f, dashed: Boolean = false) {
        stroke.color = color; stroke.strokeWidth = width
        stroke.pathEffect = if (dashed) android.graphics.DashPathEffect(floatArrayOf(3f, 3f), 0f) else null
        canvas.drawLine(x1, y1, x2, y2, stroke)
        stroke.pathEffect = null
    }

    /** The glass card: surface, 1dp border, 22dp radius (theme ha-card-*). */
    fun card(r: RectF = RectF(0f, 0f, w, h), radius: Float = 22f) {
        rrect(r, radius, p.card)
        val inset = RectF(r.left + .5f, r.top + .5f, r.right - .5f, r.bottom - .5f)
        outline(inset, radius, p.border.toInt(), 1f)
    }

    /** Thin progress bar (.bar). */
    fun bar(r: RectF, pct: Float, color: Int, track: Int = p.track.toInt()) {
        val rad = r.height() / 2
        rrect(r, rad, track)
        if (pct > 0f) rrect(RectF(r.left, r.top, r.left + r.width() * pct.coerceIn(0f, 1f), r.bottom), rad, color)
    }

    fun bar(r: RectF, pct: Float, grad: Pair<Long, Long>) {
        val rad = r.height() / 2
        rrect(r, rad, p.track)
        if (pct > 0f) gradient(RectF(r.left, r.top, r.left + r.width() * pct.coerceIn(0f, 1f), r.bottom), rad, grad, 90f)
    }

    // ------------------------------------------------------------ text

    private fun setup(size: Float, weight: Int, color: Int, letterSpacing: Float = 0f) {
        tp.typeface = Fonts.get(ctx, weight); tp.textSize = size; tp.color = color; tp.letterSpacing = letterSpacing
    }

    fun measure(s: String, size: Float, weight: Int): Float { setup(size, weight, 0); return tp.measureText(s) }

    /**
     * Draw one line. (x,y) is the anchor: y is the vertical centre of the text box unless [top] is true.
     * Returns the drawn width. Text longer than [maxW] is ellipsized.
     */
    fun text(s: String, x: Float, y: Float, size: Float, weight: Int = 400, color: Int = p.ink.toInt(),
             align: Align = Align.LEFT, maxW: Float = Float.MAX_VALUE, top: Boolean = false, letterSpacing: Float = 0f): Float {
        if (s.isEmpty()) return 0f
        setup(size, weight, color, letterSpacing)
        val str = if (maxW < Float.MAX_VALUE) TextUtils.ellipsize(s, tp, maxW, TextUtils.TruncateAt.END).toString() else s
        val wdt = tp.measureText(str)
        val fm = tp.fontMetrics
        val baseline = if (top) y - fm.ascent else y - (fm.ascent + fm.descent) / 2
        val x0 = when (align) { Align.LEFT -> x; Align.CENTER -> x - wdt / 2; Align.RIGHT -> x - wdt }
        canvas.drawText(str, x0, baseline, tp)
        trace?.add(str)
        return wdt
    }

    fun text(s: String, x: Float, y: Float, size: Float, weight: Int, color: Long, align: Align = Align.LEFT,
             maxW: Float = Float.MAX_VALUE, top: Boolean = false, letterSpacing: Float = 0f) =
        text(s, x, y, size, weight, color.toInt(), align, maxW, top, letterSpacing)

    /** Word-wrapped paragraph, at most [maxLines] lines. Returns the height used. */
    fun paragraph(s: String, x: Float, y: Float, maxW: Float, size: Float, weight: Int, color: Int, maxLines: Int, lineH: Float = size * 1.4f): Float {
        setup(size, weight, color)
        val words = s.split(' ')
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        for (wd in words) {
            val trial = if (cur.isEmpty()) wd else "$cur $wd"
            if (tp.measureText(trial) <= maxW || cur.isEmpty()) cur = StringBuilder(trial)
            else { lines.add(cur.toString()); cur = StringBuilder(wd) }
            if (lines.size == maxLines) break
        }
        if (lines.size < maxLines && cur.isNotEmpty()) lines.add(cur.toString())
        var yy = y
        lines.forEachIndexed { i, ln ->
            val last = i == lines.size - 1 && (i == maxLines - 1)
            text(if (last) ln else ln, x, yy, size, weight, color, top = true, maxW = if (last) maxW else Float.MAX_VALUE)
            yy += lineH
        }
        return lines.size * lineH
    }

    /** Text that stays inside [maxW] by shrinking the size down to [minSize] before ellipsizing. */
    fun fitText(s: String, x: Float, y: Float, size: Float, minSize: Float, weight: Int, color: Int, align: Align, maxW: Float, top: Boolean = false) {
        var sz = size
        while (sz > minSize && measure(s, sz, weight) > maxW) sz -= 1f
        text(s, x, y, sz, weight, color, align, maxW, top)
    }

    // ------------------------------------------------------------ icons

    fun icon(resId: Int, cx: Float, cy: Float, size: Float, color: Int, alpha: Float = 1f, shadow: Boolean = false) {
        val d = AppCompatResources.getDrawable(ctx, resId)?.mutate() ?: return
        d.setTint(color); d.alpha = (alpha * 255).toInt()
        val half = size / 2
        if (shadow) {
            // drop-shadow(0 4px 10px rgba(0,0,0,.25)) approximation: a blurred dark copy underneath
            val sh = AppCompatResources.getDrawable(ctx, resId)?.mutate()
            sh?.setTint(Color.BLACK); sh?.alpha = 60
            sh?.setBounds((cx - half).toInt(), (cy - half + 3).toInt(), (cx + half).toInt(), (cy + half + 3).toInt())
            sh?.draw(canvas)
        }
        d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        d.draw(canvas)
    }

    // ------------------------------------------------------------ composite pieces

    /** `.pill`: small rounded label with an optional dot / icon. Returns its width. */
    fun pill(x: Float, y: Float, label: String, bg: Int, ink: Int, size: Float = 11f, weight: Int = 700,
             iconRes: Int? = null, iconColor: Int = ink, padX: Float = 10f, height: Float = 24f, maxW: Float = Float.MAX_VALUE, draw: Boolean = true): Float {
        val iconW = if (iconRes != null) 13f + 5f else 0f
        val tw = measure(label, size, weight).coerceAtMost(maxW - padX * 2 - iconW)
        val wdt = padX * 2 + iconW + tw
        if (draw) {
            rrect(RectF(x, y, x + wdt, y + height), height / 2, bg)
            var tx = x + padX
            if (iconRes != null) { icon(iconRes, tx + 6.5f, y + height / 2, 13f, iconColor); tx += iconW }
            text(label, tx, y + height / 2, size, weight, ink, maxW = tw)
        }
        return wdt
    }

    /** `chip` template: pill button with a coloured icon and bold label. */
    fun chip(r: RectF, label: String, iconRes: Int?, color: Int, ink: Int = p.ink.toInt(), bg: Int = p.mix(color.toLong() and 0xffffffffL), size: Float = 12f, pad: Float = 12f) {
        rrect(r, r.height() / 2, bg)
        val iconW = if (iconRes != null) 16f + 6f else 0f
        val tw = measure(label, size, 700).coerceAtMost(r.width() - pad * 2 - iconW)
        var x = r.centerX() - (iconW + tw) / 2
        if (iconRes != null) { icon(iconRes, x + 8f, r.centerY(), 16f, color); x += iconW }
        text(label, x, r.centerY(), size, 700, ink, maxW = tw)
    }

    /** Card header: bold 14 title left, muted 12 meta right (the `.row.between` pattern). */
    fun header(title: String, meta: String, x: Float, y: Float, wdt: Float) {
        val tw = text(title, x, y, 14f, 700, p.ink)
        text(meta, x + wdt, y, 12f, 400, p.muted, Align.RIGHT, maxW = wdt - tw - 10f)
    }

    /** `.tag`: 10px uppercase letter-spaced muted label. */
    fun tag(s: String, x: Float, y: Float) = text(s.uppercase(), x, y, 10f, 700, p.muted, letterSpacing = .1f)

    /**
     * The `knob` ring gauge: a 270° track from the bottom-left, a glowing gradient arc for [pct] (0..1)
     * and the value in the middle. [radius] is the ring radius in dp; stroke scales with it.
     */
    fun knob(cx: Float, cy: Float, radius: Float, pct: Float, value: String, valueSize: Float = radius * .42f,
             colors: IntArray = intArrayOf(0xff22e3a1.toInt(), 0xff3ec6ff.toInt(), 0xff7b61ff.toInt(), 0xffff4f81.toInt(), 0xffff4f81.toInt())) {
        val sw = radius * .14f
        val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val sweep = 270f * pct.coerceIn(0f, 1f)
        stroke.strokeWidth = sw; stroke.strokeCap = Paint.Cap.ROUND
        stroke.color = p.track.toInt(); stroke.shader = null
        canvas.drawArc(oval, 135f, 270f, false, stroke)
        if (sweep > 0.5f) {
            // gradient follows the arc: sweep gradient rotated so it starts at 135°
            val grad = android.graphics.SweepGradient(cx, cy, colors, floatArrayOf(0f, .35f, .6f, .8f, 1f))
            val m = Matrix(); m.setRotate(135f, cx, cy); grad.setLocalMatrix(m)
            stroke.shader = grad
            stroke.maskFilter = android.graphics.BlurMaskFilter(sw * .8f, android.graphics.BlurMaskFilter.Blur.NORMAL); stroke.alpha = 140
            canvas.drawArc(oval, 135f, sweep, false, stroke)
            stroke.maskFilter = null; stroke.alpha = 255
            canvas.drawArc(oval, 135f, sweep, false, stroke)
            stroke.shader = null
        }
        stroke.strokeCap = Paint.Cap.BUTT
        fitText(value, cx, cy, valueSize, valueSize * .6f, 700, p.ink.toInt(), Align.CENTER, radius * 1.5f)
    }

    /** `.kstats`: two centred value/label stats with a divider between them. */
    fun kstats(x: Float, y: Float, wdt: Float, aVal: String, aLab: String, bVal: String, bLab: String, size: Float = 22f) {
        line(x + wdt / 2, y, x + wdt / 2, y + size + 20f, p.track.toInt())
        text(aVal, x + wdt / 4, y + size / 2 + 2f, size, 700, p.ink, Align.CENTER, maxW = wdt / 2 - 10f, letterSpacing = -.02f)
        text(aLab, x + wdt / 4, y + size + 12f, 12f, 400, p.muted, Align.CENTER, maxW = wdt / 2 - 10f)
        text(bVal, x + wdt * 3 / 4, y + size / 2 + 2f, size, 700, p.ink, Align.CENTER, maxW = wdt / 2 - 10f, letterSpacing = -.02f)
        text(bLab, x + wdt * 3 / 4, y + size + 12f, 12f, 400, p.muted, Align.CENTER, maxW = wdt / 2 - 10f)
    }

    /** `.urow`: dot, name, 80dp bar, muted value. */
    fun usageRow(x: Float, y: Float, wdt: Float, name: String, color: Int, pct: Float, valueText: String, barW: Float = 80f) {
        circle(x + 4.5f, y, 4.5f, color)
        val vw = measure(valueText, 12f, 400)
        text(valueText, x + wdt, y, 12f, 400, p.muted, Align.RIGHT)
        bar(RectF(x + wdt - vw - 10f - barW, y - 3f, x + wdt - vw - 10f, y + 3f), pct, color)
        text(name, x + 16f, y, 13f, 400, p.ink, maxW = wdt - 16f - vw - 10f - barW - 8f)
    }

    /** Small tinted square with an icon inside (`.item .ico`). */
    fun iconBox(x: Float, y: Float, size: Float, radius: Float, iconRes: Int, color: Int, iconSize: Float = size * .5f) {
        rrect(RectF(x, y, x + size, y + size), radius, Palette.alpha(color, .14f))
        icon(iconRes, x + size / 2, y + size / 2, iconSize, color)
    }

    fun clipRound(r: RectF, radius: Float) {
        val path = Path(); path.addRoundRect(r, radius, radius, Path.Direction.CW); canvas.clipPath(path)
    }

    /** Blurred, darkened artwork behind the now-playing capsule (the `.bg` layer). */
    fun blurredArt(art: Bitmap, r: RectF, radius: Float, dark: Float = .45f) {
        canvas.save()
        clipRound(r, radius)
        // cheap blur: shrink to a handful of pixels, then draw it back up with bilinear filtering
        val tiny = Bitmap.createScaledBitmap(art, 6, 6, true)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val m = Matrix()
        val sx = (r.width() + 60f) / tiny.width; val sy = (r.height() + 60f) / tiny.height
        val s = max(sx, sy)
        m.setScale(s, s); m.postTranslate(r.centerX() - tiny.width * s / 2, r.centerY() - tiny.height * s / 2)
        canvas.drawBitmap(tiny, m, paint)
        rrect(r, radius, Color.argb((dark * 255).toInt(), 0, 0, 0))
        canvas.restore()
    }

    fun drawBitmap(b: Bitmap, r: RectF, radius: Float) {
        canvas.save(); clipRound(r, radius)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val s = max(r.width() / b.width, r.height() / b.height)
        val m = Matrix(); m.setScale(s, s); m.postTranslate(r.centerX() - b.width * s / 2, r.centerY() - b.height * s / 2)
        canvas.drawBitmap(b, m, paint)
        canvas.restore()
    }
}
