package in_.weenja.hawidgets.widgets

import android.content.Context
import android.graphics.RectF
import in_.weenja.hawidgets.Cfg
import in_.weenja.hawidgets.R
import in_.weenja.hawidgets.ha.Snapshot
import in_.weenja.hawidgets.ui.Align
import in_.weenja.hawidgets.ui.Painter
import in_.weenja.hawidgets.ui.Palette
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

/** Category colours + labels shared by the spend and dues widgets (dashboard `cats` / `C` tables). */
object Cats {
    /** name, config key of the input_number, gradient */
    val list = listOf(
        Triple("Food", "cat_food", 0xffffb347L to 0xffff8a1eL),
        Triple("Subs", "cat_subs", 0xffff5c8aL to 0xffff2d6fL),
        Triple("Rent", "cat_other", 0xff9d7bffL to 0xff6a3dffL),
        Triple("Travel", "cat_travel", 0xff4fd7ffL to 0xff1fa8ffL),
        Triple("Shop", "cat_shopping", 0xffc6f04aL to 0xff8fc31fL),
    )
    val dots = listOf(0xffffb347L, 0xffff5c8aL, 0xff9d7bffL, 0xff4fd7ffL, 0xffc6f04aL)
    val byKey = mapOf("food" to (0xffffb347L to "Food"), "subs" to (0xffff5c8aL to "Subs"), "travel" to (0xff4fd7ffL to "Travel"),
        "shopping" to (0xffc6f04aL to "Shop"), "other" to (0xff9d7bffL to "Other"))
}

/**
 * The "Spending" card: month total vs budget, 5 category bars, the daily insight, coming-up pills,
 * card dues and the recent rows. Sections appear top-down as the widget height allows.
 */
class SpendWidget : CardWidget() {

    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        p.card()
        val padX = 20f; val padY = 18f
        val innerW = p.w - padX * 2
        val bottom = p.h - padY

        val cfg = a.cfg
        val vals = Cats.list.map { snap.num(cfg[it.second]) }
        val total = vals.sum()
        val budget = snap.num(cfg["budget_month"]).takeIf { it > 0 } ?: 1.0
        val maxV = max(vals.max(), 1.0)
        val feed = snap[cfg["spend_feed"]]?.attrs ?: JSONObject()

        var y = padY + 10f
        val badge = Common.statusBadge(ctx, p, a, snap, p.w - padX, y)?.also { spots.add(it) }
        p.header("Spending", if (badge == null) "${Common.monthName()} · ${Common.rs(total)} of ${Common.rs(budget)}" else "", padX, y, innerW)
        y += 20f

        // ---- very short: one stacked bar of the categories + the legend
        if (bottom - y < 96f) {
            val barR = RectF(padX, y + 6f, p.w - padX, y + 20f)
            p.rrect(barR, 7f, pal.track)
            var x = barR.left
            p.canvas.save(); p.clipRound(barR, 7f)
            Cats.list.forEachIndexed { i, (_, _, grad) ->
                val w = (barR.width() * vals[i] / budget).toFloat()
                if (w > 0f) p.gradient(RectF(x, barR.top, x + w, barR.bottom), 0f, grad, 90f); x += w
            }
            p.canvas.restore()
            var lx = padX
            Cats.list.forEachIndexed { i, (name, _, _) ->
                if (lx + 60f > p.w - padX) return@forEachIndexed
                p.circle(lx + 3.5f, y + 36f, 3.5f, Cats.dots[i].toInt())
                lx += 12f + p.text("$name ${Common.rs(vals[i])}", lx + 12f, y + 36f, 10.5f, 600, pal.muted) + 10f
            }
            return spots
        }

