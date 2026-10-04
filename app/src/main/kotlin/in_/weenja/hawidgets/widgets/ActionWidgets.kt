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
import kotlin.math.min
import kotlin.math.roundToInt

/** A round `remote_key`: tinted glyph on a dark key, or a gradient key with an ink glyph. */
private fun Painter.remoteKey(r: RectF, icon: Int, color: Int, grad: Pair<Long, Long>? = null, size: Float = 22f) {
    if (grad != null) gradient(r, min(r.width(), r.height()) * .34f, grad, 160f, shadow = 6f) else {
        rrect(r, min(r.width(), r.height()) * .34f, p.surface2)
        outline(RectF(r.left + .5f, r.top + .5f, r.right - .5f, r.bottom - .5f), min(r.width(), r.height()) * .34f, p.border.toInt())
    }
    icon(icon, r.centerX(), r.centerY(), size, color)
}

/**
 * The hero card's TV remote: power / home / back / play-pause / Kodi / YouTube keys, a D-pad when
 * tall, and the Kodi "continue" pick when the menu is filled.
 */
class TvRemoteWidget : CardWidget() {
    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        p.card()
        val padX = 20f; val padY = 18f
        val innerW = p.w - padX * 2
        val bottom = p.h - padY
        val cfg = a.cfg
        val tv = Lights.tv(cfg, snap)
        val tvId = tv?.id ?: ""
        val remoteId = Lights.tvRemote(cfg, snap)?.id ?: ""
        val on = tv != null && tv.state !in setOf("off", "standby", "unavailable", "unknown")
        val app = tv?.str("app_name")?.takeIf { it.isNotEmpty() } ?: ""
        val title = tv?.str("media_title")?.takeIf { it.isNotEmpty() }
        val TV = JSONObject().put("entity_id", tvId)
        fun cmd(c: String) = if (remoteId.isEmpty()) a.configure() else a.service("remote", "send_command", JSONObject().put("entity_id", remoteId).put("command", c))
        if (tv == null) {
            p.card()
            Common.empty(p, RectF(20f, 18f, p.w - 20f, p.h - 18f), R.drawable.ic_television, "No TV found", "Tap to pick your TV and its remote")
            return listOf(Hotspot(RectF(0f, 0f, p.w, p.h), a.configure(), "Choose a TV"))
        }

        var y = padY + 10f
        // ---- keys only when the widget is a short strip (no room for a header)
        if (p.h < 88f) {
            val g = 6f; val n = 6
            val kw = ((p.w - 20f - g * (n - 1)) / n).coerceAtMost(p.h - 14f)
            val kh = min(kw, p.h - 14f)
            val x0 = (p.w - (kw * n + g * (n - 1))) / 2; val y0 = (p.h - kh) / 2
            val TVs = JSONObject().put("entity_id", tvId)
            val keysS = listOf(
                Triple(R.drawable.ic_power, pal.orange.toInt(), a.service("media_player", "toggle", TVs, tvId to if (on) "off" else "on")),
                Triple(R.drawable.ic_home, pal.ink.toInt(), cmd("HOME")), Triple(R.drawable.ic_arrow_left, pal.ink.toInt(), cmd("BACK")),
                Triple(R.drawable.ic_play_pause, pal.pink.toInt(), a.service("media_player", "media_play_pause", TVs)),
                Triple(R.drawable.ic_chevron_up, pal.ink.toInt(), cmd("UP")), Triple(R.drawable.ic_chevron_down, pal.ink.toInt(), cmd("DOWN")))
            keysS.forEachIndexed { i, (ic, col, pi) ->
                val r = RectF(x0 + i * (kw + g), y0, x0 + i * (kw + g) + kw, y0 + kh)
                p.remoteKey(r, ic, col, size = (kh * .5f).coerceIn(16f, 24f)); spots.add(Hotspot(r, pi))
            }
            return spots
        }
        val badge = Common.statusBadge(ctx, p, a, snap, p.w - padX, y)?.also { spots.add(it) }
        val meta = if (!on) "off" else listOfNotNull(app.takeIf { it.isNotEmpty() }, title).joinToString(" · ")
        p.header(tv?.name?.takeIf { it.isNotEmpty() } ?: "TV", if (badge == null) meta else "", padX, y, innerW)
        y += 22f

