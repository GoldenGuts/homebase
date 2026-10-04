package in_.weenja.hawidgets.widgets

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import in_.weenja.hawidgets.Cfg
import in_.weenja.hawidgets.R
import in_.weenja.hawidgets.ha.Entity
import in_.weenja.hawidgets.ha.Snapshot
import in_.weenja.hawidgets.ui.Align
import in_.weenja.hawidgets.ui.Painter
import in_.weenja.hawidgets.ui.Palette
import org.json.JSONObject
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The floating "now_bar": blurred artwork, title / artist, progress, transport keys + volume.
 * Picks the same player the dashboard does: playing > paused > any available, else Spotify.
 */
open class NowWidget : CardWidget() {
    override val freshness = false

    companion object {
        /** The configured players in priority order, else every media player. */
        fun players(cfg: Cfg, snap: Snapshot): List<String> = cfg.slot("players").ifEmpty { cfg.list("media_players") }.ifEmpty { snap.byDomain("media_player").map { it.id } }

        fun pick(cfg: Cfg, snap: Snapshot): Entity? {
            val ids = players(cfg, snap)
            ids.firstNotNullOfOrNull { snap[it]?.takeIf { e -> e.state == "playing" } }?.let { return it }
            ids.firstNotNullOfOrNull { snap[it]?.takeIf { e -> e.state == "paused" } }?.let { return it }
            return ids.firstNotNullOfOrNull { snap[it]?.takeIf { e -> e.available } }
        }

        /** The player transport keys act on: the one playing or paused. */
        fun transport(cfg: Cfg, snap: Snapshot): Entity? = players(cfg, snap).firstNotNullOfOrNull { snap[it]?.takeIf { x -> x.state == "playing" || x.state == "paused" } }

        /** entity_picture of the picked player, for the prefetch in Refresh. */
        fun artUrl(cfg: Cfg, snap: Snapshot): String? = pick(cfg, snap)?.str("entity_picture")?.takeIf { it.isNotEmpty() }
    }

    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        val cfg = a.cfg
        val e = pick(cfg, snap)
        val off = e == null || e.state in setOf("unavailable", "off", "standby", "unknown")
        val playing = e?.state == "playing"
        val transport = transport(cfg, snap)
        val music = cfg["media_music"].ifEmpty { e?.id ?: players(cfg, snap).firstOrNull() ?: "" }
        val targetId = transport?.id ?: music
        val title = if (off) "Nothing playing" else (e!!.str("media_title").ifEmpty { e.str("app_name").ifEmpty { if (playing) "Playing" else "Paused" } })
        val dev = e?.name ?: ""
        val sub = if (off) (snap[music]?.name?.let { "Tap play to start $it" } ?: "Tap play to start") else e!!.str("media_artist").ifEmpty { e.str("media_series_title").ifEmpty { e.str("app_name").ifEmpty { dev } } }

        // artwork -> glass with blurred art and white ink; else the plain card
        val art = if (off) null else Common.cachedArt(ctx, e!!.str("entity_picture").takeIf { it.isNotEmpty() })
        val compact = p.h < 150f && p.w >= 200f
        val radius = if (compact && p.h <= 110f) p.h / 2 else if (p.w < 200f) 22f else 26f
        val full = RectF(0f, 0f, p.w, p.h)
        p.card(full, radius)
        if (art != null) {
            p.blurredArt(art, full, radius, dark = if (pal.dark) .42f else .3f)
            p.outline(RectF(.5f, .5f, p.w - .5f, p.h - .5f), radius, Color.argb(70, 255, 255, 255), 1f)
        }
        val ink = if (art != null) Color.WHITE else pal.ink.toInt()
        val sub2 = if (art != null) Color.argb(184, 255, 255, 255) else pal.muted.toInt()
        val trackC = if (art != null) Color.argb(72, 255, 255, 255) else Palette.alpha(pal.ink, .18f)

        // progress
        var pct = 0f; var pos = 0.0; val dur = e?.num("media_duration") ?: 0.0
        if (!off && dur > 0 && e!!.attrs.has("media_position")) {
            pos = e.num("media_position")
            if (playing) e.str("media_position_updated_at").takeIf { it.isNotEmpty() }?.let { upd ->
                try { pos += (System.currentTimeMillis() - Instant.parse(upd).toEpochMilli()) / 1000.0 } catch (_: Exception) {}
            }
            pos = pos.coerceIn(0.0, dur); pct = (pos / dur).toFloat()
        }
        fun fmt(x: Double): String { val s = x.roundToInt(); val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60
            return (if (h > 0) "$h:${m.toString().padStart(2, '0')}" else "$m") + ":" + sec.toString().padStart(2, '0') }

