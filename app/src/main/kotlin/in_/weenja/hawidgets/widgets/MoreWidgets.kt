package in_.weenja.hawidgets.widgets

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import in_.weenja.hawidgets.HomeStore
import in_.weenja.hawidgets.R
import in_.weenja.hawidgets.core.EntityState
import in_.weenja.hawidgets.core.Extra
import in_.weenja.hawidgets.core.Planner
import in_.weenja.hawidgets.core.Rules
import in_.weenja.hawidgets.core.Time
import in_.weenja.hawidgets.ha.Snapshot
import in_.weenja.hawidgets.ui.Align
import in_.weenja.hawidgets.ui.Icons
import in_.weenja.hawidgets.ui.Painter
import in_.weenja.hawidgets.ui.Palette
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

private const val PAD_X = 18f
private const val PAD_Y = 16f

/** Card + header; returns the y under the header and the status badge hotspot (if any). */
private fun header(ctx: Context, p: Painter, a: Actions, snap: Snapshot, title: String, meta: String, spots: MutableList<Hotspot>): Float {
    p.card()
    val y = PAD_Y + 10f
    val badge = Common.statusBadge(ctx, p, a, snap, p.w - PAD_X, y)?.also { spots.add(it) }
    p.header(a.cfg.title.ifBlank { title }, if (badge == null) meta else "", PAD_X, y, p.w - PAD_X * 2)
    return y + 20f
}

private fun localDay(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()

/** "Today", "Tomorrow", "Friday", "12 Oct". */
private fun dayLabel(d: LocalDate): String {
    val today = LocalDate.now()
    return when {
        d == today -> "Today"
        d == today.plusDays(1) -> "Tomorrow"
        d.isBefore(today.plusDays(7)) && d.isAfter(today) -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        else -> "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}"
    }
}

private fun offsetMin(ms: Long) = TimeZone.getDefault().getOffset(ms) / 60_000

/** Everyone in Home Assistant and where they are. */
class PeopleWidget : CardWidget() {
    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        val ids = a.cfg.slot("items").ifEmpty { snap.byDomain("person").map { it.id } }.take(6)
        val all = ids.mapNotNull { snap.core(it) }
        val home = all.count { it.state == "home" }
        var y = header(ctx, p, a, snap, "Who's home", if (all.isEmpty()) "" else if (home == 0) "nobody home" else "$home home", spots)
        if (all.isEmpty()) { Common.empty(p, RectF(PAD_X, y, p.w - PAD_X, p.h - PAD_Y), R.drawable.ic_account, "No people yet", "Add people in Home Assistant → Settings → People"); return spots }
        val now = System.currentTimeMillis()
        val area = RectF(PAD_X, y + 4f, p.w - PAD_X, p.h - PAD_Y)
        val cols = min(all.size, ((area.width() + 8f) / 74f).toInt().coerceAtLeast(1))
        // each row needs an avatar and a name under it (~52 dp): show as many people as fit
        val maxRows = ((area.height() + 8f) / 60f).toInt().coerceAtLeast(1)
        val people = all.take(cols * maxRows)
        val rows = (people.size + cols - 1) / cols
        val cw = (area.width() - 8f * (cols - 1)) / cols
        val ch = ((area.height() - 8f * (rows - 1)) / rows).coerceAtMost(110f)
        people.forEachIndexed { i, e ->
            val r = RectF(area.left + (i % cols) * (cw + 8f), area.top + (i / cols) * (ch + 8f), area.left + (i % cols) * (cw + 8f) + cw, area.top + (i / cols) * (ch + 8f) + ch)
            val isHome = e.state == "home"
            val col = if (isHome) pal.lime else if (e.state == "not_home") pal.muted else pal.cyan
            val av = min(r.width() * .56f, r.height() - 40f).coerceIn(26f, 64f)
            val cx = r.centerX(); val cy = r.top + av / 2 + 2f
            val pic = Common.cachedArt(ctx, e.attrString("entity_picture"))
            if (pic != null) p.drawBitmap(pic, RectF(cx - av / 2, cy - av / 2, cx + av / 2, cy + av / 2), av / 2)
            else {
                p.circle(cx, cy, av / 2, pal.mix(col, .3f))
                p.text(e.name.split(' ').mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString(""), cx, cy, av * .38f, 700, col.toInt(), Align.CENTER)
            }
            p.circle(cx + av * .36f, cy + av * .36f, 6f, pal.card.toInt()); p.circle(cx + av * .36f, cy + av * .36f, 4.5f, col.toInt())
            p.text(e.name.substringBefore(' '), cx, cy + av / 2 + 11f, 12f, 700, pal.ink, Align.CENTER, maxW = r.width())
            val since = Time.parseMillis(e.lastChanged)?.let { " · " + Time.duration(now - it) } ?: ""
            if (r.height() >= av + 36f) p.text(Rules.stateLabel(e) + since, cx, cy + av / 2 + 25f, 10f, 500, pal.muted, Align.CENTER, maxW = r.width())
            spots.add(Hotspot(r, a.moreInfo(e.id), "${e.name}, ${Rules.stateLabel(e)}"))
        }
        return spots
    }
}