        // ---- key row: power · home · back · play/pause · kodi · youtube
        data class Key(val icon: Int, val color: Int, val grad: Pair<Long, Long>?, val pi: PendingIntent)
        val keys = listOf(
            Key(R.drawable.ic_power, pal.orange.toInt(), null, a.service("media_player", "toggle", TV, tvId to if (on) "off" else "on")),
            Key(R.drawable.ic_home, pal.ink.toInt(), null, cmd("HOME")),
            Key(R.drawable.ic_arrow_left, pal.ink.toInt(), null, cmd("BACK")),
            Key(R.drawable.ic_play_pause, pal.pink.toInt(), null, a.service("media_player", "media_play_pause", TV)),
            if (cfg.has("script_kodi_open")) Key(R.drawable.ic_kodi, 0xff06131b.toInt(), pal.gradCyan, a.script(cfg["script_kodi_open"]))
            else Key(R.drawable.ic_volume_minus, pal.cyan.toInt(), null, a.service("media_player", "volume_down", TV)),
            if (cfg.has("script_tv_app") && cfg.has("tv_youtube")) Key(R.drawable.ic_youtube, 0xffffffff.toInt(), 0xffff5c5cL to 0xffe00000L, a.script(cfg["script_tv_app"], JSONObject().put("app", cfg["tv_youtube"])))
            else Key(R.drawable.ic_volume_plus, pal.cyan.toInt(), null, a.service("media_player", "volume_up", TV)),
        )
        // ---- plan the height: Kodi picks (30 dp each) pinned at the bottom, the D-pad and keys scale with what is left
        val km = cfg["kodi_menu"].takeIf { it.isNotEmpty() }?.let { snap[it] }
        val cont = km?.attrs?.optJSONObject("continue")
        val news = km?.attrs?.optJSONArray("new") ?: JSONArray()
        val pickCount = (if (cont != null) 1 else 0) + news.length()
        val wantDpad = bottom - y >= 150f
        val gap = 8f
        val kw = ((innerW - gap * (keys.size - 1)) / keys.size).coerceAtMost(56f)
        val rowW = kw * keys.size + gap * (keys.size - 1)
        val x0 = padX + (innerW - rowW) / 2
        // how many picks fit under a minimal D-pad (3 x 46 + 2 x 6 + 12) and minimal keys (48 + 12)
        val minAbove = 48f + 12f + (if (wantDpad) 3 * 46f + 2 * 6f + 12f else 0f)
        val picksShown = if (pickCount == 0) 0 else ((bottom - y - minAbove + 6f) / 36f).toInt().coerceIn(0, pickCount)
        val picksH = if (picksShown > 0) picksShown * 36f - 6f else if (pickCount == 0 && bottom - y - minAbove >= 30f) 30f else 0f
        val room = bottom - y - picksH - (if (picksH > 0f) 12f else 0f)
        val kh = if (wantDpad) min(kw, ((room - 3 * 6f - 12f) / 4f).coerceIn(48f, 64f)) else min(kw, (room).coerceIn(40f, 56f))
        keys.forEachIndexed { i, k ->
            val r = RectF(x0 + i * (kw + gap), y, x0 + i * (kw + gap) + kw, y + kh)
            p.remoteKey(r, k.icon, k.color, k.grad)
            spots.add(Hotspot(r, k.pi))
        }
        y += kh + 12f

