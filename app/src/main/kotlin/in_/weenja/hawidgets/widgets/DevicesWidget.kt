package in_.weenja.hawidgets.widgets

import android.content.Context
import android.graphics.RectF
import in_.weenja.hawidgets.HomeStore
import in_.weenja.hawidgets.R
import in_.weenja.hawidgets.core.Planner
import in_.weenja.hawidgets.core.Rules
import in_.weenja.hawidgets.ha.Snapshot
import in_.weenja.hawidgets.ui.Painter
import org.json.JSONArray
import org.json.JSONObject

/**
 * "My devices": N slots, each any entity that toggles (light, switch, fan, cover, lock, input_boolean).
 * The colour comes from the domain; the widget size decides how many slots show. With room to spare a
 * chip row adds brightness and fan-speed presets. Empty slots fill themselves: Home Assistant favorites,
 * then what it suggests, then lights, switches and fans room by room.
 */
class DevicesWidget : CardWidget() {

    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        p.card()
        val padX = 20f; val padY = 18f
        val innerW = p.w - padX * 2
        val now = System.currentTimeMillis()

        val ids = a.cfg.slot("items").ifEmpty { Planner.fillToggles(HomeStore.home(ctx, snap), 12) }
        val entities = ids.map { snap.core(it) }

        var y = padY + 10f
        val badge = Common.statusBadge(ctx, p, a, snap, p.w - padX, y)?.also { spots.add(it) }
        val onCount = entities.count { it != null && Rules.isActive(it) }
        p.header(a.cfg.title.ifBlank { "My devices" }, if (badge == null) "$onCount of ${entities.count { it != null }} on" else "", padX, y, innerW)
        y += 20f
        if (ids.isEmpty()) {
            Common.empty(p, RectF(padX, y, p.w - padX, p.h - padY), R.drawable.ic_toggle_switch, "Nothing to switch yet", "Tap to pick lights, switches and fans")
            spots.add(Hotspot(RectF(0f, 0f, p.w, p.h), a.configure(), "Choose devices"))
            return spots
        }

        // chip row only when there is room under at least one row of tiles
        val gap = 8f
        val chipH = 30f
        val lights = entities.filterNotNull().filter { it.domain == "light" && it.available }
        val fan = entities.filterNotNull().firstOrNull { it.domain == "fan" && it.available }
        val bottom = p.h - padY
        val showChips = (lights.isNotEmpty() || fan != null) && bottom - y >= 2 * 58f + gap + chipH + gap
        val gridBottom = if (showChips) bottom - chipH - gap else bottom
        val cells = ids.mapIndexed { i, id -> Tiles.cell(entities[i], id, a, now) }
        spots.addAll(Tiles.grid(p, RectF(padX, y, p.w - padX, gridBottom), cells, gap, minW = 64f, minH = 54f, maxRows = 4, maxTileH = 130f))

        if (showChips) {
            val cy = bottom - chipH
            class Chip(val label: String, val icon: Int, val color: Long, val pi: android.app.PendingIntent)
            val chips = ArrayList<Chip>()
            if (lights.isNotEmpty()) {
                val target = JSONArray(lights.map { it.id })
                for ((lab, pct) in listOf("30%" to 30, "60%" to 60, "Max" to 100))
                    chips.add(Chip(lab, R.drawable.ic_brightness_6, pal.orange, a.service("light", "turn_on", JSONObject().put("entity_id", target).put("brightness_pct", pct), lights.first().id to "on")))
            }
            if (fan != null) {
                for ((lab, pct, ic) in listOf(Triple("Low", 33, R.drawable.ic_fan_speed_1), Triple("Mid", 66, R.drawable.ic_fan_speed_2), Triple("High", 100, R.drawable.ic_fan_speed_3)))
                    chips.add(Chip(lab, ic, pal.purple, a.service("fan", "set_percentage", JSONObject().put("entity_id", fan.id).put("percentage", pct), fan.id to "on")))
            }
            val cw = (innerW - gap * (chips.size - 1)) / chips.size
            chips.forEachIndexed { i, c ->
                val r = RectF(padX + i * (cw + gap), cy, padX + i * (cw + gap) + cw, cy + chipH)
                if (cw >= 62f) p.chip(r, c.label, c.icon, c.color.toInt(), size = 11f) else p.chip(r, c.label, null, c.color.toInt(), size = 11f, pad = 6f)
                spots.add(Hotspot(r, c.pi, c.label))
            }
        }
        return spots
    }
}