/** The latest snapshot of a camera or doorbell. Refreshed with every widget refresh. */
class CameraWidget : CardWidget() {
    override val freshness = false

    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        val id = a.cfg.one("camera") ?: snap.byDomain("camera").firstOrNull()?.id
        val e = snap.core(id)
        val full = RectF(0f, 0f, p.w, p.h)
        p.card(full)
        val img = id?.let { Common.cachedArt(ctx, CAMERA_KEY + it) }
        if (img != null) p.drawBitmap(img, full, 22f)
        else {
            p.icon(R.drawable.ic_cctv, p.w / 2, p.h / 2 - 8f, 34f, pal.muted.toInt())
            p.text(if (e == null) "Pick a camera" else if (snap.demo) "Snapshot appears after login" else "Waiting for a snapshot…", p.w / 2, p.h / 2 + 22f, 12f, 500, pal.muted, Align.CENTER, maxW = p.w - 24f)
        }
        // name + time pill top-left, refresh key top-right
        val label = (a.cfg.title.ifBlank { e?.name ?: "Camera" }) + (if (!snap.demo && snap.fetchedAt > 0) " · " + Time.clock(snap.fetchedAt, offsetMin(snap.fetchedAt)) else "")
        val pw = p.pill(0f, 0f, label, 0, 0, size = 11f, draw = false, maxW = p.w - 70f)
        p.pill(12f, 12f, label, Color.argb(150, 0, 0, 0), Color.WHITE, size = 11f, maxW = p.w - 70f)
        val rr = RectF(p.w - 12f - 32f, 10f, p.w - 12f, 42f)
        p.rrect(rr, 16f, Color.argb(150, 0, 0, 0)); p.icon(R.drawable.ic_refresh, rr.centerX(), rr.centerY(), 18f, Color.WHITE)
        spots.add(Hotspot(full, if (e != null) a.moreInfo(e.id) else a.configure(), label))
        spots.add(Hotspot(RectF(rr.left - 8f, 0f, p.w, rr.bottom + 8f), a.refresh(), "Refresh snapshot"))
        if (pw > 0f) Common.statusBadge(ctx, p, a, snap, p.w - 52f, 26f)?.let { spots.add(it) }
        return spots
    }

    companion object { const val CAMERA_KEY = "camera:" }
}

