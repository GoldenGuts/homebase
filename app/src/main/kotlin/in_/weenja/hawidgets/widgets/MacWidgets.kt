package in_.weenja.hawidgets.widgets

import android.app.PendingIntent
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
import kotlin.math.min
import kotlin.math.roundToInt

/** Shared bits of the Mac view (dashboards/mac.yaml): status attributes and the mode names. */
object Mac {
    val MODES = listOf(Triple("auto", "Auto", R.drawable.ic_flash), Triple("acceptEdits", "Edits", R.drawable.ic_pencil_outline),
        Triple("plan", "Plan", R.drawable.ic_map_outline), Triple("default", "Ask", R.drawable.ic_hand_back_left_outline))
    fun modeName(m: String) = when (m) { "auto" -> "Auto"; "acceptEdits" -> "Accept edits"; "plan" -> "Plan"; "default" -> "Ask"; else -> m }
    fun modeColor(p: Palette, m: String) = when (m) { "auto" -> p.lime; "acceptEdits" -> p.cyan; "plan" -> p.purple; else -> p.orange }
    fun online(cfg: Cfg, snap: Snapshot) = snap[cfg["mac_status"]]?.state == "online" || snap.on(cfg["mac_online"])
    fun folder(cfg: Cfg, snap: Snapshot): String = snap[cfg["claude_folder"]]?.state?.trimEnd('/')?.takeIf { it.isNotEmpty() && it != "unknown" } ?: ""
    fun project(cfg: Cfg, snap: Snapshot): String = folder(cfg, snap).substringAfterLast('/').ifEmpty { "—" }
    fun age(started: Long): String { val m = max(0L, (System.currentTimeMillis() / 1000 - started) / 60); return if (m < 60) "$m min" else "${m / 60}h ${m % 60}m" }
}

/**
 * The "Claude Code" card: sessions live / started today / folder / mode, one row per Remote Control
 * host with "Open" (the session link), "New session" (the environment link) and a stop key, then the
 * permission mode picker and Start host / Resume / Stop all.
 */
open class ClaudeWidget : CardWidget() {
    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        p.card()
        val padX = 20f; val padY = 18f
        val innerW = p.w - padX * 2
        val bottom = p.h - padY
        val cfg = a.cfg
        val st = snap[cfg["mac_status"]]
        val on = Mac.online(cfg, snap)
        val live = st?.num("active_sessions")?.roundToInt() ?: 0
        val hosts = st?.attrs?.optJSONArray("sessions_json") ?: JSONArray()
        val hostCount = if (hosts.length() > 0) hosts.length() else (st?.num("session_count")?.roundToInt() ?: 0)
        val today = st?.num("claude_today")?.roundToInt() ?: 0
        val modeId = cfg["claude_mode"]
        val mode = snap[modeId]?.state ?: "auto"
        val keychain = st?.str("keychain")?.takeIf { it.isNotEmpty() } ?: "?"

        if (p.w < 200f) {
            // square tile: big live count, folder, Start host pill; tap the number = open the first session
            p.icon(R.drawable.ic_robot, 16f + 12f, 16f + 12f, 22f, (if (live > 0) pal.lime else pal.muted).toInt())
            p.text("Claude", 16f + 30f, 16f + 12f, 13f, 700, pal.ink)
            p.text("$live", 16f, p.h / 2 - 2f, 40f, 700, pal.ink, letterSpacing = -.04f)
            val lw = p.measure("$live", 40f, 700)
            p.text(if (live == 1) "live" else "live", 16f + lw + 6f, p.h / 2 + 8f, 12f, 500, pal.muted)
            p.text(if (!on) "Mac offline" else Mac.project(cfg, snap) + " · " + Mac.modeName(mode), 16f, p.h - 52f, 11f, 500, pal.muted, maxW = p.w - 32f)
            val pill = RectF(16f, p.h - 40f, p.w - 16f, p.h - 12f)
            p.gradient(pill, 14f, pal.gradLime, 160f, shadow = 6f)
            p.icon(R.drawable.ic_play, pill.centerX() - 34f, pill.centerY(), 15f, pal.inkOnLime.toInt())
            p.text("Start host", pill.centerX() + 8f, pill.centerY(), 12f, 800, pal.inkOnLime.toInt(), Align.CENTER)
            spots.add(Hotspot(pill, a.script(cfg["claude_start"])))
            val first = (0 until hosts.length()).firstNotNullOfOrNull { i -> val h = hosts.getJSONObject(i); (h.optJSONArray("served")?.optJSONObject(0)?.optString("link") ?: h.optString("link")).takeIf { it.startsWith("http") } }
            if (first != null) spots.add(0, Hotspot(RectF(0f, 0f, p.w, p.h - 44f), a.url(first)))
            return spots
        }
        var y = padY + 10f
        val badge = Common.statusBadge(ctx, p, a, snap, p.w - padX, y)?.also { spots.add(it) }
        val meta = if (!on) "Mac offline" else if (hostCount > 0) "$live session${if (live == 1) "" else "s"} · $hostCount host${if (hostCount == 1) "" else "s"}" else "nothing running"
        p.header("Claude Code", if (badge == null) meta else "", padX, y, innerW)
        y += 22f

