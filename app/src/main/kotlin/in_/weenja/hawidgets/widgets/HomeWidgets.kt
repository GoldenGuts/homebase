package in_.weenja.hawidgets.widgets

import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import in_.weenja.hawidgets.HomeStore
import in_.weenja.hawidgets.R
import in_.weenja.hawidgets.core.Accent
import in_.weenja.hawidgets.core.EnergyToday
import in_.weenja.hawidgets.core.EntityState
import in_.weenja.hawidgets.core.Extra
import in_.weenja.hawidgets.core.Home
import in_.weenja.hawidgets.core.Planner
import in_.weenja.hawidgets.core.Rules
import in_.weenja.hawidgets.core.Series
import in_.weenja.hawidgets.core.Time
import in_.weenja.hawidgets.ha.Snapshot
import in_.weenja.hawidgets.ui.Align
import in_.weenja.hawidgets.ui.Painter
import in_.weenja.hawidgets.ui.Palette
import org.json.JSONObject
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Header + a grid of entity tiles; Favorites and Suggested differ only in where the entities come from. */
abstract class GridWidget : CardWidget() {
    abstract val defaultTitle: String
    abstract fun ids(ctx: Context, snap: Snapshot, a: Actions, home: Home): List<String>
    open fun meta(ids: List<String>, snap: Snapshot): String = "${ids.count { id -> snap.core(id)?.let { Rules.isActive(it) } == true }} on"
    open val emptyTitle = "Nothing here yet"
    open val emptyHint = "Tap to choose entities"

    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val spots = ArrayList<Hotspot>()
        p.card()
        val padX = 18f; val padY = 16f
        val home = HomeStore.home(ctx, snap)
        val ids = ids(ctx, snap, a, home)
        val now = System.currentTimeMillis()
        val headerH = if (p.h >= 120f) 30f else 0f
        if (headerH > 0f) {
            val y = padY + 10f
            val badge = Common.statusBadge(ctx, p, a, snap, p.w - padX, y)?.also { spots.add(it) }
            p.header(a.cfg.title.ifBlank { defaultTitle }, if (badge == null) meta(ids, snap) else "", padX, y, p.w - padX * 2)
        }
        val area = RectF(padX, padY + headerH, p.w - padX, p.h - padY)
        if (ids.isEmpty()) {
            Common.empty(p, area, R.drawable.ic_star_outline, emptyTitle, emptyHint)
            spots.add(Hotspot(RectF(0f, 0f, p.w, p.h), a.configure(), emptyHint))
            return spots
        }
        spots.addAll(Tiles.grid(p, area, ids.map { Tiles.cell(snap.core(it), it, a, now) }, gap = 8f, minW = 62f, minH = 52f, maxRows = 5, maxTileH = 120f))
        return spots
    }
}

/** Home Assistant's own favorites (or entities picked in the app): state and one-tap control. */
class FavoritesWidget : GridWidget() {
    override val defaultTitle = "Favorites"
    override val emptyTitle = "No favorites yet"
    override val emptyHint = "Star entities in Home Assistant, or tap to pick"
    override fun ids(ctx: Context, snap: Snapshot, a: Actions, home: Home): List<String> {
        val own = a.cfg.slot("items")
        if (own.isNotEmpty() || a.cfg.option("source") == "custom") return own
        return home.favorites.ifEmpty { Planner.fillTaps(home, 12) }
    }
}

/** What Home Assistant says you usually control at this time of day (usage_prediction/common_control). */
class SuggestedWidget : GridWidget() {
    override val defaultTitle = "Suggested"
    override val emptyTitle = "Learning your habits"
    override val emptyHint = "Home Assistant suggests entities after a few days of use"
    override fun meta(ids: List<String>, snap: Snapshot) = "for this time of day"
    override fun ids(ctx: Context, snap: Snapshot, a: Actions, home: Home): List<String> = home.suggested.filter { snap[it] != null }.take(12)
}