/** Robot vacuum: state, battery, start / pause / dock. */
class VacuumWidget : CardWidget() {
    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        val id = a.cfg.one("vacuum") ?: snap.byDomain("vacuum").firstOrNull()?.id
        val e = snap.core(id)
        val batt = e?.attrDouble("battery_level")
        var y = header(ctx, p, a, snap, e?.name ?: "Vacuum", batt?.let { "battery ${it.roundToInt()}%" } ?: "", spots)
        if (e == null) { Common.empty(p, RectF(PAD_X, y, p.w - PAD_X, p.h - PAD_Y), R.drawable.ic_robot_vacuum, "No vacuum found", "Tap to pick one"); spots.add(Hotspot(RectF(0f, 0f, p.w, p.h), a.configure())); return spots }
        val cleaning = e.state == "cleaning"
        val bottom = p.h - PAD_Y
        val btnH = 32f
        val heroH = (bottom - y - btnH - 10f).coerceIn(40f, 90f)
        val tile = RectF(PAD_X, y, PAD_X + heroH, y + heroH)
        if (cleaning) p.gradient(tile, 18f, pal.gradLime, 160f, shadow = 8f) else p.rrect(tile, 18f, pal.surface2)
        p.icon(R.drawable.ic_robot_vacuum, tile.centerX(), tile.centerY(), heroH * .52f, if (cleaning) pal.inkOnLime.toInt() else pal.lime.toInt(), shadow = cleaning)
        val tx = tile.right + 14f
        p.text(Rules.pretty(e.state), tx, tile.centerY() - 10f, 18f, 700, pal.ink, maxW = p.w - PAD_X - tx)
        val sub = listOfNotNull(e.attrString("fan_speed")?.let { "suction $it" }, e.attrString("status")).joinToString(" · ")
        if (sub.isNotEmpty()) p.text(sub, tx, tile.centerY() + 10f, 11f, 500, pal.muted, maxW = p.w - PAD_X - tx)
        batt?.let { p.bar(RectF(tx, tile.centerY() + 22f, p.w - PAD_X, tile.centerY() + 26f), (it / 100).toFloat(), if (it < 20) pal.pink.toInt() else pal.lime.toInt()) }
        spots.add(Hotspot(tile, a.tap(e), "${e.name}, ${e.state}"))
        val by = bottom - btnH
        val t = JSONObject().put("entity_id", e.id)
        val btns = listOf(Triple(if (cleaning) "Pause" else "Start", if (cleaning) R.drawable.ic_pause else R.drawable.ic_play, a.service("vacuum", if (cleaning) "pause" else "start", t, e.id to if (cleaning) "paused" else "cleaning")),
            Triple("Dock", R.drawable.ic_home, a.service("vacuum", "return_to_base", t, e.id to "returning")),
            Triple("Locate", R.drawable.ic_map_marker, a.service("vacuum", "locate", t)))
        val bw = (p.w - PAD_X * 2 - 16f) / 3
        btns.forEachIndexed { i, (lab, ic, pi) ->
            val r = RectF(PAD_X + i * (bw + 8f), by, PAD_X + i * (bw + 8f) + bw, by + btnH)
            p.chip(r, lab, if (bw >= 80f) ic else null, pal.lime.toInt(), size = 12f, pad = 8f); spots.add(Hotspot(r, pi, lab))
        }
        return spots
    }
}