        // ---- stat4 (one row when short, or when hosts need the room)
        val compact = p.h < 200f || (hosts.length() > 0 && p.h < 380f)
        data class Stat(val color: Long, val value: String, val label: String)
        val stats = listOf(Stat(0xffb5e21c, "$live", if (compact) "live" else "Sessions live"), Stat(0xff7b61ff, "$today", if (compact) "today" else "Started today"),
            Stat(0xff3fe3ff, Mac.project(cfg, snap), if (compact) "folder" else "Selected folder"), Stat(0xffff9f2e, Mac.modeName(mode), if (compact) "mode" else "Mode · keychain $keychain"))
        val cols = if (compact) 4 else 2
        val rows = if (compact) 1 else 2
        val sh = if (compact) 36f else 40f
        val sw = (innerW - 14f * (cols - 1)) / cols
        stats.forEachIndexed { i, s ->
            val c = i % cols; val r = i / cols
            val x = padX + c * (sw + 14f); val ty = y + r * (sh + 8f)
            p.rrect(RectF(x, ty, x + 3f, ty + sh), 1.5f, s.color)
            p.fitText(s.value, x + 10f, ty + (if (compact) 8f else 10f), if (compact) 16f else 20f, 11f, 700, pal.ink.toInt(), Align.LEFT, sw - 12f)
            p.text(s.label, x + 10f, ty + sh - 7f, if (compact) 9.5f else 12f, 400, pal.muted, maxW = sw - 12f)
        }
        y += rows * sh + (rows - 1) * 8f + 12f

        // ---- start row(s): mode chips + Start host / Resume / Stop all. Drawn from the bottom so hosts get what is left.
        val chipH = 32f; val gap = 6f
        val startRows = if (p.h >= 340f) 2 else 1
        val startH = startRows * chipH + (startRows - 1) * gap
        val startTop = bottom - startH
        run {
            var yy = startTop
            if (startRows == 2) {
                val cw = (innerW - gap * 3) / 4
                Mac.MODES.forEachIndexed { i, (key, name, ic) ->
                    val r = RectF(padX + i * (cw + gap), yy, padX + i * (cw + gap) + cw, yy + chipH)
                    val col = Mac.modeColor(pal, key)
                    p.chip(r, name, if (cw >= 70f) ic else null, col.toInt(), bg = if (mode == key) pal.mix(col, .34f) else pal.mix(col), size = 11f, pad = 8f)
                    if (mode == key) p.outline(RectF(r.left + .5f, r.top + .5f, r.right - .5f, r.bottom - .5f), chipH / 2, Palette.alpha(col, .6f), 1f)
                    spots.add(Hotspot(r, a.service("input_select", "select_option", JSONObject().put("entity_id", modeId).put("option", key), modeId to key)))
                }
                yy += chipH + gap
            }
            // Start host (gradient pill, double width) · Resume · Stop all
            val cw = (innerW - gap * 3) / 4
            val start = RectF(padX, yy, padX + cw * 2 + gap, yy + chipH)
            p.gradient(start, chipH / 2, pal.gradLime, 160f, shadow = 6f)
            p.icon(R.drawable.ic_play, start.centerX() - 34f, start.centerY(), 16f, pal.inkOnLime.toInt())
            p.text("Start host", start.centerX() + 8f, start.centerY(), 12f, 800, pal.inkOnLime.toInt(), Align.CENTER, letterSpacing = .02f)
            spots.add(Hotspot(start, a.script(cfg["claude_start"])))
            val resume = RectF(start.right + gap, yy, start.right + gap + cw, yy + chipH)
            p.chip(resume, "Resume", if (cw >= 74f) R.drawable.ic_history else null, pal.cyan.toInt(), size = 11f, pad = 8f)
            spots.add(Hotspot(resume, a.script(cfg["claude_start"], JSONObject().put("mode", "resume"))))
            val stopArmed = Actions.isArmed(ctx, "mac_stop_all")
            val stop = RectF(resume.right + gap, yy, resume.right + gap + cw, yy + chipH)
            if (stopArmed) p.chip(stop, "Sure?", null, pal.pink.toInt(), bg = pal.mix(pal.pink, .45f), size = 11f, pad = 8f)
            else p.chip(stop, "Stop all", if (cw >= 78f) R.drawable.ic_stop else null, pal.pink.toInt(), size = 11f, pad = 8f)
            spots.add(Hotspot(stop, a.armed("mac_stop_all", "script", cfg["claude_stop_all"])))
        }