/** One area: temperature and humidity, the thermostat with −/+, and the area's devices. */
class RoomWidget : CardWidget() {
    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        p.card()
        val padX = 18f; val padY = 16f
        val innerW = p.w - padX * 2
        val now = System.currentTimeMillis()
        val home = HomeStore.home(ctx, snap)
        val spec = a.spec ?: Planner.rooms(home).firstOrNull()
        val cfg = in_.weenja.hawidgets.Cfg(ctx, spec)
        val temp = snap.core(cfg.one("temperature"))
        val hum = snap.core(cfg.one("humidity"))
        val climate = snap.core(cfg.one("climate"))
        val items = cfg.slot("items")
        val areaName = spec?.title?.ifBlank { null } ?: spec?.option("area")?.let { home.registry.area(it)?.name } ?: "Room"

        var y = padY + 10f
        val badge = Common.statusBadge(ctx, p, a, snap, p.w - padX, y)?.also { spots.add(it) }
        val on = items.count { id -> snap.core(id)?.let { Rules.isActive(it) } == true }
        p.header(areaName, if (badge == null) (if (items.isEmpty()) "" else if (on == 0) "all off" else "$on on") else "", padX, y, innerW)
        y += 18f
        if (spec == null) {
            Common.empty(p, RectF(padX, y, p.w - padX, p.h - padY), R.drawable.ic_sofa, "Pick a room", "Tap to choose an area")
            spots.add(Hotspot(RectF(0f, 0f, p.w, p.h), a.configure(), "Choose a room"))
            return spots
        }

        // ---- climate row: big temperature with humidity under it, thermostat (− target +) on the right
        val curT = temp?.number ?: climate?.attrDouble("current_temperature")
        val hasClimateRow = curT != null || hum != null || climate != null
        val short = p.h < 170f
        if (hasClimateRow) {
            val rowH = if (short) 40f else 56f
            val cy = y + rowH / 2
            var x = padX
            if (curT != null) {
                val t = "${Rules.fmt(curT)}°"
                val tw = p.text(t, x, cy - (if (short) 1f else 7f), if (short) 26f else 32f, 700, pal.ink, letterSpacing = -.03f)
                hum?.number?.let { h ->
                    if (short) { p.icon(R.drawable.ic_water_percent, x + tw + 16f, cy, 14f, pal.cyan.toInt()); p.text("${h.roundToInt()}%", x + tw + 25f, cy, 13f, 600, pal.muted) }
                    else { p.icon(R.drawable.ic_water_percent, x + 6f, cy + 17f, 12f, pal.cyan.toInt()); p.text("${h.roundToInt()}% humidity", x + 14f, cy + 17f, 11f, 500, pal.muted) }
                }
                x += tw + (if (short && hum?.number != null) 60f else 12f)
            } else hum?.number?.let { h -> x += 12f + p.text("${h.roundToInt()}% humidity", x, cy, 15f, 600, pal.muted) }
            if (climate != null && climate.available) {
                val target = climate.attrDouble("temperature")
                val step = climate.attrDouble("target_temp_step") ?: if (climate.unit == "°F" || (target ?: 0.0) > 45) 1.0 else 0.5
                val action = climate.attrString("hvac_action") ?: climate.state
                val col = when { action == "cooling" || climate.state == "cool" -> pal.cyan; action == "heating" || climate.state == "heat" -> pal.orange; climate.state == "off" -> pal.muted; else -> pal.lime }
                val bw = if (short) 30f else 34f
                val mid = 60f
                val plus = RectF(p.w - padX - bw, cy - bw / 2, p.w - padX, cy + bw / 2)
                val tr = RectF(plus.left - 4f - mid, plus.top, plus.left - 4f, plus.bottom)
                val minus = RectF(tr.left - 4f - bw, plus.top, tr.left - 4f, plus.bottom)
                if (target != null && minus.left > x) {
                    p.text("${Rules.fmt(target)}°", tr.centerX(), tr.centerY() - 6f, 17f, 700, col.toInt(), Align.CENTER)
                    p.text(Rules.pretty(if (action == "idle" || action == "off") climate.state else action), tr.centerX(), tr.centerY() + 10f, 9.5f, 600, pal.muted, Align.CENTER, maxW = mid)
                    p.chip(minus, "", R.drawable.ic_minus, col.toInt(), size = 11f); p.chip(plus, "", R.drawable.ic_plus, col.toInt(), size = 11f)
                    fun set(v: Double) = a.service("climate", "set_temperature", JSONObject().put("entity_id", climate.id).put("temperature", v))
                    spots.add(Hotspot(minus, set(target - step), "Lower to ${Rules.fmt(target - step)}°"))
                    spots.add(Hotspot(plus, set(target + step), "Raise to ${Rules.fmt(target + step)}°"))
                    spots.add(Hotspot(tr, a.moreInfo(climate.id), "Thermostat ${Rules.stateLabel(climate)}"))
                } else {
                    val label = Rules.stateLabel(climate)
                    val pw = p.pill(0f, 0f, label, 0, 0, draw = false, maxW = p.w - padX - x)
                    if (p.w - padX - pw > x) p.pill(p.w - padX - pw, cy - 12f, label, pal.mix(col), col.toInt(), maxW = p.w - padX - x)
                    spots.add(Hotspot(RectF(p.w - padX - pw, cy - 14f, p.w - padX, cy + 14f), a.moreInfo(climate.id), label))
                }
            }
            y += rowH + 8f
        }