/** Blinds, shades and the garage: position with open / stop / close keys. Opening a garage or door needs a second tap. */
class CoversWidget : CardWidget() {
    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        val ids = a.cfg.slot("items").ifEmpty { snap.byDomain("cover").map { it.id } }.take(6)
        val es = ids.map { it to snap.core(it) }
        val open = es.count { it.second?.state == "open" }
        var y = header(ctx, p, a, snap, "Blinds & garage", if (ids.isEmpty()) "" else if (open == 0) "all closed" else "$open open", spots)
        if (ids.isEmpty()) { Common.empty(p, RectF(PAD_X, y, p.w - PAD_X, p.h - PAD_Y), R.drawable.ic_blinds, "No covers found", "Tap to pick blinds or a garage door"); spots.add(Hotspot(RectF(0f, 0f, p.w, p.h), a.configure())); return spots }
        val bottom = p.h - PAD_Y
        val rowH = ((bottom - y) / es.size).coerceIn(38f, 58f)
        for ((id, e) in es) {
            if (y + 34f > bottom + 2f) break
            val cy = y + rowH / 2
            if (e == null) { p.text("$id not found", PAD_X, cy, 12f, 500, pal.error.toInt()); y += rowH; continue }
            val sensitive = e.deviceClass in setOf("garage", "door", "gate")
            val active = Rules.isActive(e)
            p.iconBox(PAD_X, cy - 15f, 30f, 10f, Icons.of(Rules.icon(e)), (if (active) pal.lime else pal.muted).toInt(), 18f)
            val kw = 32f
            val keysX = p.w - PAD_X - kw * 3 - 12f
            p.text(e.name, PAD_X + 40f, cy - 7f, 13f, 700, pal.ink, maxW = keysX - PAD_X - 48f)
            val pos = e.attrDouble("current_position")?.roundToInt()
            val armedOpen = sensitive && a.isArmed("tap:${e.id}:open_cover")
            p.text(if (armedOpen) "tap ▲ again to open" else listOfNotNull(Rules.pretty(e.state), pos?.takeIf { e.state == "open" && it in 1..99 }?.let { "$it%" }).joinToString(" · "),
                PAD_X + 40f, cy + 9f, 11f, 500, if (armedOpen) pal.pink.toInt() else pal.muted.toInt(), maxW = keysX - PAD_X - 48f)
            val t = JSONObject().put("entity_id", e.id)
            val keys = listOf(Triple(R.drawable.ic_arrow_up, if (sensitive) a.armed("tap:${e.id}:open_cover", "cover", "open_cover", t) else a.service("cover", "open_cover", t, e.id to "opening"), "Open"),
                Triple(R.drawable.ic_stop, a.service("cover", "stop_cover", t), "Stop"),
                Triple(R.drawable.ic_arrow_down, a.service("cover", "close_cover", t, e.id to "closing"), "Close"))
            keys.forEachIndexed { i, (ic, pi, lab) ->
                val r = RectF(keysX + i * (kw + 6f), cy - 16f, keysX + i * (kw + 6f) + kw, cy + 16f)
                p.rrect(r, 16f, if (i == 0 && armedOpen) pal.mix(pal.pink, .35f) else pal.surface2.toInt()); p.icon(ic, r.centerX(), r.centerY(), 18f, pal.ink.toInt())
                spots.add(Hotspot(r, pi, "$lab ${e.name}"))
            }
            y += rowH
        }
        return spots
    }
}

