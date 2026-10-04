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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The "Today" card: date, status pills (phone / Mac battery, HA updates) and the Today to-do list.
 * Tap a row to tick it (todo.update_item); tap the header to open the list in HA.
 */
class TodoWidget : CardWidget() {
    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        p.card()
        val padX = 20f; val padY = 18f
        val innerW = p.w - padX * 2
        val bottom = p.h - padY
        val cfg = a.cfg
        val todo = Find.todo(cfg, snap)
        val items = todo?.attrs?.optJSONArray("items") ?: JSONArray()
        val open = (0 until items.length()).count { items.getJSONObject(it).optString("status") != "completed" }
        val d = LocalDate.now()

        var y = padY + 10f
        val badge = Common.statusBadge(ctx, p, a, snap, p.w - padX, y)?.also { spots.add(it) }
        val meta = d.format(DateTimeFormatter.ofPattern("EEEE · d MMM", Locale.ENGLISH)) + (if (todo != null) " · $open open" else "")
        p.header(cfg.title.ifBlank { todo?.name?.takeIf { cfg.one("list") != null } ?: "Today" }, if (badge == null) meta else "", padX, y, innerW)
        y += 22f

        // ---- status strip (.strip of .pill)
        val phone = Find.phoneBattery(cfg, snap)
        val pb = phone?.stateNum ?: 0.0; val mb = snap.num(cfg["mac_battery"])
        val chg = cfg["phone_battery_state"].takeIf { it.isNotEmpty() }?.let { snap.state(it) == "charging" }
            ?: (snap[phone?.id?.replace("_battery_level", "_battery_state") ?: ""]?.state == "charging")
        val upd = snap.byDomain("update").count { it.isOn }
        data class Pill(val icon: Int, val color: Long, val text: String)
        val pills = listOfNotNull(
            phone?.let { Pill(if (chg) R.drawable.ic_battery_charging else R.drawable.ic_cellphone, if (pb < 20) 0xffff4fa3 else 0xff8fc31f, "Phone ${pb.roundToInt()}%") },
            snap[cfg["mac_battery"]]?.takeIf { in_.weenja.hawidgets.Extras.enabled(ctx) }?.let { Pill(R.drawable.ic_laptop, 0xff1fa8ff, "Mac ${mb.roundToInt()}%") },
            Pill(if (upd > 0) R.drawable.ic_package_up else R.drawable.ic_check_circle_outline, if (upd > 0) 0xff7b61ff else 0xff8a90a3, if (upd > 0) "$upd update${if (upd == 1) "" else "s"}" else "HA up to date"),
        )
        var x = padX
        for (pl in pills) {
            val wdt = p.pill(0f, 0f, pl.text, 0, 0, size = 11f, iconRes = pl.icon, padX = 8f, draw = false)
            if (x + wdt > p.w - padX + .5f) { x = padX; y += 30f }
            p.pill(x, y, pl.text, pal.mix(pl.color), pal.ink.toInt(), size = 11f, iconRes = pl.icon, iconColor = pl.color.toInt(), padX = 8f)
            x += wdt + 6f
        }
        y += 24f + 10f

        // ---- list
        if (todo == null) {
            Common.empty(p, RectF(padX, y, p.w - padX, bottom), R.drawable.ic_playlist_plus, "No to-do list yet", "Add the Local To-do integration in Home Assistant")
            return spots
        }
        val rowH = 34f
        val sorted = (0 until items.length()).map { items.getJSONObject(it) }.sortedBy { if (it.optString("status") == "completed") 1 else 0 }
        for (it in sorted) {
            if (y + rowH > bottom + 4f) break
            val done = it.optString("status") == "completed"
            val r = RectF(padX, y, p.w - padX, y + rowH)
            val cy = y + rowH / 2
            p.icon(if (done) R.drawable.ic_checkbox_marked_circle else R.drawable.ic_checkbox_blank_circle_outline, padX + 11f, cy, 22f, if (done) pal.lime.toInt() else pal.muted.toInt())
            val tw = p.text(it.optString("summary"), padX + 32f, cy, 14f, if (done) 400 else 500, if (done) pal.muted.toInt() else pal.ink.toInt(), maxW = innerW - 32f)
            if (done) p.line(padX + 32f, cy, padX + 32f + tw, cy, pal.muted.toInt(), 1.2f)
            val uid = it.optString("uid")
            if (uid.isNotEmpty()) spots.add(Hotspot(r, a.service("todo", "update_item",
                JSONObject().put("entity_id", todo.id).put("item", uid).put("status", if (done) "needs_action" else "completed"))))
            y += rowH
        }
        if (items.length() == 0) Common.empty(p, RectF(padX, y, p.w - padX, bottom), R.drawable.ic_check_circle_outline, "Nothing on the list", "Add items in the HA app")
        spots.add(0, Hotspot(RectF(0f, 0f, p.w, 46f), a.open("/todo?entity_id=${todo.id}")))
        return spots
    }
}