        // ---- devices
        if (items.isNotEmpty() && p.h - padY - y >= 34f) {
            val cells = items.map { Tiles.cell(snap.core(it), it, a, now, stripPrefix = areaName) }
            spots.addAll(Tiles.grid(p, RectF(padX, y, p.w - padX, p.h - padY), cells, gap = 8f, minW = 58f, minH = 34f, maxRows = 3, maxTileH = 110f))
        }
        return spots
    }
}

/** Doors, windows, garage, locks and the alarm: "All secure" or what is open. */
class SecurityWidget : CardWidget() {
    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        p.card()
        val padX = 18f; val padY = 16f
        val innerW = p.w - padX * 2
        val now = System.currentTimeMillis()
        val home = HomeStore.home(ctx, snap)
        val ids = a.cfg.slot("items").ifEmpty { Planner.security(home, 12) }
        val es = ids.mapNotNull { snap.core(it) }
        val alerts = es.filter { Rules.isAlert(it) }
        val alarm = es.firstOrNull { it.domain == "alarm_control_panel" }

        var y = padY + 10f
        val badge = Common.statusBadge(ctx, p, a, snap, p.w - padX, y)?.also { spots.add(it) }
        val tw = p.text(a.cfg.title.ifBlank { "Security" }, padX, y, 14f, 700, pal.ink)
        if (badge == null) {
            val (label, color) = if (alerts.isEmpty()) "All secure" to pal.lime else summary(alerts) to pal.pink
            val pw = p.pill(0f, 0f, label, 0, 0, size = 11f, iconRes = if (alerts.isEmpty()) R.drawable.ic_shield_check else R.drawable.ic_shield_alert, draw = false, maxW = innerW - tw - 10f)
            p.pill(p.w - padX - pw, y - 12f, label, pal.mix(color, .22f), color.toInt(), size = 11f, iconRes = if (alerts.isEmpty()) R.drawable.ic_shield_check else R.drawable.ic_shield_alert, maxW = innerW - tw - 10f)
        }
        y += 22f
        if (ids.isEmpty()) {
            Common.empty(p, RectF(padX, y, p.w - padX, p.h - padY), R.drawable.ic_shield_home, "No doors, locks or alarm found", "Tap to pick entities")
            spots.add(Hotspot(RectF(0f, 0f, p.w, p.h), a.configure()))
            return spots
        }
        // alarm strip with arm buttons when there is room
        if (alarm != null && p.h >= 230f) {
            val h = 34f
            val armed = alarm.state.startsWith("armed")
            val col = if (alarm.state == "triggered") pal.pink else if (armed) pal.lime else pal.muted
            val r = RectF(padX, y, p.w - padX, y + h)
            p.rrect(r, h / 2, pal.mix(col, .18f))
            p.icon(if (armed) R.drawable.ic_shield_home else R.drawable.ic_shield_off, r.left + 18f, r.centerY(), 18f, col.toInt())
            p.text(Rules.stateLabel(alarm), r.left + 34f, r.centerY(), 12f, 700, pal.ink, maxW = innerW * .4f)
            val codeArm = alarm.attrBool("code_arm_required") == true
            val btns = if (armed) listOf("Disarm" to null) else listOf("Home" to "alarm_arm_home", "Away" to "alarm_arm_away")
            var bx = r.right - 4f
            for ((lab, svc) in btns.reversed()) {
                val bw = p.measure(lab, 11f, 700) + 24f
                val br = RectF(bx - bw, r.top + 4f, bx, r.bottom - 4f)
                p.rrect(br, br.height() / 2, pal.surface2); p.text(lab, br.centerX(), br.centerY(), 11f, 700, pal.ink, Align.CENTER)
                // disarming (and arming with a code) needs the keypad: open Home Assistant
                spots.add(Hotspot(br, if (svc == null || codeArm) a.moreInfo(alarm.id) else a.service("alarm_control_panel", svc, JSONObject().put("entity_id", alarm.id), alarm.id to "arming"), lab))
                bx = br.left - 6f
            }
            y += h + 10f
        }
        val tiles = ids.filter { it != alarm?.id || p.h < 230f }
        spots.addAll(Tiles.grid(p, RectF(padX, y, p.w - padX, p.h - padY), tiles.map { Tiles.cell(snap.core(it), it, a, now) }, gap = 8f, minW = 62f, minH = 50f, maxRows = 4, maxTileH = 110f))
        return spots
    }

    private fun summary(alerts: List<EntityState>): String {
        val open = alerts.count { it.domain != "lock" && it.deviceClass !in setOf("smoke", "gas", "carbon_monoxide", "moisture") && it.domain != "alarm_control_panel" }
        val unlocked = alerts.count { it.domain == "lock" }
        val hazards = alerts.count { it.deviceClass in setOf("smoke", "gas", "carbon_monoxide", "moisture") }
        return listOfNotNull(open.takeIf { it > 0 }?.let { "$it open" }, unlocked.takeIf { it > 0 }?.let { "$it unlocked" },
            hazards.takeIf { it > 0 }?.let { "$it alarm${if (it == 1) "" else "s"}" }, "triggered".takeIf { alerts.any { it.domain == "alarm_control_panel" } }).joinToString(" · ")
    }
}