        // ---- D-pad (when there is room): up / left / select / right / down as a cross
        if (wantDpad) {
            val g = 6f
            val cell = ((room - kh - 12f - 2 * g) / 3f).coerceIn(46f, 76f)
            y += ((room - kh - 12f - (3 * cell + 2 * g)) / 2).coerceAtLeast(0f)
            val cx = p.w / 2; val top = y
            fun cellR(col: Int, row: Int) = RectF(cx + (col - 1.5f) * (cell + g) + g / 2, top + row * (cell + g), cx + (col - .5f) * (cell + g) + g / 2, top + row * (cell + g) + cell)
            val up = cellR(1, 0); val left = cellR(0, 1); val sel = cellR(1, 1); val right = cellR(2, 1); val down = cellR(1, 2)
            val gs = (cell * .55f).coerceIn(26f, 34f)
            p.remoteKey(up, R.drawable.ic_chevron_up, pal.ink.toInt(), size = gs); p.remoteKey(down, R.drawable.ic_chevron_down, pal.ink.toInt(), size = gs)
            p.remoteKey(left, R.drawable.ic_chevron_left, pal.ink.toInt(), size = gs); p.remoteKey(right, R.drawable.ic_chevron_right, pal.ink.toInt(), size = gs)
            p.rrect(sel, cell * .34f, pal.mix(pal.cyan, .3f)); p.text("OK", sel.centerX(), sel.centerY(), (cell * .3f).coerceIn(13f, 18f), 800, pal.cyan)
            spots.add(Hotspot(up, cmd("UP"))); spots.add(Hotspot(down, cmd("DOWN"))); spots.add(Hotspot(left, cmd("LEFT"))); spots.add(Hotspot(right, cmd("RIGHT"))); spots.add(Hotspot(sel, cmd("SELECT")))
            // side keys: movie mode + menu
            val side = (cell * .95f).coerceAtMost(56f)
            val mv = RectF(padX, top + cell + g, padX + side, top + cell + g + cell); val mn = RectF(p.w - padX - side, top + cell + g, p.w - padX, top + cell + g + cell)
            val muted = tv.attrs.optBoolean("is_volume_muted")
            if (cfg.has("script_movie")) { p.remoteKey(mv, R.drawable.ic_movie_open, pal.purple.toInt()); spots.add(Hotspot(mv, a.script(cfg["script_movie"]), "Movie mode")) }
            else { p.remoteKey(mv, if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_high, pal.purple.toInt()); spots.add(Hotspot(mv, a.service("media_player", "volume_mute", JSONObject().put("entity_id", tvId).put("is_volume_muted", !muted)), "Mute")) }
            p.remoteKey(mn, R.drawable.ic_format_list_checks, pal.ink.toInt()); spots.add(Hotspot(mn, cmd("MENU"), "Menu"))
            y = top + 3 * cell + 2 * g + 12f
        }

        // ---- Kodi pick: continue + new (sensor.kodi_menu, filled by script.kodi_open), pinned to the bottom
        if (picksH > 0f) y = bottom - picksH
        val picks = ArrayList<Pair<String, PendingIntent>>()
        if (cont != null) picks.add("Continue · ${cont.optString("label")} · ${cont.optInt("left")} min left" to
            a.script(cfg["script_kodi_play"], JSONObject().put("kind", cont.optString("kind")).put("id", cont.optInt("id")).put("resume", true)))
        for (i in 0 until news.length()) {
            val it = news.getJSONObject(i)
            picks.add("${it.optString("label")} · ${it.optString("hint")}" to a.script(cfg["script_kodi_play"], JSONObject().put("kind", it.optString("kind")).put("id", it.optInt("id")).put("resume", false)))
        }
        if (picks.isNotEmpty() && bottom - y >= 30f) {
            for ((i, pk) in picks.withIndex()) {
                if (y + 30f > bottom + 2f) break
                val r = RectF(padX, y, p.w - padX, y + 30f)
                p.chip(r, pk.first, if (i == 0 && cont != null) R.drawable.ic_play else R.drawable.ic_television_play, if (i == 0 && cont != null) pal.cyan.toInt() else pal.muted.toInt(), size = 11f)
                spots.add(Hotspot(r, pk.second))
                y += 36f
            }
        } else if (bottom - y >= 30f && cfg.has("script_movie")) {
            val r = RectF(padX, y, p.w - padX, y + 30f)
            p.chip(r, "Movie mode", R.drawable.ic_movie_open, pal.purple.toInt(), size = 11f)
            spots.add(Hotspot(r, a.script(cfg["script_movie"])))
        }
        return spots
    }
}