/** Washer, dryer, dishwasher and timer countdowns: remaining time and the end time. */
class CountdownWidget : CardWidget() {
    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        val home = HomeStore.home(ctx, snap)
        val ids = a.cfg.slot("items").ifEmpty { Planner.countdowns(home) }.take(4)
        val now = System.currentTimeMillis()
        class Row(val e: EntityState, val end: Long?, val total: Long?)
        val rows = ids.mapNotNull { snap.core(it) }.map { e ->
            when (e.domain) {
                "timer" -> Row(e, if (e.state == "active") Time.parseMillis(e.attrString("finishes_at")) else null, e.attrString("duration")?.let { hms(it) })
                else -> Row(e, Time.parseMillis(e.state), null)
            }
        }.sortedBy { it.end?.takeIf { t -> t > now } ?: Long.MAX_VALUE }
        val running = rows.count { (it.end ?: 0) > now }
        var y = header(ctx, p, a, snap, "Timers", if (rows.isEmpty()) "" else if (running == 0) "nothing running" else "$running running", spots)
        if (rows.isEmpty()) { Common.empty(p, RectF(PAD_X, y, p.w - PAD_X, p.h - PAD_Y), R.drawable.ic_timer_outline, "No timers found", "Tap to pick timers or end-time sensors"); spots.add(Hotspot(RectF(0f, 0f, p.w, p.h), a.configure())); return spots }
        val bottom = p.h - PAD_Y
        val rowH = ((bottom - y) / rows.size).coerceIn(40f, 64f)
        val off = offsetMin(now)
        for (r in rows) {
            if (y + 36f > bottom + 2f) break
            val cy = y + rowH / 2
            val left = r.end?.let { it - now }?.takeIf { it > 0 }
            val remainingPaused = if (r.e.domain == "timer" && r.e.state == "paused") r.e.attrString("remaining")?.let { hms(it) } else null
            val icon = when { Regex("wash").containsMatchIn(r.e.id) -> R.drawable.ic_washing_machine; Regex("dry").containsMatchIn(r.e.id) -> R.drawable.ic_tumble_dryer
                Regex("dish").containsMatchIn(r.e.id) -> R.drawable.ic_dishwasher; else -> R.drawable.ic_timer_outline }
            val col = if (left != null) pal.orange else pal.muted
            p.iconBox(PAD_X, cy - 15f, 30f, 10f, icon, col.toInt(), 18f)
            val big = when { left != null -> Time.duration(left); remainingPaused != null -> Time.duration(remainingPaused) + " paused"; else -> "done" }
            val bw = p.text(big, p.w - PAD_X, cy - 6f, 16f, 700, if (left != null) pal.ink.toInt() else pal.muted.toInt(), Align.RIGHT)
            p.text(r.e.name.replace(Regex("(?i) (end time|finish(es)?|remaining.*)$"), ""), PAD_X + 40f, cy - 7f, 13f, 700, pal.ink, maxW = p.w - PAD_X * 2 - 50f - bw)
            p.text(r.end?.let { if (it > now) "ends ${Time.clock(it, off)}" else "finished ${Time.clock(it, off)}" } ?: Rules.pretty(r.e.state), PAD_X + 40f, cy + 9f, 11f, 500, pal.muted, maxW = p.w - PAD_X * 2 - 50f)
            if (left != null && r.total != null && r.total > 0) p.bar(RectF(p.w - PAD_X - 70f, cy + 8f, p.w - PAD_X, cy + 11f), 1f - left.toFloat() / r.total, pal.orange.toInt())
            spots.add(Hotspot(RectF(0f, y, p.w, y + rowH), a.moreInfo(r.e.id), "${r.e.name}, $big"))
            y += rowH
        }
        return spots
    }

    private fun hms(s: String): Long? {
        val parts = s.split(':').mapNotNull { it.toDoubleOrNull() }
        if (parts.size != 3) return null
        return ((parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000).toLong()
    }
}

