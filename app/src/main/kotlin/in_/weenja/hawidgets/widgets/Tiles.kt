package in_.weenja.hawidgets.widgets

import android.app.PendingIntent
import android.graphics.RectF
import in_.weenja.hawidgets.R
import in_.weenja.hawidgets.core.Accent
import in_.weenja.hawidgets.core.EntityState
import in_.weenja.hawidgets.core.Rules
import in_.weenja.hawidgets.ui.Align
import in_.weenja.hawidgets.ui.Icons
import in_.weenja.hawidgets.ui.Painter
import in_.weenja.hawidgets.ui.Palette
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * Entity tiles in the dashboard's device_tile style (gradient when active, dark when off) and the grid
 * that fits as many as the widget size allows. Favorites, Suggested, Room, Security and My devices use it.
 */
object Tiles {
    class Cell(
        val title: String,
        val sub: String,
        val icon: Int,
        val accent: Accent,
        val active: Boolean,
        val pi: PendingIntent?,
        val alert: Boolean = false,
        val missing: Boolean = false,
        val armed: Boolean = false,
        val label: String = title,
    )

    /** Tile for one entity; [id] is used when Home Assistant no longer knows it. */
    /** "Living room ceiling" in the Living room widget reads "Ceiling". */
    fun shortName(name: String, prefix: String?): String {
        if (prefix.isNullOrBlank()) return name
        // Home Assistant names are often "Living Room …" in the area "Living room", sometimes doubled
        var rest = name
        while (rest.startsWith(prefix, ignoreCase = true) && rest.length - prefix.length >= 2) rest = rest.substring(prefix.length).trimStart(' ', '-', '·', ':')
        return if (rest.length >= 2 && rest.length < name.length) rest.replaceFirstChar { it.uppercase() } else name
    }

    fun cell(e: EntityState?, id: String, a: Actions, now: Long, stripPrefix: String? = null): Cell {
        if (e == null) return Cell(id.substringAfter('.').replace('_', ' ').replaceFirstChar { it.uppercase() }, "not found · tap to fix",
            R.drawable.ic_help_circle_outline, Accent.MUTED, false, a.configure(), missing = true)
        val alert = Rules.isAlert(e)
        val call = Rules.tapAction(e)
        val armed = call?.confirm == true && a.isArmed("tap:${e.id}:${call.service}")
        val icon = e.icon.takeIf { Icons.has(it) } ?: Rules.icon(e)
        val sub = when {
            armed -> "tap again to ${call!!.service.replace('_', ' ').substringBefore(" cover")}"
            else -> Rules.stateLabel(e, now)
        }
        return Cell(shortName(e.name, stripPrefix), sub, Icons.of(icon), if (alert) Accent.PINK else Rules.accent(e), Rules.isActive(e) || alert,
            if (call != null) a.tap(e) else a.moreInfo(e.id), alert = alert, armed = armed, label = "${e.name}, ${Rules.stateLabel(e, now)}")
    }

    class Fit(val cols: Int, val rows: Int, val shown: Int)

    /** Columns x rows so [n] tiles fill [w] x [h], each at least [minW] x [minH]; fewer when they cannot all fit. */
    fun fit(w: Float, h: Float, n: Int, gap: Float, minW: Float = 66f, minH: Float = 56f, maxRows: Int = 4, aspect: Float = 1.1f): Fit {
        if (n <= 0) return Fit(1, 1, 0)
        var best: Fit? = null; var bestScore = Float.MAX_VALUE
        for (rows in 1..maxRows) {
            val cols = ceil(n / rows.toFloat()).toInt()
            if (cols * rows - n >= cols) continue
            val tw = (w - gap * (cols - 1)) / cols; val th = (h - gap * (rows - 1)) / rows
            if (tw < minW || th < minH) continue
            val score = kotlin.math.abs(tw / th - aspect) + (cols * rows - n) * .15f
            if (score < bestScore) { bestScore = score; best = Fit(cols, rows, n) }
        }
        best?.let { return it }
        val cols = floor((w + gap) / (minW + gap)).toInt().coerceAtLeast(1)
        val rows = floor((h + gap) / (minH + gap)).toInt().coerceIn(1, maxRows)
        return Fit(cols, rows, min(n, cols * rows))
    }