/** Gaming PC: on/off + hours today, game picker ‹ › and Wake / Launch / Valorant chips. */
class GamingWidget : CardWidget() {
    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        p.card()
        val padX = 20f; val padY = 18f
        val innerW = p.w - padX * 2
        val bottom = p.h - padY
        val cfg = a.cfg
        val on = snap.on(cfg["pc_active"])
        val hours = snap.num(cfg["pc_usage"])
        val game = snap[cfg["pc_game"]]
        val riot = cfg["pc_launcher"].takeIf { it.isNotEmpty() }?.let { snap[it]?.state }?.let { it == "on" || it == "True" || it == "true" } ?: false
        val wake = a.wake(cfg["pc_mac"], cfg["pc_broadcast"])
        val noMac = cfg["pc_mac"].isBlank()

        var y = padY + 10f
        val badge = Common.statusBadge(ctx, p, a, snap, p.w - padX, y)?.also { spots.add(it) }
        p.header("Gaming PC", if (badge == null) (if (on) "on · %.1fh today".format(hours) else "off · %.1fh today".format(hours)) else "", padX, y, innerW)
        y += 22f

        // status tile row: big gamepad tile + stats (only when the widget is tall enough for all three rows)
        val chipsTop = bottom - 32f
        val pickerTop = chipsTop - 8f - 32f
        val heroRoom = pickerTop - 12f - y
        val tileH = heroRoom.coerceIn(60f, 120f)
        val tile = RectF(padX, y, padX + tileH, y + tileH)
        if (heroRoom >= 60f) {
        y += ((heroRoom - tileH) / 2).coerceAtLeast(0f); tile.offset(0f, y - tile.top)
        if (on) p.gradient(tile, 18f, pal.gradLime, 160f, shadow = 8f) else p.rrect(tile, 18f, pal.surface2)
        p.icon(R.drawable.ic_gamepad_variant, tile.centerX(), tile.centerY(), (tileH * .55f).coerceAtMost(48f), if (on) pal.inkOnLime.toInt() else pal.muted.toInt(), shadow = on)
        val sx = tile.right + 14f
        val cy = tile.centerY()
        p.text(if (on) "Online" else "Asleep", sx, cy - 11f, 20f, 700, pal.ink)
        p.text(if (riot) "Launcher running" else if (on) "ready to launch" else if (noMac) "set the MAC address in the app" else "wake it with the chip", sx, cy + 11f, 12f, 400, pal.muted, maxW = p.w - padX - sx - 70f)
        p.text("%.1fh".format(hours), p.w - padX, cy - 11f, 20f, 700, pal.ink, Align.RIGHT)
        p.text("today", p.w - padX, cy + 11f, 12f, 400, pal.muted, Align.RIGHT)
        spots.add(Hotspot(tile, wake))
        y = pickerTop
        }