        // ---- hosts between the stats and the start rows
        val hostsBottom = startTop - 10f
        if (hostsBottom - y >= 40f) {
            p.tag("Hosts", padX, y + 6f); y += 16f
            if (hosts.length() == 0) {
                p.text(if (on) "No host running · Start host serves the selected folder" else "Wake the Mac first", padX, y + 10f, 12f, 400, pal.muted, maxW = innerW)
            }
            val rowH = 46f
            for (i in 0 until hosts.length()) {
                if (y + rowH > hostsBottom + 2f || spots.size > 15) break
                val h = hosts.getJSONObject(i)
                val r = RectF(padX, y, p.w - padX, y + rowH)
                p.rrect(r, 14f, pal.surface2)
                val status = h.optString("status")
                val col = when (status) { "connected" -> 0xffb5e21c; "starting" -> 0xffff9f2e; "error", "duplicate" -> 0xffff4fa3; else -> 0xff8a90a3 }.toInt()
                p.circle(r.left + 14f, r.centerY(), 4.5f, col)
                val n = h.optInt("sessions").takeIf { it > 0 } ?: (if (status == "connected") 1 else 0)
                val stTxt = when (status) { "connected" -> "live"; "starting" -> "starting…"; "error" -> "error"; "duplicate" -> "served elsewhere"; else -> status }
                // right side: New session (env link) + stop key
                val stopR = RectF(r.right - 36f, r.top + 7f, r.right - 4f, r.bottom - 7f)
                p.rrect(stopR, 10f, pal.mix(pal.pink, .2f)); p.icon(R.drawable.ic_stop, stopR.centerX(), stopR.centerY(), 16f, pal.pink.toInt())
                spots.add(Hotspot(stopR, a.script(cfg["claude_stop"], JSONObject().put("name", h.optString("name")))))
                var rightEdge = stopR.left - 6f
                val keysOnly = p.w < 380f
                val env = h.optString("env_link").takeIf { it.startsWith("http") }
                if (env != null) {
                    val newR = RectF(rightEdge - (if (keysOnly) 32f else 62f), r.top + 7f, rightEdge, r.bottom - 7f)
                    if (keysOnly) { p.rrect(newR, 10f, pal.mix(pal.lime, .2f)); p.icon(R.drawable.ic_plus, newR.centerX(), newR.centerY(), 18f, pal.lime.toInt()) }
                    else p.chip(newR, "New", R.drawable.ic_plus, pal.lime.toInt(), size = 11f, pad = 6f)
                    spots.add(Hotspot(newR, a.url(env)))
                    rightEdge = newR.left - 6f
                }
                val served = h.optJSONArray("served") ?: JSONArray()
                val link = served.optJSONObject(0)?.optString("link")?.takeIf { it.startsWith("http") } ?: h.optString("link").takeIf { it.startsWith("http") }
                if (link != null) {
                    val openR = RectF(rightEdge - (if (keysOnly) 32f else 66f), r.top + 7f, rightEdge, r.bottom - 7f)
                    if (keysOnly) { p.rrect(openR, 10f, pal.mix(pal.cyan, .2f)); p.icon(R.drawable.ic_open_in_new, openR.centerX(), openR.centerY(), 16f, pal.cyan.toInt()) }
                    else p.chip(openR, "Open", R.drawable.ic_open_in_new, pal.cyan.toInt(), size = 11f, pad = 6f)
                    spots.add(Hotspot(openR, a.url(link)))
                    rightEdge = openR.left - 6f
                }
                val textW = rightEdge - (r.left + 26f)
                p.text(h.optString("name"), r.left + 26f, r.centerY() - 8f, 13f, 700, pal.ink, maxW = textW)
                p.text("$n session${if (n == 1) "" else "s"} · ${Mac.age(h.optLong("started"))} · $stTxt", r.left + 26f, r.centerY() + 9f, 11f, 400, pal.muted, maxW = textW)
                // the row itself opens the first session when there is one
                if (link != null) spots.add(0, Hotspot(r, a.url(link)))
                y += rowH + 6f
            }
        }
        return spots
    }
}