/** Live power, solar and battery, and today's kWh from the Energy dashboard (energy/get_prefs). */
class EnergyWidget : CardWidget() {
    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        p.card()
        val padX = 18f; val padY = 16f
        val innerW = p.w - padX * 2
        val home = HomeStore.home(ctx, snap)
        val setup = home.energy
        val today = snap.core(Extra.ENERGY_ID)
        val states = home.states
        fun w(id: String?): Double? = id?.let { states[it] }?.let { e -> e.number?.let { v -> when (e.unit.lowercase()) { "kw" -> v * 1000; "mw" -> v * 1e6; else -> v } } }
        val power = a.cfg.one("power")
        val e = EnergyToday(today?.attrDouble("grid_in"), today?.attrDouble("grid_out"), today?.attrDouble("solar"), today?.attrDouble("battery_in"), today?.attrDouble("battery_out"),
            w(power ?: setup.gridPower), w(setup.solarPower), w(setup.batteryPower), setup.batterySoc?.let { states[it]?.number })

        var y = padY + 10f
        val badge = Common.statusBadge(ctx, p, a, snap, p.w - padX, y)?.also { spots.add(it) }
        p.header(a.cfg.title.ifBlank { "Energy" }, if (badge == null) (e.home?.let { "today · ${EnergyToday.energy(it)} used" } ?: "today") else "", padX, y, innerW)
        y += 22f
        spots.add(0, Hotspot(RectF(0f, 0f, p.w, p.h), a.open("/energy"), "Energy dashboard"))
        if (setup.isEmpty && power == null) {
            Common.empty(p, RectF(padX, y, p.w - padX, p.h - padY), R.drawable.ic_lightning_bolt, "No Energy dashboard yet", "Settings → Dashboards → Energy in Home Assistant")
            return spots
        }
        class Col(val icon: Int, val label: String, val live: String?, val today: String?, val color: Long, val active: Boolean)
        val cols = ArrayList<Col>()
        if (setup.hasSolar) cols.add(Col(R.drawable.ic_solar_power, "Solar", e.solarW?.let { EnergyToday.power(it) }, e.solar?.let { EnergyToday.energy(it) }, pal.orange, (e.solarW ?: 0.0) > 5))
        cols.add(Col(R.drawable.ic_home_lightning_bolt, "Home", e.homeW?.let { EnergyToday.power(it) }, e.home?.let { EnergyToday.energy(it) }, pal.cyan, true))
        if (setup.hasGrid || power != null) {
            val gw = e.gridW
            val exporting = gw != null && gw < 0
            cols.add(Col(R.drawable.ic_transmission_tower, if (exporting) "Exporting" else "Grid", gw?.let { EnergyToday.power(abs(it)) },
                e.gridIn?.let { EnergyToday.energy(it) },
                if (exporting) pal.lime else pal.purple, gw != null && abs(gw) > 5))
        }
        if (setup.hasBattery) {
            // battery power: positive = discharging into the home (HA's convention), negative = charging
            val bw = e.batteryW
            val flow = bw?.let { if (abs(it) < 5) "idle" else (if (it < 0) "↑" else "↓") + EnergyToday.power(abs(it)) }
            cols.add(Col(R.drawable.ic_home_battery, "Battery", e.batteryPct?.let { "${it.roundToInt()}%" } ?: flow, if (e.batteryPct != null) flow else null,
                pal.lime, (bw ?: 0.0).let { abs(it) > 5 }))
        }