        // game picker ‹ [Game · name] ›
        if (game != null && y + 32f <= bottom) {
            val arrowW = 36f; val h = 32f; val gap = 8f
            val left = RectF(padX, y, padX + arrowW, y + h); val right = RectF(p.w - padX - arrowW, y, p.w - padX, y + h)
            val mid = RectF(left.right + gap, y, right.left - gap, y + h)
            p.chip(left, "", R.drawable.ic_chevron_left, pal.muted.toInt()); p.chip(right, "", R.drawable.ic_chevron_right, pal.muted.toInt())
            p.rrect(mid, h / 2, pal.mix(pal.purple))
            p.icon(R.drawable.ic_gamepad_variant, mid.left + 18f, mid.centerY(), 16f, pal.purple.toInt())
            val g = if (game.state in setOf("unknown", "unavailable", "off", "")) "pick a game" else game.state
            p.text("Game", mid.left + 32f, mid.centerY(), 12f, 600, pal.muted)
            val fx = mid.left + 32f + p.measure("Game", 12f, 600) + 6f
            p.text("· $g", fx, mid.centerY(), 12f, 700, pal.ink, maxW = mid.right - fx - 10f)
            val t = JSONObject().put("entity_id", game.id)
            spots.add(Hotspot(left, a.service("select", "select_previous", t))); spots.add(Hotspot(right, a.service("select", "select_next", t)))
            spots.add(Hotspot(mid, a.open(cfg["path_gaming"])))
            y += h + 8f
        }
        // chips: Wake · Launch · quick launch, pinned to the bottom
        if (y + 32f <= bottom + 2f) {
            y = maxOf(y, chipsTop)
            val gap = 8f; val cw = (innerW - gap * 2) / 3; val h = 32f
            val chips = listOf(Triple("Wake", R.drawable.ic_power, pal.lime to wake),
                Triple("Launch", R.drawable.ic_rocket_launch, pal.purple to a.script(cfg["script_launch_game"])),
                Triple(cfg["game_quick_label"].ifEmpty { "Quick" }, R.drawable.ic_crosshairs, pal.pink to a.script(cfg["script_game_quick"])))
            chips.forEachIndexed { i, (lab, ic, cp) ->
                val r = RectF(padX + i * (cw + gap), y, padX + i * (cw + gap) + cw, y + h)
                p.chip(r, lab, if (cw >= 84f) ic else null, cp.first.toInt(), size = 12f, pad = 8f)
                spots.add(Hotspot(r, cp.second))
            }
            y += h + 10f
        }
        // slim status row when the big tile did not fit but there is still a little room
        if (bottom - y >= 30f && bottom - y < 120f) {
            p.iconBox(padX, y, 28f, 9f, R.drawable.ic_gamepad_variant, (if (on) pal.lime else pal.muted).toInt(), 16f)
            p.text(if (on) "Online" else "Asleep", padX + 36f, y + 8f, 13f, 700, pal.ink)
            p.text(if (riot) "Launcher running" else if (on) "ready to launch" else if (noMac) "set the MAC address in the app" else "tap Wake to switch it on", padX + 36f, y + 22f, 11f, 400, pal.muted, maxW = innerW - 36f - 60f)
            p.text("%.1fh today".format(hours), p.w - padX, y + 14f, 12f, 600, pal.muted, Align.RIGHT)
        }
        return spots
    }
}

/** Scenes, scripts and buttons as gradient tiles. Empty = the home's scenes and scripts. */
class QuickActionsWidget : CardWidget() {
    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        p.card()
        val padX = 20f; val padY = 18f
        val innerW = p.w - padX * 2
        val bottom = p.h - padY
        var y = padY + 10f
        val badge = Common.statusBadge(ctx, p, a, snap, p.w - padX, y)?.also { spots.add(it) }
        val cfg = a.cfg
        val ids = cfg.slot("items").ifEmpty {
            in_.weenja.hawidgets.core.Planner.fill("scenes", in_.weenja.hawidgets.HomeStore.home(ctx, snap))?.list("items") ?: emptyList()
        }
        val es = ids.mapNotNull { snap.core(it) }
        p.header(cfg.title.ifBlank { "Scenes" }, if (badge == null) "${es.size} scene${if (es.size == 1) "" else "s"}" else "", padX, y, innerW)
        y += 20f
        if (es.isEmpty()) {
            Common.empty(p, RectF(padX, y, p.w - padX, bottom), R.drawable.ic_palette, "No scenes yet", "Tap to pick scenes and scripts")
            spots.add(Hotspot(RectF(0f, 0f, p.w, p.h), a.configure(), "Choose scenes"))
            return spots
        }
        val grads = listOf(pal.gradPurple to pal.inkOnPurple, pal.gradPink to pal.inkOnPink, pal.gradCyan to pal.inkOnCyan, pal.gradOrange to pal.inkOnOrange, pal.gradLime to pal.inkOnLime)
        val gap = 8f
        val cols = if (innerW / 3 >= 100f) 3 else 2
        val rows = ((bottom - y + gap) / (48f + gap)).toInt().coerceIn(1, 3)
        val count = min(es.size, cols * rows)
        val useRows = (count + cols - 1) / cols
        val th = ((bottom - y - gap * (useRows - 1)) / useRows).coerceIn(48f, 120f)
        y += ((bottom - y - (useRows * th + (useRows - 1) * gap)) / 2).coerceAtLeast(0f)
        for (i in 0 until count) {
            val e = es[i]
            val r = i / cols
            val inRow = if (r == useRows - 1) count - r * cols else cols
            val tw = (innerW - gap * (inRow - 1)) / inRow
            val c = i % cols
            val rect = RectF(padX + c * (tw + gap), y + r * (th + gap), padX + c * (tw + gap) + tw, y + r * (th + gap) + th)
            val (grad, inkL) = grads[i % grads.size]
            p.gradient(rect, 18f, grad, 160f, shadow = 6f)
            val ink = inkL.toInt()
            val icon = in_.weenja.hawidgets.ui.Icons.of(e.icon.takeIf { in_.weenja.hawidgets.ui.Icons.has(it) } ?: in_.weenja.hawidgets.core.Rules.icon(e))
            if (th >= 70f) {
                p.icon(icon, rect.left + 22f, rect.top + 22f, 24f, ink, shadow = true)
                p.text(e.name, rect.left + 12f, rect.bottom - 26f, 13f, 800, ink, maxW = tw - 20f)
                p.text(in_.weenja.hawidgets.core.Rules.stateLabel(e), rect.left + 12f, rect.bottom - 12f, 10f, 500, Palette.alpha(ink, .75f), maxW = tw - 20f)
            } else {
                p.icon(icon, rect.left + 20f, rect.centerY(), 22f, ink, shadow = true)
                p.text(e.name, rect.left + 36f, rect.centerY(), 12f, 800, ink, maxW = tw - 44f)
            }
            spots.add(Hotspot(rect, a.tap(e), e.name))
        }
        return spots
    }
}