/** The Mac itself: app tiles (open / running), power chips, keep-awake timer, machine stats. */
class MacWidget : CardWidget() {
    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        p.card()
        val padX = 20f; val padY = 18f
        val innerW = p.w - padX * 2
        val bottom = p.h - padY
        val cfg = a.cfg
        val st = snap[cfg["mac_status"]]
        val on = Mac.online(cfg, snap)
        val caff = snap.on(cfg["mac_caffeinated"]) || st?.attrs?.optBoolean("caffeinate") == true
        fun macScript(name: String, data: JSONObject? = null) = a.script(cfg["mac_script_prefix"] + name, data)
        val batt = st?.num("battery")?.roundToInt()
        val load = st?.str("load")?.takeIf { it.isNotEmpty() } ?: "?"
        val disk = st?.str("disk_used_pct")?.takeIf { it.isNotEmpty() } ?: "?"
        val up = st?.num("uptime_hours")?.toInt() ?: 0

        var y = padY + 10f
        val badge = Common.statusBadge(ctx, p, a, snap, p.w - padX, y)?.also { spots.add(it) }
        val meta = if (!on) "offline" else listOfNotNull(batt?.let { "$it%" + (if (st?.attrs?.optBoolean("charging") == true) " ⚡" else "") }, "load $load", "disk $disk%", "up ${up / 24}d ${up % 24}h").joinToString(" · ")
        p.header("Mac", if (badge == null) meta else "", padX, y, innerW)
        y += 20f