        // ---- bars: 146dp like the dashboard when everything fits, else the bars give way to the
        // sections below (down to 60dp); very small widgets show only the chart
        val innerH = bottom - y
        val labelsH = 28f
        val insight = feed.optJSONObject("insight")?.optString("text").orEmpty()
        val up = feed.optJSONArray("coming_up") ?: JSONArray()
        val cards = feed.optJSONArray("cards") ?: JSONArray()
        val recent = feed.optJSONArray("recent") ?: JSONArray()
        var need = 0f
        if (insight.isNotEmpty()) need += 16f + 2 * 16.8f + 12f
        if (up.length() > 0) need += 20f + pillRows(p, up, innerW) * 30f + 4f
        if (cards.length() > 0) need += 20f + cards.length() * 28f + 4f
        if (recent.length() > 0) need += 20f + recent.length() * 30f
        val barsH = when {
            innerH < 200f -> (innerH - labelsH - 4f).coerceAtLeast(60f)
            else -> (innerH - labelsH - need).coerceIn(60f, 240f)
        }
        val barsTop = y; val barsBottom = y + barsH
        val colW = (innerW - 12f) / 5f
        val barW = (colW * .55f).coerceIn(18f, 34f)
        val usable = barsH - 26f
        Cats.list.forEachIndexed { i, (_, _, grad) ->
            val cx = padX + 6f + colW * i + colW / 2
            val h = max(6f, (usable * vals[i] / maxV).toFloat())
            val r = RectF(cx - barW / 2, barsBottom - h, cx + barW / 2, barsBottom)
            p.gradient(r, 8f, grad, 180f, shadow = 6f)
            if (vals[i] == maxV && maxV > 0 && total > 0) {
                val tip = Common.rs(vals[i])
                val tw = p.pill(0f, 0f, tip, 0, 0, size = 11f, height = 20f, draw = false)
                p.pill(cx - tw / 2, r.top - 28f, tip, pal.surface2.toInt(), pal.ink.toInt(), size = 11f, height = 20f)
            }
        }
        p.line(padX, barsBottom + .5f, p.w - padX, barsBottom + .5f, pal.track.toInt(), 1f, dashed = true)
        if (total == 0.0) Common.empty(p, RectF(padX, barsTop, p.w - padX, barsBottom), Common.ICON_EMPTY, "No spending logged yet", "Fill the category input_numbers")
        // labels: dot + name, centred under each bar
        Cats.list.forEachIndexed { i, (name, _, _) ->
            val cx = padX + 6f + colW * i + colW / 2
            val tw = p.measure(name, 11f, 600)
            val x0 = cx - (tw + 12f) / 2
            p.circle(x0 + 3.5f, barsBottom + 14f, 3.5f, Cats.dots[i].toInt())
            p.text(name, x0 + 12f, barsBottom + 14f, 11f, 600, pal.ink)
        }
        y = barsBottom + labelsH

        // ---- sections, only while they fit
        if (insight.isNotEmpty()) {
            val lines = if (bottom - y >= 60f) 2 else 1
            val boxH = 16f + lines * 16.8f
            if (y + boxH <= bottom) {
                val r = RectF(padX, y, p.w - padX, y + boxH)
                p.rrect(r, 12f, pal.surface2)
                p.icon(R.drawable.ic_creation, r.left + 17f, r.top + 16f, 14f, pal.pink.toInt())
                p.paragraph(insight, r.left + 30f, r.top + 8f, r.width() - 40f, 12f, 400, pal.ink.toInt(), lines, 16.8f)
                y += boxH + 12f
            }
        }

        if (up.length() > 0 && bottom - y >= 46f) {
            y = section(p, "Coming up", y)
            y = pills(p, up, padX, y, innerW, bottom)
        }

        if (cards.length() > 0 && bottom - y >= 46f) {
            y = section(p, "Card dues", y)
            for (i in 0 until cards.length()) {
                if (y + 28f > bottom) break
                cardRow(p, cards.getJSONObject(i), padX, y, innerW); y += 28f
            }
            y += 4f
        }