/** The next events of your calendars (REST /api/calendars/…). */
class CalendarWidget : CardWidget() {
    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        val ids = a.cfg.slot("items").ifEmpty { snap.byDomain("calendar").map { it.id } }.take(6)
        val colors = listOf(pal.cyan, pal.orange, pal.pink, pal.lime, pal.purple, pal.muted)
        val now = System.currentTimeMillis()
        class Ev(val title: String, val start: Long, val end: Long?, val allDay: Boolean, val color: Long, val cal: String)
        val events = ids.flatMapIndexed { i, id ->
            val e = snap.core(id) ?: return@flatMapIndexed emptyList()
            e.attr(Extra.EVENTS)?.list?.mapNotNull { ev ->
                val startS = ev.str("start") ?: return@mapNotNull null
                val allDay = ev.flag("all_day") == true || startS.length == 10
                val start = if (startS.length == 10) Time.parseMillis(startS + "T00:00:00", offsetMin(now)) else Time.parseMillis(startS)
                val end = ev.str("end")?.let { if (it.length == 10) Time.parseMillis(it + "T00:00:00", offsetMin(now)) else Time.parseMillis(it) }
                start?.let { Ev(ev.str("summary") ?: "(no title)", it, end, allDay, colors[i % colors.size], e.name) }
            } ?: emptyList()
        }.filter { (it.end ?: it.start) >= now }.sortedBy { it.start }
        var y = header(ctx, p, a, snap, "Calendar", LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE d MMM", Locale.getDefault())), spots)
        spots.add(0, Hotspot(RectF(0f, 0f, p.w, p.h), a.open("/calendar"), "Calendar"))
        if (ids.isEmpty()) { Common.empty(p, RectF(PAD_X, y, p.w - PAD_X, p.h - PAD_Y), R.drawable.ic_calendar, "No calendars found", "Tap to pick calendars"); spots.add(Hotspot(RectF(0f, 0f, p.w, p.h), a.configure())); return spots }
        if (events.isEmpty()) { Common.empty(p, RectF(PAD_X, y, p.w - PAD_X, p.h - PAD_Y), R.drawable.ic_calendar_clock, "Nothing coming up", "in the next two weeks"); return spots }
        val bottom = p.h - PAD_Y
        val off = offsetMin(now)
        var lastDay: LocalDate? = null
        for (ev in events) {
            val day = localDay(ev.start).let { if (it.isBefore(LocalDate.now())) LocalDate.now() else it }
            val needDay = day != lastDay
            if (y + (if (needDay) 18f else 0f) + 30f > bottom + 2f) break
            if (needDay) { p.tag(dayLabel(day), PAD_X, y + 8f); y += 18f; lastDay = day }
            p.rrect(RectF(PAD_X, y + 3f, PAD_X + 3f, y + 27f), 1.5f, ev.color)
            val time = if (ev.allDay) "all day" else Time.clock(ev.start, off) + (ev.end?.let { "–" + Time.clock(it, off) } ?: "")
            p.text(ev.title, PAD_X + 12f, y + 9f, 13f, 700, pal.ink, maxW = p.w - PAD_X * 2 - 12f)
            p.text("$time · ${ev.cal}", PAD_X + 12f, y + 23f, 11f, 500, pal.muted, maxW = p.w - PAD_X * 2 - 12f)
            y += 32f
        }
        return spots
    }
}

/** Low batteries first, then devices that dropped off the network. */
class BatteryWidget : CardWidget() {
    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        val home = HomeStore.home(ctx, snap)
        val batts = Planner.batteries(home).mapNotNull { snap.core(it) }
        val offline = Planner.offlineDevices(home)
        val low = batts.count { (it.number ?: 100.0) < 20 }
        var y = header(ctx, p, a, snap, "Batteries & offline", listOfNotNull(low.takeIf { it > 0 }?.let { "$it low" }, offline.size.takeIf { it > 0 }?.let { "$it offline" }).joinToString(" · ").ifEmpty { "all good" }, spots)
        val bottom = p.h - PAD_Y
        if (batts.isEmpty() && offline.isEmpty()) { Common.empty(p, RectF(PAD_X, y, p.w - PAD_X, bottom), R.drawable.ic_battery, "Nothing to report", "No battery sensors found"); return spots }
        val rowH = 26f
        val offRows = if (offline.isEmpty()) 0 else min(offline.size, 3)
        val battRows = ((bottom - y - (if (offRows > 0) 20f + offRows * 22f else 0f)) / rowH).toInt().coerceAtLeast(1)
        for (e in batts.take(battRows)) {
            if (y + rowH > bottom + 2f) break
            val lvl = e.number ?: 0.0
            val col = when { lvl < 20 -> pal.pink; lvl < 40 -> pal.orange; else -> pal.lime }
            val cy = y + rowH / 2
            p.icon(Icons.of(Rules.batteryIcon(lvl)), PAD_X + 9f, cy, 18f, col.toInt())
            val name = e.name.replace(Regex("(?i)\\s*battery( level)?$"), "")
            p.text("${lvl.roundToInt()}%", p.w - PAD_X, cy, 12f, 700, if (lvl < 20) pal.pink.toInt() else pal.ink.toInt(), Align.RIGHT)
            p.bar(RectF(p.w - PAD_X - 40f - 60f, cy - 2f, p.w - PAD_X - 40f, cy + 2f), (lvl / 100).toFloat(), col.toInt())
            val nameW = p.w - PAD_X * 2 - 24f - 110f
            p.text(Common.keepTail(p, name, nameW, 12.5f, 500), PAD_X + 24f, cy, 12.5f, 500, pal.ink, maxW = nameW)
            spots.add(Hotspot(RectF(0f, y, p.w, y + rowH), a.moreInfo(e.id), "$name, ${lvl.roundToInt()}%"))
            y += rowH
        }
        if (offRows > 0 && y + 20f + 22f <= bottom + 2f) {
            y += 4f; p.tag("Offline", PAD_X, y + 7f); y += 16f
            for (name in offline.take(offRows)) {
                if (y + 22f > bottom + 2f) break
                p.icon(R.drawable.ic_wifi_off, PAD_X + 9f, y + 11f, 15f, pal.error.toInt())
                p.text(Common.keepTail(p, name, p.w - PAD_X * 2 - 24f, 12.5f, 500), PAD_X + 24f, y + 11f, 12.5f, 500, pal.ink, maxW = p.w - PAD_X * 2 - 24f)
                y += 22f
            }
        }
        return spots
    }
}