        // ---- app tiles (app_tile template): gradient when running, tap opens
        data class AppT(val name: String, val icon: Int, val grad: Pair<Long, Long>, val ink: Long, val running: Boolean)
        val apps = st?.attrs?.optJSONObject("apps")
        fun run(key: String, sensor: String) = snap.on(sensor) || apps?.optBoolean(key) == true
        val tiles = listOf(
            AppT("Antigravity", R.drawable.ic_rocket_launch, pal.gradOrange, pal.inkOnOrange, run("Antigravity", "binary_sensor.mac_antigravity_running")),
            AppT("Cursor", R.drawable.ic_cursor_default_click, pal.gradCyan, pal.inkOnCyan, run("Cursor", "binary_sensor.mac_cursor_running")),
            AppT("Zed", R.drawable.ic_code_braces, pal.gradPurple, pal.inkOnPurple, run("Zed", "binary_sensor.mac_zed_running")),
            AppT("Chrome", R.drawable.ic_google_chrome, pal.gradLime, pal.inkOnLime, run("Google Chrome", "binary_sensor.mac_chrome_running")),
            AppT("Ghostty", R.drawable.ic_console, pal.gradPink, pal.inkOnPink, run("Ghostty", "binary_sensor.mac_ghostty_running")),
            AppT("Finder", R.drawable.ic_folder_open, pal.gradCyan, pal.inkOnCyan, on),
        )
        val gap = 8f
        val chipH = 30f
        // rows drawn from the bottom: keep-awake, then power chips; the app grid takes what is left
        val chipRows = when { bottom - y >= 2 * chipH + gap + 12f + 64f -> 2; bottom - y >= chipH -> 1; else -> 0 }
        val chipsTop = bottom - (chipRows * chipH + (chipRows - 1).coerceAtLeast(0) * gap)
        val gridBottom = if (chipRows > 0) chipsTop - 12f else bottom
        val gridH = gridBottom - y
        if (gridH >= 56f) {
            // 6 in a row when short, 3 x 2 when there is room for two rows of decent tiles
            val rows = if (gridH >= 2 * 64f + gap) 2 else 1
            val cols = if (rows == 2) 3 else 6
            val tw = (innerW - gap * (cols - 1)) / cols
            val th = ((gridH - gap * (rows - 1)) / rows).coerceAtMost(if (rows == 2) 110f else 84f)
            val y0 = y + (gridH - (rows * th + (rows - 1) * gap)) / 2
            tiles.forEachIndexed { i, t ->
                val c = i % cols; val rr = i / cols
                val r = RectF(padX + c * (tw + gap), y0 + rr * (th + gap), padX + c * (tw + gap) + tw, y0 + rr * (th + gap) + th)
                if (t.running) p.gradient(r, 16f, t.grad, 160f, shadow = 6f) else p.rrect(r, 16f, pal.surface2)
                val ink = if (t.running) t.ink.toInt() else pal.muted.toInt()
                if (rows == 2) {
                    p.icon(t.icon, r.left + 14f + 14f, r.top + 14f + 14f, 28f, ink, shadow = t.running)
                    p.text(t.name, r.left + 14f, r.bottom - 26f, 13f, 800, ink, maxW = tw - 24f)
                    p.text(if (t.running) "running · tap to focus" else "tap to open", r.left + 14f, r.bottom - 12f, 10f, 500, Palette.alpha(ink, .75f), maxW = tw - 24f)
                } else {
                    p.icon(t.icon, r.centerX(), r.centerY() - (if (th >= 80f) 8f else 6f), min(30f, tw * .55f), ink, shadow = t.running)
                    p.text(t.name, r.centerX(), r.bottom - 12f, if (tw >= 50f) 10f else 8.5f, 800, ink, Align.CENTER, maxW = tw - 6f, letterSpacing = .02f)
                }
                spots.add(Hotspot(r, macScript("open_app", JSONObject().put("app", if (t.name == "Chrome") "Google Chrome" else t.name))))
            }
        }
        y = chipsTop

        // ---- power chips: Wake · Sleep (armed) · Lock · Screen off · Peek
        if (chipRows >= 1) {
            val sleepArmed = Actions.isArmed(ctx, "mac_sleep")
            data class Chip(val label: String, val icon: Int, val color: Long, val pi: PendingIntent, val bg: Int? = null)
            val chips = listOf(
                Chip("Wake", R.drawable.ic_power, pal.lime, a.wake(cfg["mac_mac"], cfg["mac_broadcast"])),
                Chip(if (sleepArmed) "Sure?" else "Sleep", R.drawable.ic_power_sleep, pal.purple, a.armed("mac_sleep", "script", cfg["mac_script_prefix"] + "sleep"), if (sleepArmed) pal.mix(pal.purple, .45f) else null),
                Chip("Lock", R.drawable.ic_lock, pal.muted, macScript("lock")),
                Chip("Screen off", R.drawable.ic_monitor_off, pal.cyan, macScript("display_off")),
                Chip("Peek", R.drawable.ic_monitor_screenshot, pal.pink, macScript("screenshot")),
            )
            val cw = (innerW - gap * (chips.size - 1)) / chips.size
            chips.forEachIndexed { i, c ->
                val r = RectF(padX + i * (cw + gap), y, padX + i * (cw + gap) + cw, y + chipH)
                val ic = if (cw >= 88f) c.icon else null
                val narrow = cw < 64f
                val lab = if (narrow && c.label == "Screen off") "Screen" else c.label
                if (c.bg != null) p.chip(r, lab, ic, c.color.toInt(), bg = c.bg, size = if (narrow) 10.5f else 11f, pad = if (narrow) 4f else 6f) else p.chip(r, lab, ic, c.color.toInt(), size = if (narrow) 10.5f else 11f, pad = if (narrow) 4f else 6f)
                spots.add(Hotspot(r, c.pi))
            }
            y += chipH + 8f
        }

