package in_.weenja.hawidgets.widgets

import android.app.PendingIntent
import android.content.Context
import android.graphics.RectF
import in_.weenja.hawidgets.Cfg
import in_.weenja.hawidgets.Prefs
import in_.weenja.hawidgets.R
import in_.weenja.hawidgets.ha.Snapshot
import in_.weenja.hawidgets.ui.Align
import in_.weenja.hawidgets.ui.Painter
import in_.weenja.hawidgets.ui.Palette
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One-action tiles for 1x1 / 2x1 / 2x2 cells: the whole widget is a device_tile. Gradient when the
 * thing is on, dark when off; icon, name and a small state line. Tap = the action.
 */
abstract class TileWidget : CardWidget() {
    override val freshness = false

    class Spec(val name: String, val sub: String, val icon: Int, val grad: Pair<Long, Long>, val ink: Long,
               val active: Boolean, val action: PendingIntent?, val missing: Boolean = false)

    abstract fun spec(ctx: Context, snap: Snapshot, a: Actions): Spec

    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val s = spec(ctx, snap, a)
        val r = RectF(0f, 0f, p.w, p.h)
        val radius = min(22f, min(p.w, p.h) * .28f)
        if (s.active && !s.missing) p.gradient(r, radius, s.grad, 160f) else { p.rrect(r, radius, pal.surface2); p.outline(RectF(.5f, .5f, p.w - .5f, p.h - .5f), radius, pal.border.toInt()) }
        val ink = if (s.active && !s.missing) s.ink.toInt() else pal.muted.toInt()
        val tiny = p.h < 76f && p.w < 140f          // 1x1
        val wide = p.w >= 140f && p.h < 100f        // 2x1 / 3x1
        when {
            tiny -> {
                p.icon(s.icon, p.w / 2, p.h / 2 - 8f, min(30f, p.h * .42f), ink, alpha = if (s.missing) .45f else 1f, shadow = s.active)
                p.text(s.name, p.w / 2, p.h - 13f, 10f, 800, ink, Align.CENTER, maxW = p.w - 10f)
            }
            wide -> {
                p.icon(s.icon, 14f + 16f, p.h / 2, 30f, ink, alpha = if (s.missing) .45f else 1f, shadow = s.active)
                p.text(s.name, 58f, p.h / 2 - (if (s.sub.isNotEmpty()) 8f else 0f), 14f, 800, ink, maxW = p.w - 70f)
                if (s.sub.isNotEmpty()) p.text(s.sub, 58f, p.h / 2 + 9f, 11f, 500, Palette.alpha(ink, .8f), maxW = p.w - 70f)
            }
            else -> {
                p.icon(s.icon, 16f + 18f, 16f + 18f, 36f, ink, alpha = if (s.missing) .45f else 1f, shadow = s.active)
                p.text(s.name, 16f, p.h - (if (s.sub.isNotEmpty()) 34f else 22f), 15f, 800, ink, maxW = p.w - 32f)
                if (s.sub.isNotEmpty()) p.text(s.sub, 16f, p.h - 18f, 11f, 500, Palette.alpha(ink, .8f), maxW = p.w - 32f)
            }
        }
        val spots = ArrayList<Hotspot>()
        if (s.action != null && Prefs(ctx).isLoggedIn) spots.add(Hotspot(r, s.action))
        return spots
    }
}

private fun pctOf(v: Double?) = v?.let { "${(it / 2.55).roundToInt()}%" }

/**
 * The generic tile: any entity (the widget's own slot, else the first favorite). Tap = what the shared
 * rules pick (toggle, open / close, lock, run a scene…); unlocking and opening a garage need a second tap.
 */