/** One light: big toggle, brightness presets, colour temperature and candy colours (when supported). */
abstract class LightWidget(private val resolve: (Cfg, Snapshot) -> in_.weenja.hawidgets.ha.Entity?, private val title: String, private val iconOn: Int, private val iconOff: Int) : CardWidget() {
    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        p.card()
        val padX = 20f; val padY = 18f
        val innerW = p.w - padX * 2
        val bottom = p.h - padY
        val light = resolve(a.cfg, snap)
        val entityId = light?.id ?: "light.none"
        val on = light?.isOn == true
        val bri = light?.attrs?.opt("brightness")?.toString()?.toDoubleOrNull()?.let { (it / 2.55).roundToInt() } ?: 0
        val modes = light?.attrs?.optJSONArray("supported_color_modes")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } } ?: emptyList()
        val hasTemp = modes.any { it == "color_temp" } || light?.attrs?.has("min_color_temp_kelvin") == true
        val hasColor = modes.any { it in setOf("hs", "rgb", "rgbw", "rgbww", "xy") }
        val kelvin = light?.num("color_temp_kelvin")?.takeIf { it > 0 }?.roundToInt()

        var y = padY + 10f
        val badge = Common.statusBadge(ctx, p, a, snap, p.w - padX, y)?.also { spots.add(it) }
        p.header(a.cfg.title.ifBlank { light?.name ?: title }, if (badge == null) (if (light == null) "tap to choose a light" else if (on) "$bri%" + (kelvin?.let { " · ${it}K" } ?: "") else "off") else "", padX, y, innerW)
        if (light == null) {
            Common.empty(p, RectF(padX, y + 20f, p.w - padX, bottom), R.drawable.ic_lightbulb, "No light yet", "Tap to pick one")
            return listOf(Hotspot(RectF(0f, 0f, p.w, p.h), a.configure(), "Choose a light"))
        }
        y += 20f
        val T = JSONObject().put("entity_id", entityId)
        fun turnOn(extra: JSONObject.() -> Unit) = a.service("light", "turn_on", JSONObject(T.toString()).apply(extra), entityId to "on")

        // hero tile: icon + state + brightness bar; tap toggles. Rows spread out when the widget is tall.
        val rowsBelow = 1 + (if (hasTemp) 1 else 0) + (if (hasColor) 1 else 0)
        val spare = (bottom - y - 74f - rowsBelow * 36f).coerceAtLeast(0f)
        val tileH = if (p.h < 230f) 62f else (74f + spare * .5f).coerceAtMost(150f)
        val rowH0 = if (p.h < 230f) 28f else (30f + spare * .15f).coerceAtMost(48f)
        val gapExtra = if (p.h < 230f) 0f else (spare * .12f).coerceAtMost(24f)
        val tile = RectF(padX, y, p.w - padX, y + tileH)
        if (on) p.gradient(tile, 18f, pal.gradOrange, 160f, shadow = 8f) else p.rrect(tile, 18f, pal.surface2)
        val ink = if (on) pal.inkOnOrange.toInt() else pal.muted.toInt()
        p.icon(if (on) iconOn else iconOff, tile.left + 34f, tile.centerY(), (tileH * .5f).coerceIn(30f, 48f), ink, shadow = on)
        val ty = tile.centerY() - (if (tileH < 70f) 12f else 14f)
        p.text(if (on) "On" else "Off", tile.left + 64f, ty, 15f, 800, ink, maxW = tile.width() - 140f)
        p.text(if (on) "$bri% · tap to switch off" else "off · tap to switch on", tile.left + 64f, ty + (if (tileH < 70f) 16f else 20f), 11f, 500, Palette.alpha(ink, .8f), maxW = tile.width() - 80f)
        p.bar(RectF(tile.left + 64f, tile.bottom - 12f, tile.right - 16f, tile.bottom - 8f), if (on) bri / 100f else 0f, ink, Palette.alpha(ink, .25f))
        light?.let { spots.add(Hotspot(tile, a.toggle(it.id, on))) }
        y += tileH + 8f + gapExtra

        // brightness chips
        val gap = if (p.h < 230f) 4f else 6f + gapExtra
        fun chipRow(labels: List<Triple<String, Int?, PendingIntent>>, color: Int, h: Float = rowH0, fixedBg: ((Int) -> Int)? = null) {
            if (y + h > bottom + 2f) return
            val cw = (innerW - gap * (labels.size - 1)) / labels.size
            labels.forEachIndexed { i, (lab, ic, pi) ->
                val r = RectF(padX + i * (cw + gap), y, padX + i * (cw + gap) + cw, y + h)
                if (fixedBg != null) { p.rrect(r, h / 2, fixedBg(i)); if (lab.isNotEmpty()) p.text(lab, r.centerX(), r.centerY(), 11f, 700, 0xff14161e.toInt(), Align.CENTER) }
                else p.chip(r, lab, if (cw >= 70f) ic else null, color, size = 11f, pad = 8f)
                spots.add(Hotspot(r, pi))
            }
            y += h + gap
        }
        chipRow(listOf(10, 30, 60, 100).map { pct -> Triple(if (pct == 100) "Max" else "$pct%", R.drawable.ic_brightness_6, turnOn { put("brightness_pct", pct) }) }, pal.orange.toInt())
        if (hasTemp) chipRow(listOf(Triple("Warm", R.drawable.ic_weather_sunset_down, turnOn { put("color_temp_kelvin", 2700) }),
            Triple("Neutral", R.drawable.ic_white_balance_sunny, turnOn { put("color_temp_kelvin", 4000) }),
            Triple("Cool", R.drawable.ic_weather_sunny, turnOn { put("color_temp_kelvin", 6000) })), pal.cyan.toInt())
        if (hasColor) {
            val candy = listOf(0xffff9f2e to "orange", 0xffff4fa3 to "pink", 0xffb5e21c to "lime", 0xff3fe3ff to "cyan", 0xff7b61ff to "purple")
            chipRow(candy.map { (c, _) -> Triple("", null, turnOn { put("rgb_color", JSONArray(listOf((c shr 16) and 0xff, (c shr 8) and 0xff, c and 0xff))) }) }, 0, fixedBg = { i -> candy[i].first.toInt() })
        }
        return spots
    }
}

class BulbWidget : LightWidget({ c, s -> Lights.bulb(c, s) }, "Light", R.drawable.ic_lightbulb_on, R.drawable.ic_lightbulb_off)
class TubeWidget : LightWidget({ c, s -> Lights.tube(c, s) }, "Light B", R.drawable.ic_lightbulb_fluorescent_tube, R.drawable.ic_lightbulb_fluorescent_tube)
