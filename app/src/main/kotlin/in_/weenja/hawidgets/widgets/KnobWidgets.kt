package in_.weenja.hawidgets.widgets

import android.content.Context
import android.graphics.RectF
import in_.weenja.hawidgets.Cfg
import in_.weenja.hawidgets.R
import in_.weenja.hawidgets.ha.Snapshot
import in_.weenja.hawidgets.ui.Align
import in_.weenja.hawidgets.ui.Painter
import org.json.JSONArray
import java.time.LocalDate
import kotlin.math.min
import kotlin.math.roundToInt

/** One usage / stat row under a knob. */
class KRow(val name: String, val color: Long, val pct: Float, val value: String, val short: String = name)

/**
 * Shared layout of the dashboard's `knob` template: header, ring gauge with the value inside,
 * two stats under it, rows at the bottom. Wide-and-short widgets put the ring on the left and
 * the stats + rows on the right.
 */
abstract class KnobWidget : CardWidget() {
    class Model(val title: String, val sub: String, val pct: Float, val value: String,
                val aVal: String, val aLab: String, val bVal: String, val bLab: String, val rows: List<KRow>, val openPath: String,
                val aShort: String = aLab, val bShort: String = bLab)

    abstract fun model(cfg: Cfg, snap: Snapshot): Model

    /** What to set up when none of the widget's sensors exist (drawn instead of a ring of zeros); null = fine. */
    open fun missing(cfg: Cfg, snap: Snapshot): Pair<Int, String>? = null

    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        val m = model(a.cfg, snap)
        p.card()
        val padX = 20f; val padY = 18f
        val innerW = p.w - padX * 2
        missing(a.cfg, snap)?.let { (icon, hint) ->
            val top = if (p.w < 200f) 0f else { p.header(m.title, "", padX, padY + 10f, innerW); padY + 26f }
            Common.empty(p, RectF(padX - 8f, top, p.w - padX + 8f, p.h - padY), icon, "No data yet", hint)
            spots.add(Hotspot(RectF(0f, 0f, p.w, p.h), a.configure(), "${m.title}: no data yet"))
            return spots
        }
        if (p.w < 200f) {
            // square tile: title on top, ring, the first stat under it
            p.text(m.title, padX - 4f, 24f, 13f, 700, pal.ink, maxW = innerW + 8f)
            val r = (min(p.w, p.h - 70f) / 2 - 10f).coerceAtLeast(28f)
            p.knob(p.w / 2, (36f + (p.h - 30f)) / 2, r, m.pct, m.value, valueSize = r * .48f)
            p.text("${m.aVal} ${m.aShort}", p.w / 2, p.h - 16f, 11f, 500, pal.muted, Align.CENTER, maxW = innerW + 8f)
            spots.add(Hotspot(RectF(0f, 0f, p.w, p.h), a.open(m.openPath)))
            return spots
        }
        var y = padY + 10f
        val badge = Common.statusBadge(ctx, p, a, snap, p.w - padX, y)?.also { spots.add(it) }
        p.header(m.title, if (badge == null) m.sub else "", padX, y, innerW)
        y += 20f
        val bottom = p.h - padY
        val innerH = bottom - y
        val sideBySide = innerH < 190f
        if (sideBySide) {
            // ring left, stats + rows right
            val r = (min(innerH, innerW * .45f) / 2 - 8f).coerceAtLeast(30f)
            val cx = padX + r + 8f; val cy = y + innerH / 2
            p.knob(cx, cy, r, m.pct, m.value, valueSize = r * .5f)
            val x = cx + r + 18f; val w = p.w - padX - x
            var yy = y + 4f
            p.fitText(m.aVal, x, yy + 10f, 19f, 13f, 700, pal.ink.toInt(), Align.LEFT, w / 2 - 6f)
            p.fitText(m.bVal, x + w / 2, yy + 10f, 19f, 13f, 700, pal.ink.toInt(), Align.LEFT, w / 2 - 6f)
            p.text(m.aShort, x, yy + 28f, 11f, 400, pal.muted, maxW = w / 2 - 6f)
            p.text(m.bShort, x + w / 2, yy + 28f, 11f, 400, pal.muted, maxW = w / 2 - 6f)
            yy += 50f
            for (row in m.rows) {
                if (yy + 12f > bottom) break
                p.usageRow(x, yy, w, row.short, row.color.toInt(), row.pct, row.value, barW = (w * .22f).coerceIn(28f, 60f))
                yy += 22f
            }
        } else {
            val rowsH = m.rows.size * 24f
            val statsH = 52f
            val r = ((innerH - statsH - rowsH - 16f) / 2 - 6f).coerceIn(44f, 108f)
            val cx = p.w / 2; val cy = y + r + 10f
            p.knob(cx, cy, r, m.pct, m.value)
            y = cy + r + 14f
            p.kstats(padX, y, innerW, m.aVal, m.aLab, m.bVal, m.bLab)
            y += statsH + 4f
            for (row in m.rows) {
                if (y + 12f > bottom) break
                p.usageRow(padX, y + 6f, innerW, row.name, row.color.toInt(), row.pct, row.value)
                y += 24f
            }
        }
        spots.add(0, Hotspot(RectF(0f, 0f, p.w, p.h), a.open(m.openPath)))
        return spots
    }
}