        if (recent.length() > 0 && bottom - y >= 46f) {
            y = section(p, "Recent", y)
            for (i in 0 until recent.length()) {
                if (y + 30f > bottom) break
                txnRow(p, recent.getJSONObject(i), padX, y, innerW); y += 30f
            }
        }
        return spots
    }

    private fun section(p: Painter, title: String, y: Float): Float {
        p.tag(title, 20f, y + 8f)
        return y + 20f
    }

    fun pillRows(p: Painter, up: JSONArray, maxW: Float): Int {
        var x = 0f; var rows = 1
        for (i in 0 until minOf(up.length(), 6)) {
            val u = up.getJSONObject(i)
            val bill = u.optString("bill").takeIf { it.isNotEmpty() && it != "null" }
            val label = "${if (bill != null) Common.cap(bill) else u.optString("merchant")}  ${Common.dm(u.optString("date"))}  ${Common.rs(u.optDouble("amount", 0.0))}"
            val wdt = p.pill(0f, 0f, label, 0, 0, size = 11f, weight = 600, iconRes = R.drawable.ic_flash, draw = false).coerceAtMost(maxW)
            if (x + wdt > maxW + .5f) { x = 0f; rows++ }
            x += wdt + 6f
        }
        return rows
    }

    /** `.pills` row(s): icon + merchant + date + amount. Returns the y below the last row that fit. */
    fun pills(p: Painter, up: JSONArray, x0: Float, y0: Float, maxW: Float, bottom: Float): Float {
        val pal = p.p
        var x = x0; var y = y0
        val h = 24f
        for (i in 0 until minOf(up.length(), 6)) {
            val u = up.getJSONObject(i)
            val bill = u.optString("bill").takeIf { it.isNotEmpty() && it != "null" }
            val name = if (bill != null) Common.cap(bill) else u.optString("merchant")
            val label = "$name  ${Common.dm(u.optString("date"))}  ${Common.rs(u.optDouble("amount", 0.0))}"
            val ic = if (bill != null) R.drawable.ic_flash else R.drawable.ic_autorenew
            val wdt = p.pill(0f, 0f, label, 0, 0, size = 11f, weight = 600, iconRes = ic, draw = false).coerceAtMost(maxW)
            if (x + wdt > x0 + maxW + .5f) { x = x0; y += h + 6f }
            if (y + h > bottom) return y
            // dim the middle date part like the dashboard: draw the pill, then re-paint the date muted
            p.pill(x, y, label, pal.surface2.toInt(), pal.ink.toInt(), size = 11f, weight = 600, iconRes = ic, iconColor = pal.pink.toInt(), maxW = maxW)
            x += wdt + 6f
        }
        return y + h + 10f
    }

    /** `.crow`: icon box, bank, ··last4, "by 21 Sep", amount. */
    fun cardRow(p: Painter, c: JSONObject, x: Float, y: Float, w: Float) {
        val pal = p.p
        val cy = y + 14f
        p.rrect(RectF(x, cy - 14f, x + 28f, cy + 14f), 9f, Palette.alpha(0xff4fd7ff, .13f))
        p.icon(R.drawable.ic_credit_card_outline, x + 14f, cy, 16f, 0xff4fd7ff.toInt())
        var tx = x + 36f
        tx += p.text(c.optString("bank"), tx, cy, 13f, 700, pal.ink) + 6f
        val last = c.optString("last").takeIf { it.isNotEmpty() && it != "null" }
        if (last != null) tx += p.text("··$last", tx, cy, 12f, 400, pal.muted) + 6f
        val amt = Common.rs(c.optDouble("due", 0.0))
        val aw = p.text(amt, x + w, cy, 13f, 700, pal.ink, Align.RIGHT)
        val dd = c.optString("due_date").takeIf { it.isNotEmpty() && it != "null" }
        if (dd != null) {
            val soon = (Common.daysUntil(dd) ?: 99) < 3
            p.text("by ${Common.dm(dd)}", x + w - aw - 8f, cy, 12f, if (soon) 700 else 400, if (soon) pal.orange else pal.muted, Align.RIGHT, maxW = x + w - aw - 8f - tx)
        }
    }

    /** `txn_row`: dot, merchant + when, amount, category pill. */
    fun txnRow(p: Painter, t: JSONObject, x: Float, y: Float, w: Float) {
        val pal = p.p
        val cy = y + 15f
        val cat = Cats.byKey[t.optString("category")] ?: Cats.byKey["other"]!!
        val back = t.optString("kind") != "debit"
        val bill = t.optString("bill").takeIf { it.isNotEmpty() && it != "null" }
        val name = if (bill != null) Common.cap(bill) else t.optString("merchant")
        p.circle(x + 4.5f, cy, 4.5f, cat.first.toInt())
        // right side first so the name can ellipsize into the space left
        val pillW = p.pill(0f, 0f, cat.second, 0, 0, size = 10f, height = 20f, padX = 8f, draw = false).coerceAtLeast(34f)
        p.pill(x + w - pillW, cy - 10f, cat.second, Palette.alpha(cat.first, .13f), cat.first.toInt(), size = 10f, height = 20f, padX = 8f)
        val amt = Common.rs(t.optDouble("amount", 0.0))
        val amtColor = if (back) pal.muted.toInt() else pal.ink.toInt()
        val aw = p.text(amt, x + w - pillW - 8f, cy, 13f, 700, amtColor, Align.RIGHT)
        if (back) p.line(x + w - pillW - 8f - aw, cy, x + w - pillW - 8f, cy, amtColor, 1.2f)
        val nameMax = x + w - pillW - 8f - aw - 10f - (x + 16f)
        val nw = p.text(name, x + 16f, cy, 13f, 700, pal.ink, maxW = nameMax * .65f)
        val whenS = Common.whenLabel(t.optLong("ts")) + (if (back) " · " + t.optString("kind") else "")
        p.text(whenS, x + 16f + nw + 5f, cy, 12f, 400, pal.muted, maxW = nameMax - nw - 5f)
    }
}