        val bottom = p.h - padY
        val showBar = bottom - y >= 120f && e.home != null && (e.solar != null || e.gridIn != null)
        val blockH = (bottom - y - (if (showBar) 34f else 0f)).coerceAtMost(110f)
        val cw = (innerW - 8f * (cols.size - 1)) / cols.size
        cols.forEachIndexed { i, c ->
            val r = RectF(padX + i * (cw + 8f), y, padX + i * (cw + 8f) + cw, y + blockH)
            p.rrect(r, 16f, if (c.active) pal.mix(c.color, .16f) else pal.surface2.toInt())
            val compact = blockH < 84f
            val iy = r.top + (if (compact) 18f else 22f)
            p.icon(c.icon, r.left + 16f, iy, if (compact) 16f else 20f, c.color.toInt())
            // the label only when it fits; the icon says enough on narrow columns
            if (p.measure(c.label, 10.5f, 600) <= r.width() - 34f) p.text(c.label, r.left + 30f, iy, 10.5f, 600, pal.muted)
            p.fitText(c.live ?: "—", r.left + 10f, iy + (if (compact) 22f else 28f), if (compact) 17f else 20f, 12f, 700, pal.ink.toInt(), Align.LEFT, r.width() - 16f)
            if (!compact || blockH >= 70f) c.today?.let { p.text(it, r.left + 10f, r.bottom - 12f, 10.5f, 500, pal.muted, maxW = r.width() - 16f) }
        }
        y += blockH + 12f
        if (showBar) {
            // where today's energy came from: solar (self-used), battery, grid
            val home0 = max(0.01, e.home ?: 0.0)
            val selfSolar = ((e.solar ?: 0.0) - (e.gridOut ?: 0.0) - (e.batteryIn ?: 0.0)).coerceAtLeast(0.0)
            val parts = listOf(Triple("solar", selfSolar / home0, pal.orange), Triple("battery", (e.batteryOut ?: 0.0) / home0, pal.lime), Triple("grid", (e.gridIn ?: 0.0) / home0, pal.purple))
            val r = RectF(padX, y, p.w - padX, y + 8f)
            p.rrect(r, 4f, pal.track)
            var x = r.left
            val sum = parts.sumOf { it.second }.coerceAtLeast(1.0)
            for ((_, frac, col) in parts) { val wdt = (r.width() * frac / sum).toFloat(); if (wdt > 1f) { p.rrect(RectF(x, r.top, x + wdt, r.bottom), 4f, col.toInt()); x += wdt } }
            val selfPct = ((selfSolar + (e.batteryOut ?: 0.0)) / home0 * 100).roundToInt().coerceIn(0, 100)
            p.text("$selfPct% self-powered today", padX, y + 20f, 11f, 600, pal.muted, maxW = innerW)
        }
        return spots
    }
}