/** Screen time today: phone + Mac + PC + TV (packages/digital_usage.yaml). */
open class ScreenTimeWidget : KnobWidget() {
    override fun model(cfg: Cfg, snap: Snapshot): Model {
        val total = snap.num(cfg["st_today"])
        val week = snap.num(cfg["st_week"])
        fun h(key: String) = snap.num(cfg[key])
        return Model("Screen time", "today · ${week.roundToInt()}h this week", (total / 10.0).toFloat().coerceIn(0f, 1f), "%.1fh".format(total),
            "%.1fh".format(h("st_tv")), "TV", "%.1fh".format(h("st_phone")), "Phone",
            listOf(KRow("Mac", 0xffff9f2e, (h("st_mac") / 5.0).toFloat(), "%.1fh".format(h("st_mac"))),
                   KRow("Gaming PC", 0xffb5e21c, (h("pc_usage") / 5.0).toFloat(), "%.1fh".format(h("pc_usage")), "PC")),
            cfg["path_home"])
    }
}

/** Garmin: body battery ring, steps + sleep, resting HR / stress / readiness rows. */
open class BodyWidget : KnobWidget() {
    override fun missing(cfg: Cfg, snap: Snapshot): Pair<Int, String>? {
        val pre = cfg["garmin_prefix"]
        return if (listOf("body_battery", "steps", "sleep_score").none { snap[pre + it] != null })
            R.drawable.ic_heart_pulse to "Needs Garmin Connect" else null
    }

    override fun model(cfg: Cfg, snap: Snapshot): Model {
        val pre = cfg["garmin_prefix"]
        fun n(id: String) = snap.num(pre + id)
        val bb = n("body_battery"); val steps = n("steps"); val goal = n("daily_step_goal").takeIf { it > 0 } ?: 8000.0
        val sleep = n("sleep_score"); val dur = n("sleep_duration")
        val synced = snap[pre + "last_synced"]?.state?.takeIf { it.length > 15 }?.let { try { java.time.Instant.parse(it).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) } catch (e: Exception) { null } }
        val chg = n("body_battery_charged").roundToInt(); val drn = n("body_battery_drained").roundToInt()
        val sub = if (chg > 0 || drn > 0) "+$chg −$drn today" else (synced?.let { "synced $it" } ?: "body battery")
        return Model("Body", sub, (bb / 100.0).toFloat(), "${bb.roundToInt()}",
            "%,d".format(steps.roundToInt()), "steps · goal %,d".format(goal.roundToInt()),
            if (sleep > 0) "${sleep.roundToInt()}" else "—", if (dur > 0) "sleep · %.1fh".format(dur) else "sleep score",
            listOf(KRow("Resting HR", 0xffff4fa3, ((n("resting_heart_rate") - 40) / 60.0).toFloat().coerceIn(0f, 1f), "${n("resting_heart_rate").roundToInt()} bpm", "Rest HR"),
                   KRow("Stress", 0xffff9f2e, (n("average_stress_level") / 100.0).toFloat(), "${n("average_stress_level").roundToInt()}"),
                   KRow("Readiness", 0xff3fe3ff, (n("training_readiness") / 100.0).toFloat(), "${n("training_readiness").roundToInt()}", "Ready"),
                   KRow("Steps", 0xffb5e21c, (steps / goal).toFloat().coerceIn(0f, 1f), "${(100 * steps / goal).roundToInt()}%")),
            cfg["path_fitness"], "steps", "sleep")
    }
}

/** Budget used this month, what is left per day, today / this week from the spend feed. */
open class BudgetWidget : KnobWidget() {
    override fun model(cfg: Cfg, snap: Snapshot): Model {
        val spent = snap.num(cfg["spent_month"])
        val budget = snap.num(cfg["budget_month"]).takeIf { it > 0 } ?: 60000.0
        val pct = (spent / budget).toFloat()
        val today = LocalDate.now()
        val daysLeft = (today.lengthOfMonth() - today.dayOfMonth + 1).coerceAtLeast(1)
        val left = budget - spent
        val days = snap[cfg["spend_feed"]]?.attrs?.optJSONArray("days") ?: JSONArray()
        var todaySpend = 0.0; var week = 0.0
        for (i in 0 until days.length()) {
            val d = days.getJSONObject(i); val t = d.optDouble("total", 0.0)
            if (d.optString("date") == today.toString()) todaySpend = t
            if (i >= days.length() - 7) week += t
        }
        val bills = snap.num(cfg["bills_14d"])
        return Model("Budget", "${Common.rs(spent)} of ${Common.rs(budget)}", pct.coerceIn(0f, 1f), "${(pct * 100).roundToInt()}%",
            Common.rs(left), if (left >= 0) "left this month" else "over budget",
            Common.rs(left / daysLeft), "per day · $daysLeft days",
            listOf(KRow("Today", 0xffff9f2e, (todaySpend / (budget / today.lengthOfMonth() * 2)).toFloat().coerceIn(0f, 1f), Common.rs(todaySpend)),
                   KRow("Last 7 days", 0xff3fe3ff, (week / (budget / 4)).toFloat().coerceIn(0f, 1f), Common.rs(week), "7 days"),
                   KRow("Bills due 14d", 0xffff4fa3, (bills / budget * 4).toFloat().coerceIn(0f, 1f), Common.rs(bills), "Bills 14d")),
            cfg["path_home"], if (left >= 0) "left" else "over", "per day")
    }
}

class ScreenTimeSmallWidget : ScreenTimeWidget()
class BodySmallWidget : BodyWidget()
class BudgetSmallWidget : BudgetWidget()