/** Next waste collection per bin, from a waste collection sensor (date / days) or a calendar. */
class BinsWidget : CardWidget() {
    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        val home = HomeStore.home(ctx, snap)
        val ids = a.cfg.slot("items").ifEmpty { Planner.bins(home) }.take(4)
        val now = System.currentTimeMillis()
        class Bin(val e: EntityState, val day: LocalDate?)
        val bins = ids.mapNotNull { snap.core(it) }.map { e ->
            val d: LocalDate? = when {
                e.domain == "calendar" -> e.attr(Extra.EVENTS)?.list?.firstOrNull()?.str("start")?.let { s -> Time.parseMillis(if (s.length == 10) s + "T12:00:00" else s, offsetMin(now))?.let { localDay(it) } }
                    ?: e.attrString("start_time")?.let { s -> Time.parseMillis(s, offsetMin(now))?.let { localDay(it) } }
                e.state.length >= 10 && e.state[4] == '-' -> Time.parseMillis(if (e.state.length == 10) e.state + "T12:00:00" else e.state, offsetMin(now))?.let { localDay(it) }
                e.number != null -> LocalDate.now().plusDays(e.number!!.toLong())
                else -> e.attrString("next_date")?.let { s -> Time.parseMillis(s, offsetMin(now))?.let { localDay(it) } }
            }
            Bin(e, d)
        }.sortedBy { it.day ?: LocalDate.MAX }
        val next = bins.firstOrNull()?.day
        var y = header(ctx, p, a, snap, "Bin day", next?.let { dayLabel(it) } ?: "", spots)
        if (bins.isEmpty()) { Common.empty(p, RectF(PAD_X, y, p.w - PAD_X, p.h - PAD_Y), R.drawable.ic_trash_can, "No collection sensors found", "Tap to pick a waste collection sensor"); spots.add(Hotspot(RectF(0f, 0f, p.w, p.h), a.configure())); return spots }
        val area = RectF(PAD_X, y + 2f, p.w - PAD_X, p.h - PAD_Y)
        val n = bins.size
        val cw = (area.width() - 8f * (n - 1)) / n
        bins.forEachIndexed { i, b ->
            val r = RectF(area.left + i * (cw + 8f), area.top, area.left + i * (cw + 8f) + cw, area.bottom.coerceAtMost(area.top + 110f))
            val soon = b.day != null && !b.day.isAfter(LocalDate.now().plusDays(1))
            val recycling = Regex("(?i)recycl|paper|plastic|glass|pmd").containsMatchIn(b.e.id + b.e.name)
            val green = Regex("(?i)garden|green|compost|organic|bio").containsMatchIn(b.e.id + b.e.name)
            val col = when { recycling -> pal.cyan; green -> pal.lime; else -> pal.orange }
            if (soon) p.gradient(r, 16f, pal.grad(if (recycling) in_.weenja.hawidgets.core.Accent.CYAN else if (green) in_.weenja.hawidgets.core.Accent.LIME else in_.weenja.hawidgets.core.Accent.ORANGE), 160f, shadow = 6f)
            else p.rrect(r, 16f, pal.surface2)
            val ink = if (soon) (if (recycling) pal.inkOnCyan else if (green) pal.inkOnLime else pal.inkOnOrange).toInt() else pal.ink.toInt()
            p.icon(if (recycling) R.drawable.ic_recycle else R.drawable.ic_trash_can, r.left + 18f, r.top + 20f, 20f, if (soon) ink else col.toInt())
            val name = b.e.name.replace(Regex("(?i)\\s*(collection|pickup|pick-up|bin)$"), "")
            p.text(name, r.left + 10f, r.bottom - 30f, 12f, 700, ink, maxW = r.width() - 16f)
            p.text(b.day?.let { dayLabel(it) } ?: Rules.stateLabel(b.e), r.left + 10f, r.bottom - 14f, 11f, 600, Palette.alpha(ink, .8f), maxW = r.width() - 16f)
            spots.add(Hotspot(r, a.moreInfo(b.e.id), "$name, ${b.day?.let { dayLabel(it) } ?: ""}"))
        }
        return spots
    }
}