/** The last 24 hours of one sensor (REST /api/history/period), with min, max and now. */
class GraphWidget : CardWidget() {
    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        p.card()
        val padX = 18f; val padY = 16f
        val innerW = p.w - padX * 2
        val home = HomeStore.home(ctx, snap)
        val id = a.cfg.one("sensor") ?: Planner.graphCandidate(home)?.id
        val e = snap.core(id)
        val series = e?.attr(Extra.HISTORY)?.let { Series.fromJson(it) }
        var y = padY + 10f
        val badge = Common.statusBadge(ctx, p, a, snap, p.w - padX, y)?.also { spots.add(it) }
        val title = a.cfg.title.ifBlank { e?.name ?: "Sensor graph" }
        val value = e?.let { Rules.stateLabel(it) } ?: ""
        val vw = if (badge == null) p.text(value, p.w - padX, y, 16f, 700, pal.ink, Align.RIGHT) else 0f
        p.text(title, padX, y, 14f, 700, pal.ink, maxW = innerW - vw - 10f)
        y += 16f
        if (id == null || e == null) {
            Common.empty(p, RectF(padX, y, p.w - padX, p.h - padY), R.drawable.ic_chart_line, "Pick a sensor", "Tap to choose what to graph")
            spots.add(Hotspot(RectF(0f, 0f, p.w, p.h), a.configure()))
            return spots
        }
        spots.add(Hotspot(RectF(0f, 0f, p.w, p.h), a.moreInfo(id), "$title, $value"))
        if (series == null || series.points.size < 2) {
            Common.empty(p, RectF(padX, y, p.w - padX, p.h - padY), R.drawable.ic_chart_line, "Collecting history…", "The graph fills in after the next refresh")
            return spots
        }
        val now = System.currentTimeMillis()
        val from = now - 24 * 3_600_000L
        val n = minOf((innerW / 3f).toInt(), series.points.count { it.atMillis >= from } + 1).coerceIn(12, 160)
        val vals = series.buckets(n, from, now)
        val known = vals.filterNotNull()
        if (known.isEmpty()) { Common.empty(p, RectF(padX, y, p.w - padX, p.h - padY), R.drawable.ic_chart_line, "No data in the last 24 h", ""); return spots }
        val lo = known.min(); val hi = known.max()
        val span = if (hi - lo < 1e-6) 1.0 else hi - lo
        val showAxis = p.h >= 130f
        val chart = RectF(padX, y + 12f, p.w - padX, p.h - padY - (if (showAxis) 16f else 2f))
        val accent = pal.color(Rules.accent(e)).toInt()
        val path = Path(); val fill = Path()
        var started = false; var lastX = chart.left
        vals.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val x = chart.left + chart.width() * i / (n - 1).toFloat()
            val yy = chart.bottom - ((v - lo) / span * chart.height()).toFloat()
            if (!started) { path.moveTo(x, yy); fill.moveTo(x, chart.bottom); fill.lineTo(x, yy); started = true } else { path.lineTo(x, yy); fill.lineTo(x, yy) }
            lastX = x
        }
        fill.lineTo(lastX, chart.bottom); fill.close()
        val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, chart.top, 0f, chart.bottom, Palette.alpha(accent, .35f), Palette.alpha(accent, 0f), Shader.TileMode.CLAMP) }
        p.canvas.drawPath(fill, fp)
        val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2.2f; color = accent; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        p.canvas.drawPath(path, sp)
        p.line(chart.left, chart.top, chart.right, chart.top, pal.track.toInt(), 1f, dashed = true)
        // min / max on small card-coloured pills so the line never hides them
        for ((label, yy) in listOf("${Rules.fmt(hi)}${unitShort(e)}" to chart.top - 1f, "${Rules.fmt(lo)}${unitShort(e)}" to chart.bottom - 9f)) {
            val lw = p.measure(label, 9.5f, 600)
            p.rrect(RectF(chart.left - 2f, yy - 7f, chart.left + lw + 6f, yy + 7f), 7f, Palette.alpha(pal.card.toInt(), .85f))
            p.text(label, chart.left + 2f, yy, 9.5f, 600, pal.muted)
        }
        if (showAxis) {
            val off = TimeZone.getDefault().getOffset(now) / 60_000
            listOf(0f to from, .5f to (from + now) / 2, 1f to now).forEach { (f, t) ->
                p.text(if (f == 1f) "now" else Time.clock(t, off), chart.left + chart.width() * f, p.h - padY - 5f, 9.5f, 500, pal.dim,
                    if (f == 0f) Align.LEFT else if (f == 1f) Align.RIGHT else Align.CENTER)
            }
        }
        return spots
    }

    private fun unitShort(e: EntityState) = if (e.unit.startsWith("°") || e.unit == "%") e.unit else if (e.unit.isNotEmpty()) " ${e.unit}" else ""
}