class BulbTile : TileWidget() {
    override fun spec(ctx: Context, snap: Snapshot, a: Actions): Spec {
        val pal = Palette.of(ctx)
        val id = a.cfg.one("entity") ?: a.cfg["light_bulb"].takeIf { it.isNotEmpty() }
            ?: in_.weenja.hawidgets.core.Planner.fillTaps(in_.weenja.hawidgets.HomeStore.home(ctx, snap), 1).firstOrNull()
        val e = snap.core(id) ?: return Spec(id?.substringAfter('.')?.replace('_', ' ') ?: "Tile", if (id == null) "tap to choose" else "not found", R.drawable.ic_help_circle_outline,
            pal.gradCyan, pal.inkOnCyan, false, a.configure(), true)
        val accent = in_.weenja.hawidgets.core.Rules.accent(e)
        val call = in_.weenja.hawidgets.core.Rules.tapAction(e)
        val armed = call?.confirm == true && a.isArmed("tap:${e.id}:${call.service}")
        val icon = e.icon.takeIf { in_.weenja.hawidgets.ui.Icons.has(it) } ?: in_.weenja.hawidgets.core.Rules.icon(e)
        return Spec(a.cfg.title.ifBlank { e.name }, if (armed) "tap again" else in_.weenja.hawidgets.core.Rules.stateLabel(e, System.currentTimeMillis()),
            in_.weenja.hawidgets.ui.Icons.of(icon), pal.grad(accent), pal.inkOn(accent), in_.weenja.hawidgets.core.Rules.isActive(e) || in_.weenja.hawidgets.core.Rules.isAlert(e),
            if (call != null) a.tap(e) else a.moreInfo(e.id), !e.available)
    }
}

class TubeTile : TileWidget() {
    override fun spec(ctx: Context, snap: Snapshot, a: Actions): Spec {
        val l = Lights.tube(a.cfg, snap); val on = l?.isOn == true; val pal = Palette.of(ctx)
        return Spec(l?.name ?: "Tube", if (l == null) "not found" else if (on) (pctOf(l.attrs.opt("brightness")?.toString()?.toDoubleOrNull()) ?: "on") else "off",
            R.drawable.ic_lightbulb_fluorescent_tube, pal.gradOrange, pal.inkOnOrange, on, l?.let { a.toggle(it.id, on) }, l == null)
    }
}

class FanTile : TileWidget() {
    override fun spec(ctx: Context, snap: Snapshot, a: Actions): Spec {
        val f = Lights.fan(a.cfg, snap); val on = f?.isOn == true; val pal = Palette.of(ctx)
        return Spec("Fan", if (f == null) "not set up" else if (on) (f.attrs.opt("percentage")?.toString()?.toDoubleOrNull()?.let { "${it.roundToInt()}%" } ?: "on") else "off",
            if (on) R.drawable.ic_fan else R.drawable.ic_fan_off, pal.gradPurple, pal.inkOnPurple, on, f?.let { a.toggle(it.id, on) }, f == null)
    }
}

class TvTile : TileWidget() {
    override fun spec(ctx: Context, snap: Snapshot, a: Actions): Spec {
        val tv = Lights.tv(a.cfg, snap); val id = tv?.id ?: ""; val pal = Palette.of(ctx)
        val on = tv != null && tv.state !in setOf("off", "standby", "unavailable", "unknown")
        return Spec("TV", if (tv == null) "not found" else if (on) tv.str("app_name").ifEmpty { "on" } else "off", if (on) R.drawable.ic_television_play else R.drawable.ic_television,
            pal.gradPink, pal.inkOnPink, on, if (tv == null) a.configure() else a.service("media_player", "toggle", JSONObject().put("entity_id", id), id to if (on) "off" else "on"), tv == null)
    }
}

class MovieTile : TileWidget() {
    override fun spec(ctx: Context, snap: Snapshot, a: Actions): Spec {
        val pal = Palette.of(ctx)
        return Spec("Movie", "TV on · lights off", R.drawable.ic_movie_open, pal.gradPurple, pal.inkOnPurple, true, a.script(a.cfg["script_movie"]))
    }
}

class AllOffTile : TileWidget() {
    override fun spec(ctx: Context, snap: Snapshot, a: Actions): Spec {
        val pal = Palette.of(ctx)
        val ids = Lights.allOff(a.cfg, snap)
        val n = ids.count { snap[it]?.active == true }
        return Spec("All off", if (n == 0) "all quiet" else "$n on", R.drawable.ic_power_sleep, pal.gradPink, pal.inkOnPink, n > 0,
            a.service("homeassistant", "turn_off", JSONObject().put("entity_id", JSONArray(ids))))
    }
}