    /** Lay out and draw the tiles; the last row stretches to the full width. */
    fun grid(p: Painter, area: RectF, cells: List<Cell>, gap: Float = 8f, minW: Float = 66f, minH: Float = 56f, maxRows: Int = 4, maxTileH: Float = 150f): List<Hotspot> {
        val spots = ArrayList<Hotspot>()
        val f = fit(area.width(), area.height(), cells.size, gap, minW, minH, maxRows)
        if (f.shown == 0) return spots
        val rows = ceil(f.shown / f.cols.toFloat()).toInt()
        val th = ((area.height() - gap * (rows - 1)) / rows).coerceAtMost(maxTileH)
        val top = area.top + ((area.height() - (rows * th + (rows - 1) * gap)) / 2).coerceAtLeast(0f)
        for (i in 0 until f.shown) {
            val r = i / f.cols; val c = i % f.cols
            val inRow = if (r == rows - 1) f.shown - r * f.cols else f.cols
            val tw = (area.width() - gap * (inRow - 1)) / inRow
            val rect = RectF(area.left + c * (tw + gap), top + r * (th + gap), area.left + c * (tw + gap) + tw, top + r * (th + gap) + th)
            tile(p, rect, cells[i])
            cells[i].pi?.let { spots.add(Hotspot(rect, it, cells[i].label)) }
        }
        return spots
    }

    fun tile(p: Painter, r: RectF, t: Cell) {
        val pal = p.p
        val on = t.active && !t.missing
        val radius = min(18f, min(r.width(), r.height()) * .3f)
        if (on) p.gradient(r, radius, pal.grad(t.accent), 160f, shadow = 8f) else p.rrect(r, radius, pal.surface2)
        if (t.armed) p.outline(RectF(r.left + 1f, r.top + 1f, r.right - 1f, r.bottom - 1f), radius, pal.pink.toInt(), 2f)
        val ink = if (on) pal.inkOn(t.accent).toInt() else if (t.missing) pal.dim.toInt() else pal.muted.toInt()
        val nameInk = if (on) ink else if (t.missing) pal.dim.toInt() else pal.ink.toInt()
        val wide = r.width() >= 118f && r.height() < 74f
        if (wide) {
            // icon left, name + state right
            val iconSize = min(30f, r.height() * .5f).coerceAtLeast(18f)
            p.icon(t.icon, r.left + 12f + iconSize / 2, r.centerY(), iconSize, if (on) ink else pal.color(t.accent).toInt(), alpha = if (t.missing) .45f else 1f, shadow = on)
            val tx = r.left + 20f + iconSize
            val tw = r.right - tx - 10f
            if (r.height() >= 44f) {
                p.text(t.title, tx, r.centerY() - 8f, 12.5f, 700, nameInk, maxW = tw)
                p.text(t.sub, tx, r.centerY() + 9f, 10.5f, 500, Palette.alpha(ink, if (on) .8f else 1f), maxW = tw)
            } else p.text(t.title, tx, r.centerY(), 12f, 700, nameInk, maxW = tw)
            return
        }
        val showName = r.width() >= 48f && r.height() >= 40f
        val showSub = r.height() >= 84f && t.sub.isNotEmpty()
        // narrow tiles wrap the name onto two lines instead of cutting it after a few letters
        val lines = if (showName) nameLines(p, t.title, r.width() - 10f, if ((!showSub && r.height() >= 56f) || r.height() >= 88f) 2 else 1) else emptyList()
        val iconSize = min(40f, r.height() * .38f).coerceAtLeast(16f)
        val textBlock = lines.size * 13f + (if (lines.isNotEmpty()) 2f else 0f) + (if (showSub) 13f else 0f)
        val cy = r.centerY() - textBlock / 2 + (if (lines.isNotEmpty()) 1f else 0f)
        p.icon(t.icon, r.centerX(), cy, iconSize, if (on) ink else pal.color(t.accent).toInt(), alpha = if (t.missing) .45f else if (on) 1f else .9f, shadow = on)
        var ty = cy + iconSize / 2 + 9f
        for (ln in lines) { p.text(ln, r.centerX(), ty, 11f, 700, nameInk, Align.CENTER, maxW = r.width() - 8f); ty += 13f }
        if (showSub) p.text(t.sub, r.centerX(), ty + 1f, 10f, 500, Palette.alpha(ink, if (on) .8f else 1f), Align.CENTER, maxW = r.width() - 10f)
    }

    /** Break [s] into at most [max] lines that fit [w]; the last line is ellipsized by the caller. */
    private fun nameLines(p: Painter, s: String, w: Float, max: Int): List<String> {
        if (max <= 1 || p.measure(s, 11f, 700) <= w) return listOf(s)
        // scripts without spaces (Japanese, Chinese, Thai) or a single long word: break between characters
        if (!s.contains(' ')) {
            var n = 1
            while (n < s.length && p.measure(s.substring(0, n + 1), 11f, 700) <= w) n++
            return listOf(s.substring(0, n), s.substring(n).trimStart())
        }
        val words = s.split(' ')
        var first = words.first()
        var i = 1
        while (i < words.size && p.measure(first + " " + words[i], 11f, 700) <= w) { first += " " + words[i]; i++ }
        if (i >= words.size) return listOf(first)
        return listOf(first, words.drop(i).joinToString(" "))
    }
}