        // actions
        val tgt = JSONObject().put("entity_id", targetId)
        val playPi = if (transport != null) a.service("media_player", "media_play_pause", tgt, targetId to if (playing) "paused" else "playing")
                     else if (music.startsWith("script.")) a.script(music.removePrefix("script."))
                     else a.service("media_player", "media_play", JSONObject().put("entity_id", music))
        val prevPi = a.service("media_player", "media_previous_track", tgt)
        val nextPi = a.service("media_player", "media_next_track", tgt)
        val volEnt = (e?.takeIf { it.attrs.has("volume_level") } ?: snap[cfg["media_speaker"]])
        fun volPi(delta: Double) = if (volEnt != null && volEnt.attrs.has("volume_level")) {
            val v = (volEnt.num("volume_level") + delta).coerceIn(0.0, 1.0)
            a.service("media_player", "volume_set", JSONObject().put("entity_id", volEnt.id).put("volume_level", (v * 100).roundToInt() / 100.0))
        } else a.service("media_player", if (delta > 0) "volume_up" else "volume_down", tgt)
        val volPct = volEnt?.takeIf { it.attrs.has("volume_level") }?.let { "${(it.num("volume_level") * 100).roundToInt()}%" }

        if (p.w < 200f && p.h >= 100f) {
            // square tile: artwork behind, title/artist at the bottom, one big play/pause key
            val ink2 = if (art != null) Color.WHITE else pal.ink.toInt()
            p.text(title, 14f, p.h - 40f, 13f, 800, ink2, maxW = p.w - 28f)
            p.text(sub, 14f, p.h - 22f, 11f, 500, if (art != null) Color.argb(184, 255, 255, 255) else pal.muted.toInt(), maxW = p.w - 28f)
            p.bar(RectF(14f, p.h - 9f, p.w - 14f, p.h - 6f), pct, ink2, trackC)
            val playR = RectF(p.w - 14f - 44f, 14f, p.w - 14f, 14f + 44f)
            p.rrect(playR, 22f, if (art != null) Color.argb(60, 255, 255, 255) else pal.mix(pal.cyan, .25f))
            p.icon(if (playing) R.drawable.ic_pause else R.drawable.ic_play, playR.centerX(), playR.centerY(), 26f, ink2)
            spots.add(Hotspot(playR, playPi))
            val nextR = RectF(playR.left, playR.bottom + 4f, playR.right, playR.bottom + 4f + 30f)
            p.icon(R.drawable.ic_fast_forward, nextR.centerX(), nextR.centerY(), 22f, ink2, .85f)
            spots.add(Hotspot(nextR, nextPi))
            if (art == null) p.icon(R.drawable.ic_music_note, 14f + 16f, 14f + 16f, 28f, pal.muted.toInt())
            spots.add(0, Hotspot(RectF(0f, 0f, p.w - 60f, p.h - 46f), a.open()))
            return spots
        }
        if (compact) {
            // [art] title / artist + thin bar | ‹ ▶ › | vol
            val padL = 14f + (radius - 14f) * .35f
            val artS = min(if (p.w < 340f) 50f else 56f, p.h - 24f)
            val ax = padL; val ay = (p.h - artS) / 2
            val artR = RectF(ax, ay, ax + artS, ay + artS)
            if (art != null) p.drawBitmap(art, artR, 14f) else {
                p.rrect(artR, 14f, if (off) pal.surface2 else pal.surface2)
                p.icon(if (off) R.drawable.ic_music_note else R.drawable.ic_music_note, artR.centerX(), artR.centerY(), 26f, pal.muted.toInt())
            }
            val showVol = p.w >= 390f
            val keysW = 24f + 36f + 24f + 8f
            val volW = if (showVol) 26f * 2 + 6f else 0f
            val right = p.w - padL * .75f
            val keysX = right - volW - (if (showVol) 10f else 0f) - keysW
            val tx = artR.right + 12f
            val textW = keysX - 10f - tx
            val cy = p.h / 2
            p.text(title, tx, cy - 12f, if (textW < 120f) 14f else 15f, 700, ink, maxW = textW)
            p.text(sub, tx, cy + 6f, if (textW < 120f) 12f else 13f, 500, sub2, maxW = textW)
            p.bar(RectF(tx, cy + 19f, tx + textW, cy + 22f), pct, ink, trackC)
            // keys
            var kx = keysX
            val prevR = RectF(kx, cy - 22f, kx + 24f, cy + 22f); p.icon(R.drawable.ic_rewind, prevR.centerX(), cy, 22f, ink); kx += 28f
            val playR = RectF(kx, cy - 22f, kx + 36f, cy + 22f); p.icon(if (playing) R.drawable.ic_pause else R.drawable.ic_play, playR.centerX(), cy, 32f, ink); kx += 40f
            val nextR = RectF(kx, cy - 22f, kx + 24f, cy + 22f); p.icon(R.drawable.ic_fast_forward, nextR.centerX(), cy, 22f, ink)
            spots.add(Hotspot(prevR, prevPi)); spots.add(Hotspot(playR, playPi)); spots.add(Hotspot(nextR, nextPi))
            if (showVol) {
                val vx = right - volW
                val dnR = RectF(vx, cy - 22f, vx + 26f, cy + 22f); val upR = RectF(vx + 32f, cy - 22f, vx + 58f, cy + 22f)
                p.icon(R.drawable.ic_volume_minus, dnR.centerX(), cy - 4f, 20f, ink, .8f); p.icon(R.drawable.ic_volume_plus, upR.centerX(), cy - 4f, 20f, ink, .8f)
                volPct?.let { p.text(it, (dnR.centerX() + upR.centerX()) / 2, cy + 14f, 10f, 600, sub2, Align.CENTER) }
                spots.add(Hotspot(dnR, volPi(-.05))); spots.add(Hotspot(upR, volPi(+.05)))
            }
            spots.add(Hotspot(RectF(0f, 0f, keysX - 10f, p.h), a.open()))
        } else {
            // the dashboard's floating panel: centred head, progress row, key row
            val padX = 26f
            val innerW = p.w - padX * 2
            val artS = if (p.h >= 230f) min(p.w * .55f, p.h - 150f) else 0f
            val blockH = (if (artS > 0f) artS + 14f else 0f) + 44f + 24f + 44f
            var y = ((p.h - blockH) / 2).coerceAtLeast(14f)
            if (artS > 0f) {
                val artR = RectF(p.w / 2 - artS / 2, y, p.w / 2 + artS / 2, y + artS)
                if (art != null) p.drawBitmap(art, artR, 18f) else { p.rrect(artR, 18f, pal.surface2); p.icon(R.drawable.ic_music_note, artR.centerX(), artR.centerY(), 40f, pal.muted.toInt()) }
                y += artS + 14f
            }
            // head + equaliser bars (static frame of the animation)
            val headTop = y
            p.text(title, p.w / 2, y + 8f, 15f, 700, ink, Align.CENTER, maxW = innerW - 80f)
            p.text(sub, p.w / 2, y + 27f, 14f, 500, sub2, Align.CENTER, maxW = innerW - 80f)
            val eqX = p.w - padX - 14f
            val eqH = if (playing) floatArrayOf(6f, 12f, 4f, 10f, 7f) else floatArrayOf(3f, 3f, 3f, 3f, 3f)
            eqH.forEachIndexed { i, hh -> p.rrect(RectF(eqX + i * 4f, headTop + 14f - hh, eqX + i * 4f + 2f, headTop + 14f), 1f, Palette.alpha(ink, if (playing) .85f else .5f)) }
            y += 44f
            // progress: time, bar with knob, -remaining
            val l = if (dur > 0) fmt(pos) else "0:00"; val r = if (dur > 0) "-" + fmt(dur - pos) else "-0:00"
            val lw = p.text(l, padX + 4f, y + 6f, 11f, 600, sub2)
            val rw = p.text(r, p.w - padX - 4f, y + 6f, 11f, 600, sub2, Align.RIGHT)
            // hour-long positions ("1:02:03") are wider than the usual "3:21"
            val bx0 = padX + 4f + maxOf(34f, lw + 8f); val bx1 = p.w - padX - 4f - maxOf(34f, rw + 8f)
            p.bar(RectF(bx0, y + 4f, bx1, y + 8f), pct, ink, trackC)
            if (pct > 0f) { val kx = bx0 + (bx1 - bx0) * pct; p.circle(kx, y + 6f, 4f, ink) }
            y += 24f
            // keys: vol- | ‹ | play | › | vol+
            val cy = y + 22f
            val playR = RectF(p.w / 2 - 28f, cy - 22f, p.w / 2 + 28f, cy + 22f)
            val prevR = RectF(playR.left - 12f - 44f, cy - 22f, playR.left - 12f, cy + 22f)
            val nextR = RectF(playR.right + 12f, cy - 22f, playR.right + 12f + 44f, cy + 22f)
            val dnR = RectF(padX - 6f, cy - 22f, padX - 6f + 40f, cy + 22f)
            val upR = RectF(p.w - padX + 6f - 40f, cy - 22f, p.w - padX + 6f, cy + 22f)
            p.icon(R.drawable.ic_rewind, prevR.centerX(), cy, 30f, ink)
            p.icon(if (playing) R.drawable.ic_pause else R.drawable.ic_play, playR.centerX(), cy, 36f, ink)
            p.icon(R.drawable.ic_fast_forward, nextR.centerX(), cy, 30f, ink)
            p.icon(R.drawable.ic_volume_minus, dnR.centerX(), cy - 4f, 22f, ink, .8f)
            p.icon(R.drawable.ic_volume_plus, upR.centerX(), cy - 4f, 22f, ink, .8f)
            volPct?.let { p.text(it, dnR.centerX(), cy + 14f, 10f, 600, sub2, Align.CENTER) }
            spots.add(Hotspot(prevR, prevPi)); spots.add(Hotspot(playR, playPi)); spots.add(Hotspot(nextR, nextPi))
            spots.add(Hotspot(dnR, volPi(-.05))); spots.add(Hotspot(upR, volPi(+.05)))
            spots.add(Hotspot(RectF(0f, 0f, p.w, max(headTop - 4f, 40f)), a.open()))
        }
        if (!compact) Common.statusBadge(ctx, p, a, snap, p.w - 22f, 16f)?.let { spots.add(0, it) }
        return spots
    }
}

class NowSmallWidget : NowWidget()
class NowBarWidget : NowWidget()