        // ---- keep awake: 1h 2h 4h 8h Off
        if (chipRows >= 2) {
            val until = st?.str("caffeinate_until")?.takeIf { it.isNotEmpty() && it != "None" }
            p.icon(R.drawable.ic_coffee, padX + 8f, y + chipH / 2, 16f, (if (caff) pal.orange else pal.muted).toInt())
            val lab = if (caff) "Awake" + (until?.let { " · $it" } ?: "") else "Can sleep"
            val lw = p.text(lab, padX + 22f, y + chipH / 2, 11f, 700, (if (caff) pal.ink else pal.muted).toInt(), maxW = 110f)
            val x0 = padX + 22f + lw + 10f
            val opts = listOf("1h" to 1, "2h" to 2, "4h" to 4, "8h" to 8, "Off" to 0)
            val cw = (p.w - padX - x0 - gap * (opts.size - 1)) / opts.size
            opts.forEachIndexed { i, (l, h) ->
                val r = RectF(x0 + i * (cw + gap), y, x0 + i * (cw + gap) + cw, y + chipH)
                val col = if (h == 0) pal.muted else pal.orange
                p.chip(r, l, null, col.toInt(), size = 11f, pad = 4f)
                spots.add(Hotspot(r, if (h == 0) macScript("decaffeinate") else macScript("caffeinate", JSONObject().put("hours", h))))
            }
            y += chipH + 8f
        }
        return spots
    }
}

/** ~/projects with git state; tap a row = select it as the Claude folder, then Start host. */
class ProjectsWidget : CardWidget() {
    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        p.card()
        val padX = 20f; val padY = 18f
        val innerW = p.w - padX * 2
        val bottom = p.h - padY
        val cfg = a.cfg
        val projects = snap[cfg["mac_status"]]?.attrs?.optJSONArray("projects") ?: JSONArray()
        val sel = Mac.folder(cfg, snap)
        var dirty = 0; var repos = 0
        for (i in 0 until projects.length()) { val q = projects.getJSONObject(i); if (q.optBoolean("git")) { repos++; if (q.optInt("dirty") > 0) dirty++ } }

        var y = padY + 10f
        val badge = Common.statusBadge(ctx, p, a, snap, p.w - padX, y)?.also { spots.add(it) }
        p.header("Projects", if (badge == null) (if (projects.length() > 0) "${projects.length()} folders · $dirty of $repos dirty" else "no data yet") else "", padX, y, innerW)
        y += 22f

        // Start host pill sits at the bottom
        val pillH = 32f
        val pill = RectF(padX, bottom - pillH, p.w - padX, bottom)
        p.gradient(pill, pillH / 2, pal.gradLime, 160f, shadow = 6f)
        run {
            val lab = "Start host · ${Mac.project(cfg, snap)}"
            val tw = p.measure(lab, 12f, 800).coerceAtMost(innerW - 60f)
            val x0 = pill.centerX() - (tw + 22f) / 2
            p.icon(R.drawable.ic_play, x0 + 8f, pill.centerY(), 16f, pal.inkOnLime.toInt())
            p.text(lab, x0 + 22f, pill.centerY(), 12f, 800, pal.inkOnLime.toInt(), maxW = tw)
        }
        spots.add(Hotspot(pill, a.script(cfg["claude_start"])))
        val listBottom = pill.top - 8f