class ClaudeTile : TileWidget() {
    override fun spec(ctx: Context, snap: Snapshot, a: Actions): Spec {
        val pal = Palette.of(ctx)
        val cfg = a.cfg
        val live = snap[cfg["mac_status"]]?.num("active_sessions")?.roundToInt() ?: 0
        val on = Mac.online(cfg, snap)
        return Spec("Claude", if (!on) "Mac offline" else if (live > 0) "$live live · ${Mac.project(cfg, snap)}" else "start in ${Mac.project(cfg, snap)}",
            R.drawable.ic_robot, pal.gradLime, pal.inkOnLime, live > 0, a.script(cfg["claude_start"]))
    }
}

class WakePcTile : TileWidget() {
    override fun spec(ctx: Context, snap: Snapshot, a: Actions): Spec {
        val cfg = a.cfg; val pal = Palette.of(ctx); val on = snap.on(cfg["pc_active"])
        return Spec("Gaming PC", if (on) "online" else if (cfg["pc_mac"].isBlank()) "set MAC in app" else "tap to wake", R.drawable.ic_gamepad_variant, pal.gradLime, pal.inkOnLime, on,
            a.wake(cfg["pc_mac"], cfg["pc_broadcast"]))
    }
}

class LockMacTile : TileWidget() {
    override fun spec(ctx: Context, snap: Snapshot, a: Actions): Spec {
        val cfg = a.cfg; val pal = Palette.of(ctx); val on = Mac.online(cfg, snap)
        return Spec("Lock Mac", if (on) "online" else "offline", R.drawable.ic_lock, pal.gradCyan, pal.inkOnCyan, on, a.script(cfg["mac_script_prefix"] + "lock"))
    }
}

class MusicTile : TileWidget() {
    override fun spec(ctx: Context, snap: Snapshot, a: Actions): Spec {
        val pal = Palette.of(ctx)
        val cfg = a.cfg
        val e = NowWidget.pick(cfg, snap); val playing = e?.state == "playing"
        val t = NowWidget.transport(cfg, snap)
        val pi = if (t != null) a.service("media_player", "media_play_pause", JSONObject().put("entity_id", t.id), t.id to if (playing) "paused" else "playing")
                 else if (cfg["media_music"].startsWith("script.")) a.script(cfg["media_music"].removePrefix("script."))
                 else a.service("media_player", "media_play", JSONObject().put("entity_id", cfg["media_music"]))
        return Spec(if (playing) "Playing" else "Music", if (playing) e!!.str("media_title").ifEmpty { "tap to pause" } else "tap to play", if (playing) R.drawable.ic_pause else R.drawable.ic_play,
            pal.gradCyan, pal.inkOnCyan, playing, pi)
    }
}

class WaterTile : TileWidget() {
    override fun spec(ctx: Context, snap: Snapshot, a: Actions): Spec {
        val pal = Palette.of(ctx)
        val cfg = a.cfg
        val w = snap.num(cfg["water"])
        val goal = snap.num(cfg["water_goal"]).takeIf { it > 0 } ?: 2500.0
        return Spec("Water", "+250 · ${w.roundToInt()} of ${goal.roundToInt()} ml", R.drawable.ic_cup_water, pal.gradCyan, pal.inkOnCyan, w >= goal,
            a.script(cfg["script_water"], JSONObject().put("ml", 250)))
    }
}

class ScreenOffTile : TileWidget() {
    override fun spec(ctx: Context, snap: Snapshot, a: Actions): Spec {
        val cfg = a.cfg; val pal = Palette.of(ctx); val on = Mac.online(cfg, snap)
        return Spec("Screen off", if (on) "Mac display" else "Mac offline", R.drawable.ic_monitor_off, pal.gradPurple, pal.inkOnPurple, on, a.script(cfg["mac_script_prefix"] + "display_off"))
    }
}