/** An electric car: charge level, range, charging state and charger power. */
class EvWidget : CardWidget() {
    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        val auto = if (a.cfg.one("battery") == null) Planner.ev(HomeStore.home(ctx, snap)) else null
        val c = if (auto != null) in_.weenja.hawidgets.Cfg(ctx, auto) else a.cfg
        val batt = snap.core(c.one("battery"))
        val charging = snap.core(c.one("charging"))
        val range = snap.core(c.one("range"))
        val power = snap.core(c.one("power"))
        val isCharging = charging?.let { it.isOn || it.state == "charging" } == true
        var y = header(ctx, p, a, snap, auto?.title ?: "Car", if (isCharging) "charging" else charging?.let { Rules.stateLabel(it) } ?: "", spots)
        if (batt == null) { Common.empty(p, RectF(PAD_X, y, p.w - PAD_X, p.h - PAD_Y), R.drawable.ic_car_electric, "No car battery found", "Tap to pick the car's battery sensor"); spots.add(Hotspot(RectF(0f, 0f, p.w, p.h), a.configure())); return spots }
        val lvl = batt.number ?: 0.0
        val bottom = p.h - PAD_Y
        val col = if (lvl < 20) pal.pink else if (isCharging) pal.lime else pal.cyan
        val big = "${lvl.roundToInt()}%"
        val bw = p.text(big, PAD_X, y + 22f, 34f, 700, pal.ink, letterSpacing = -.03f)
        p.icon(if (isCharging) R.drawable.ic_ev_station else R.drawable.ic_car_electric, PAD_X + bw + 22f, y + 20f, 24f, col.toInt())
        val lines = listOfNotNull(range?.let { "${Rules.stateLabel(it)} range" }, power?.takeIf { isCharging }?.let { "${Rules.stateLabel(it)} charging" })
        lines.forEachIndexed { i, s -> p.text(s, p.w - PAD_X, y + 10f + i * 18f, 12f, if (i == 0) 700 else 500, if (i == 0) pal.ink.toInt() else pal.muted.toInt(), Align.RIGHT, maxW = p.w - PAD_X * 2 - bw - 50f) }
        y += 48f
        if (y + 14f <= bottom) {
            val r = RectF(PAD_X, y, p.w - PAD_X, y + 14f)
            p.bar(r, (lvl / 100).toFloat(), if (isCharging) pal.gradLime else if (lvl < 20) pal.gradPink else pal.gradCyan)
            // 80 % mark: where most people stop charging
            p.line(r.left + r.width() * .8f, r.top - 3f, r.left + r.width() * .8f, r.bottom + 3f, pal.muted.toInt(), 1f, dashed = true)
        }
        spots.add(0, Hotspot(RectF(0f, 0f, p.w, p.h), a.moreInfo(batt.id), "Car battery $big"))
        return spots
    }
}