/** Calories + macros + water today with quick-add chips (packages/nutrition.yaml scripts). */
class NutritionWidget : CardWidget() {
    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        p.card()
        val padX = 20f; val padY = 18f
        val innerW = p.w - padX * 2
        val bottom = p.h - padY
        val cfg = a.cfg
        val kcal = snap.num(cfg["nut_cal"]); val goal = snap.num(cfg["nut_goal"]).takeIf { it > 0 } ?: 2200.0
        val prot = snap.num(cfg["nut_protein"]); val pGoal = snap.num(cfg["goal_protein"]).takeIf { it > 0 } ?: 120.0
        val carbs = snap.num(cfg["nut_carbs"]); val fat = snap.num(cfg["nut_fat"])
        val water = snap.num(cfg["water"])
        val wGoal = snap.num(cfg["water_goal"]).takeIf { it > 0 } ?: 2500.0
        val last = snap[cfg["last_food"]]?.state?.takeIf { it.isNotBlank() && it != "unknown" }

        var y = padY + 10f
        val badge = Common.statusBadge(ctx, p, a, snap, p.w - padX, y)?.also { spots.add(it) }
        p.header("Fuel", if (badge == null) "${kcal.roundToInt()} of ${goal.roundToInt()} kcal" else "", padX, y, innerW)
        y += 22f
        // natural height of everything below the header: 50 (calories) + 50 (macros) + 40 (water) + 38 (chips) + 14 (last)
        val spare = (bottom - y - 192f).coerceAtLeast(0f)
        val ex = (spare / 5f).coerceAtMost(26f)     // extra gap between sections
        val big = (28f + spare * .15f).coerceAtMost(44f)

        // calories: big number + gradient bar
        p.text("${kcal.roundToInt()}", padX, y + big * .45f, big, 700, pal.ink, letterSpacing = -.03f)
        val kw = p.measure("${kcal.roundToInt()}", big, 700)
        p.text("kcal · ${(goal - kcal).roundToInt().coerceAtLeast(0)} left", padX + kw + 8f, y + big * .55f, 12f, 400, pal.muted)
        p.text("${(100 * kcal / goal).roundToInt()}%", p.w - padX, y + big * .45f, 14f, 700, pal.muted, Align.RIGHT)
        y += big + 4f
        p.bar(RectF(padX, y, p.w - padX, y + 8f), (kcal / goal).toFloat(), pal.gradOrange)
        y += 18f + ex

        // macros as three mini stats with bars
        val cols = listOf(Triple("Protein", prot to pGoal, 0xffff4fa3L), Triple("Carbs", carbs to 250.0, 0xff3fe3ffL), Triple("Fat", fat to 70.0, 0xffb5e21cL))
        val cw = (innerW - 20f) / 3
        cols.forEachIndexed { i, (name, v, col) ->
            val x = padX + i * (cw + 10f)
            p.text("${v.first.roundToInt()}g", x, y + 8f, 15f, 700, pal.ink)
            p.text(name, x, y + 24f, 11f, 400, pal.muted)
            p.bar(RectF(x, y + 34f, x + cw, y + 38f), (v.first / v.second).toFloat(), col.toInt())
        }
        y += 50f + ex

        // water row + chips
        if (y + 34f <= bottom) {
            p.iconBox(padX, y, 28f, 9f, R.drawable.ic_cup_water, pal.cyan.toInt(), 16f)
            p.text("Water", padX + 36f, y + 8f, 13f, 700, pal.ink)
            p.text("${water.roundToInt()} / ${wGoal.roundToInt()} ml", padX + 36f, y + 22f, 11f, 400, pal.muted)
            val chipW = 58f
            val c1 = RectF(p.w - padX - chipW * 2 - 8f, y - 1f, p.w - padX - chipW - 8f, y + 29f)
            val c2 = RectF(p.w - padX - chipW, y - 1f, p.w - padX, y + 29f)
            p.chip(c1, "+250", null, pal.cyan.toInt(), size = 11f, pad = 8f)
            p.chip(c2, "+500", null, pal.cyan.toInt(), size = 11f, pad = 8f)
            spots.add(Hotspot(c1, a.script(cfg["script_water"], JSONObject().put("ml", 250))))
            spots.add(Hotspot(c2, a.script(cfg["script_water"], JSONObject().put("ml", 500))))
            val bx = padX + 36f + p.measure("${water.roundToInt()} / ${wGoal.roundToInt()} ml", 11f, 400) + 10f
            if (c1.left - bx > 40f) p.bar(RectF(bx, y + 20f, c1.left - 10f, y + 24f), (water / wGoal).toFloat(), pal.cyan.toInt())
            y += 40f + ex
        }
        // food quick-add chips
        if (y + 30f <= bottom) {
            val adds = listOf("Snack" to 200, "Light meal" to 450, "Meal" to 700)
            val gap = 8f; val cw2 = (innerW - gap * 2) / 3
            adds.forEachIndexed { i, (name, cal) ->
                val r = RectF(padX + i * (cw2 + gap), y, padX + i * (cw2 + gap) + cw2, y + 30f)
                p.chip(r, "$name · $cal", if (cw2 >= 96f) R.drawable.ic_silverware_fork_knife else null, pal.orange.toInt(), size = 11f, pad = 8f)
                spots.add(Hotspot(r, a.script(cfg["script_food"], JSONObject().put("calories", cal).put("name", name))))
            }
            y += 38f + ex * .5f
        }
        if (last != null && y + 14f <= bottom + 4f) p.text("Last: $last", padX, y + 4f, 11f, 400, pal.muted, maxW = innerW)
        spots.add(0, Hotspot(RectF(0f, 0f, p.w, 44f), a.open(cfg["path_fitness"])))
        return spots
    }
}