        if (projects.length() == 0) { Common.empty(p, RectF(padX, y, p.w - padX, listBottom), R.drawable.ic_source_branch, "No project list yet", "the Mac status sensor fills the projects attribute"); return spots }
        // rows stretch a little so the list reaches the Start host pill
        val fit = ((listBottom - y + 2f) / 36f).toInt().coerceAtLeast(1)
        val shownRows = minOf(fit, projects.length(), 19 - spots.size)
        val rowH = ((listBottom - y) / shownRows).coerceIn(36f, 48f)
        for (i in 0 until projects.length()) {
            if (y + rowH > listBottom + 2f || spots.size >= 19) break
            val q = projects.getJSONObject(i)
            val path = q.optString("path"); val name = q.optString("name")
            val isSel = sel.isNotEmpty() && sel == path
            val r = RectF(padX, y, p.w - padX, y + rowH - 4f)
            if (isSel) { p.rrect(r, 12f, pal.mix(pal.cyan, .18f)); p.outline(RectF(r.left + .5f, r.top + .5f, r.right - .5f, r.bottom - .5f), 12f, Palette.alpha(pal.cyan, .5f)) }
            else p.rrect(r, 12f, pal.surface2)
            p.icon(if (q.optBoolean("git")) R.drawable.ic_source_branch else R.drawable.ic_folder_open, r.left + 16f, r.centerY(), 15f, (if (isSel) pal.cyan else pal.muted).toInt())
            // right: dirty count pill + last activity
            var rx = r.right - 10f
            val last = q.optLong("last")
            if (last > 0) { val ago = Mac.age(last).let { if (it.endsWith("min")) it else it.substringBefore(' ') }; rx -= p.text(ago, rx, r.centerY(), 11f, 400, pal.muted, Align.RIGHT) + 8f }
            val d = q.optInt("dirty")
            if (d > 0) { val w = p.pill(0f, 0f, "$d", 0, 0, size = 10f, height = 18f, padX = 7f, draw = false); p.pill(rx - w, r.centerY() - 9f, "$d", pal.mix(pal.orange, .25f), pal.orange.toInt(), size = 10f, height = 18f, padX = 7f); rx -= w + 8f }
            val branch = q.optString("branch").takeIf { it.isNotEmpty() && it != "null" }
            val nw = p.text(name, r.left + 30f, r.centerY(), 13f, if (isSel) 800 else 600, pal.ink, maxW = (rx - r.left - 30f) * .6f)
            if (branch != null) p.text("· $branch", r.left + 30f + nw + 5f, r.centerY(), 11f, 400, pal.muted, maxW = rx - (r.left + 30f + nw + 5f))
            spots.add(Hotspot(r, a.service("input_text", "set_value", JSONObject().put("entity_id", cfg["claude_folder"]).put("value", path), cfg["claude_folder"] to path)))
            y += rowH
        }
        return spots
    }
}

/** The last screenshot of the Mac (camera.mac_screen). Tap = take a new one. */
class MacScreenWidget : CardWidget() {
    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        p.card()
        val padX = 20f; val padY = 18f
        val innerW = p.w - padX * 2
        val bottom = p.h - padY
        val cfg = a.cfg
        val st = snap[cfg["mac_status"]]
        val ts = st?.num("screen_ts")?.toLong()?.takeIf { it > 0 }
        val taken = ts?.let { java.time.Instant.ofEpochSecond(it).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) }
        var y = padY + 10f
        val badge = Common.statusBadge(ctx, p, a, snap, p.w - padX, y)?.also { spots.add(it) }
        p.header("Mac screen", if (badge == null) (taken?.let { "taken $it · tap for a new one" } ?: "tap to take a screenshot") else "", padX, y, innerW)
        y += 20f
        val frame = RectF(padX, y, p.w - padX, bottom)
        val img = Common.cachedArt(ctx, MAC_SCREEN_KEY)
        if (img != null) {
            // keep the Mac's aspect ratio inside the frame
            val s = min(frame.width() / img.width, frame.height() / img.height)
            val w = img.width * s; val h = img.height * s
            val r = RectF(frame.centerX() - w / 2, frame.centerY() - h / 2, frame.centerX() + w / 2, frame.centerY() + h / 2)
            p.rrect(RectF(r.left - 1f, r.top - 1f, r.right + 1f, r.bottom + 1f), 13f, pal.border)
            p.canvas.save(); p.clipRound(r, 12f)
            val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
            p.canvas.drawBitmap(img, null, r, paint)
            p.canvas.restore()
        } else {
            p.rrect(frame, 14f, pal.surface2)
            if (frame.height() >= 70f) Common.empty(p, frame, R.drawable.ic_monitor_screenshot, "No screenshot yet", "Tap to take one")
            else p.text("No screenshot yet · tap", frame.centerX(), frame.centerY(), 12f, 500, pal.muted, Align.CENTER)
        }
        spots.add(Hotspot(RectF(0f, 0f, p.w, p.h), a.script(cfg["mac_script_prefix"] + "screenshot")))
        return spots
    }

    companion object { const val MAC_SCREEN_KEY = "mac_screen" }
}

// Picker entries with a 2x2 default size (same drawing, mini layout kicks in under 200dp wide)
class ClaudeSmallWidget : ClaudeWidget()
