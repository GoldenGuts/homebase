package in_.weenja.hawidgets.widgets

import android.content.Context
import android.graphics.RectF
import in_.weenja.hawidgets.Cfg
import in_.weenja.hawidgets.R
import in_.weenja.hawidgets.ha.Entity
import in_.weenja.hawidgets.ha.Snapshot
import in_.weenja.hawidgets.ui.Align
import in_.weenja.hawidgets.ui.Painter
import in_.weenja.hawidgets.ui.Palette
import org.json.JSONObject
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Home view's "Overhead" card: two gradient tiles (planes within 100 km, low-orbit satellites more
 * than 30° up) and a few rows under them (nearest planes, the ISS, the highest satellite).
 * Data = sensor.planes_overhead + sensor.satellites_overhead (packages/overhead.yaml, bin/overhead.py).
 * Under 200 dp wide it draws the two numbers only.
 */
open class OverheadWidget : CardWidget() {
    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        p.card()
        val cfg = a.cfg
        val planes = snap[cfg["planes"]]
        val sats = snap[cfg["satellites"]]
        val pa = planes?.attrs ?: JSONObject()
        val sa = sats?.attrs ?: JSONObject()
        val np = planes?.takeIf { it.available }?.state?.toIntOrNull()
        val ns = sats?.takeIf { it.available }?.state?.toIntOrNull()
        val closest = pa.optJSONObject("closest")
        val iss = sa.optJSONObject("iss")
        val link = fr24(cfg, snap)

        if (p.w < 200f) {
            // square: plane count on top, satellite count below, each with its icon and one caption line
            val half = (p.h - 24f) / 2
            fun mini(y: Float, icon: Int, col: Int, n: Int?, label: String) {
                p.icon(icon, 16f + 12f, y + 20f, 22f, col)
                p.text(n?.toString() ?: "–", 16f + 34f, y + 20f, 30f, 700, pal.ink, letterSpacing = -.04f)
                p.text(label, 16f, y + 46f, 11f, 500, pal.muted, maxW = p.w - 32f)
            }
            mini(12f, R.drawable.ic_airplane, pal.cyan.toInt(), np, if (np == null) "planes · no data" else (closestLabel(closest, pa) ?: "planes · 100 km"))
            p.line(16f, 12f + half, p.w - 16f, 12f + half, pal.track.toInt(), 1f, dashed = true)
            mini(12f + half + 2f, R.drawable.ic_satellite_variant, pal.purple.toInt(), ns, if (ns == null) "satellites · no data" else issShort(iss, sa))
            spots.add(Hotspot(RectF(0f, 0f, p.w, p.h), a.url(link)))
            return spots
        }

        val padX = 20f; val padY = 18f
        val innerW = p.w - padX * 2
        var y = padY + 10f
        val badge = Common.statusBadge(ctx, p, a, snap, p.w - padX, y)?.also { spots.add(it) }
        val upd = updatedLabel(sa.optString("updated").ifEmpty { pa.optString("updated") })
        val stale = pa.optBoolean("stale") || sa.optBoolean("stale")
        p.header("Overhead", if (badge == null) listOfNotNull(Find.place(cfg, snap), upd, if (stale) "offline" else null).joinToString(" · ") else "", padX, y, innerW)
        y += 18f

        // ---- two gradient tiles (.ot2 / .ot)
        val compact = p.h < 170f
        val tileH = (if (compact) 92f else 108f).coerceAtMost(p.h - padY - y)
        val gap = 12f
        val tw = (innerW - gap) / 2
        fun tile(x: Float, grad: Pair<Long, Long>, ink: Long, icon: Int, n: Int?, label: String, sub: String): RectF {
            val r = RectF(x, y, x + tw, y + tileH)
            p.gradient(r, 18f, grad, 160f, shadow = 8f)
            val inkI = ink.toInt()
            p.icon(icon, x + 14f + 13f, y + 14f + 13f, 26f, inkI, shadow = true)
            val tiny = tileH < 80f
            p.text(n?.toString() ?: "–", x + (if (tiny) 48f else 14f), if (tiny) y + tileH / 2 else y + (if (compact) 52f else 58f), if (tiny) 26f else if (compact) 30f else 36f, 700, inkI, letterSpacing = -.04f)
            if (!tiny) p.text(label, x + 14f, r.bottom - (if (compact) 22f else 28f), 12f, 700, inkI, maxW = tw - 28f)
            if (!compact) p.text(sub, x + 14f, r.bottom - 14f, 11f, 500, Palette.alpha(inkI, .78f), maxW = tw - 28f)
            return r
        }
        val psub = when {
            np == null -> "no data yet"
            pa.optInt("close") > 0 -> "${pa.optInt("close")} right above you"
            closest != null -> "closest ${closest.optDouble("dist_km")} km ${closest.optString("dir")}"
            else -> "none within ${pa.optInt("radius_km", 100)} km"
        }
        val ssub = if (ns == null) "no data yet" else "${sa.optInt("starlink")} Starlink"
        val r1 = tile(padX, pal.gradCyan, pal.inkOnCyan, R.drawable.ic_airplane, np, if (np == 1) "plane · 100 km" else "planes · 100 km", psub)
        val r2 = tile(padX + tw + gap, pal.gradPurple, pal.inkOnPurple, R.drawable.ic_satellite_variant, ns, if (ns == 1) "satellite up high" else "satellites up high", ssub)
        spots.add(Hotspot(r1, a.url(link)))
        spots.add(Hotspot(r2, a.open()))
        y += tileH + 10f

        // ---- rows (.orow): nearest planes, ISS, highest satellite
        val bottom = p.h - padY
        var rowH = 38f
        fun row(icon: Int, col: Int, main: String, meta: String, right: String) {
            if (y + rowH - 6f > bottom) return
            val cy = y + 16f
            p.rrect(RectF(padX, cy - 16f, padX + 32f, cy + 16f), 11f, pal.mix(col.toLong() and 0xffffffffL))
            p.icon(icon, padX + 16f, cy, 18f, col)
            val rw = p.text(right, p.w - padX, cy, 12f, 400, pal.muted, Align.RIGHT, maxW = innerW * .5f)
            val maxMain = innerW - 42f - rw - 10f
            val mw = p.text(main, padX + 42f, cy, 13f, 700, pal.ink, maxW = maxMain)
            if (meta.isNotEmpty()) p.text(meta, padX + 42f + mw + 5f, cy, 12f, 400, pal.muted, maxW = maxMain - mw - 5f)
            y += rowH
        }
        val list = pa.optJSONArray("planes")
        // rows the ISS and the highest satellite still need after the planes
        val reserved = (if (iss != null) rowH else 0f) + (if (sa.optJSONArray("highest")?.optJSONObject(0) != null) rowH else 0f)
        val planeRows = (((bottom - y - reserved + 6f) / rowH).toInt()).coerceIn(2, 4)
        // stretch rows so the list reaches the bottom of the card
        val rowsPlanned = minOf(planeRows, list?.length() ?: 0) + (reserved / 38f).toInt()
        if (rowsPlanned > 0) rowH = ((bottom - y + 6f) / rowsPlanned).coerceIn(38f, 50f)
        if (list != null) for (i in 0 until minOf(planeRows, list.length())) {
            val x = list.optJSONObject(i) ?: continue
            val ft = x.optInt("alt_ft").takeIf { it > 0 }?.let { String.format(Locale.ENGLISH, "%,d ft", it) } ?: ""
            val arrow = when { x.optInt("climb") > 0 -> " ↗"; x.optInt("climb") < 0 -> " ↘"; else -> "" }
            row(R.drawable.ic_airplane, pal.cyan.toInt(), x.optString("callsign"), x.optString("airline").ifEmpty { x.optString("type").ifEmpty { x.optString("country") } },
                "${x.optDouble("dist_km")} km ${x.optString("dir")}" + (if (ft.isNotEmpty()) " · $ft$arrow" else ""))
        }
        if (iss != null) {
            val pass = iss.optJSONObject("next_pass")
            if (iss.optInt("el") >= 10) row(R.drawable.ic_rocket_launch, pal.orange.toInt(), "ISS", "over you now", "${iss.optInt("el")}° up · ${iss.optString("dir")}")
            else if (pass != null) {
                val m = pass.optInt("starts_in_min")
                val t = parse(pass.optString("start"))
                val whenS = when { m < 60 -> "in $m min"; m < 1440 -> "in ${m / 60}h ${m % 60}m"; else -> t?.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)) ?: "" }
                row(R.drawable.ic_rocket_launch, pal.orange.toInt(), "ISS", "next pass ${t?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: ""} · $whenS", "up to ${pass.optInt("max_el")}°")
            }
        }
        sa.optJSONArray("highest")?.optJSONObject(0)?.let { top ->
            row(R.drawable.ic_satellite_variant, pal.purple.toInt(), top.optString("name"), "highest right now", "${top.optInt("el")}° up · ${top.optString("dir")} · ${top.optInt("alt_km")} km")
        }
        if (sa.optBoolean("night") && sa.optInt("lit") > 0 && y + 16f <= bottom) {
            p.icon(R.drawable.ic_eye_outline, padX + 7f, y + 8f, 14f, pal.muted.toInt())
            p.text("${sa.optInt("lit")} of them are sunlit — look up, the brighter ones are visible.", padX + 20f, y + 8f, 12f, 400, pal.muted, maxW = innerW - 20f)
        }
        if (planes == null && sats == null) Common.empty(p, RectF(padX, y, p.w - padX, bottom), R.drawable.ic_satellite_variant, "No overhead sensors yet", "Set the planes / satellites sensors in the app")
        spots.add(0, Hotspot(RectF(0f, 0f, p.w, p.h), a.url(link)))
        return spots
    }

    /** "ISS up now" / "ISS in 52 min" / "ISS 18:40" / "244 parked" for the tight captions. */
    private fun issShort(iss: JSONObject?, sa: JSONObject): String {
        val pass = iss?.optJSONObject("next_pass")
        return when {
            iss != null && iss.optInt("el") >= 10 -> "ISS up now"
            pass != null && pass.optInt("starts_in_min") < 120 -> "ISS in ${pass.optInt("starts_in_min")} min"
            pass != null -> "ISS " + (parse(pass.optString("start"))?.format(DateTimeFormatter.ofPattern("HH:mm")) ?: "")
            else -> "${sa.optInt("high")} parked"
        }
    }

    private fun closestLabel(c: JSONObject?, pa: JSONObject): String? = when {
        pa.optInt("close") > 0 -> "${pa.optInt("close")} right above you"
        c != null -> "closest ${c.optDouble("dist_km")} km ${c.optString("dir")}"
        else -> null
    }

    /** Flightradar24 centred on home (zone.home), like the dashboard card's tap. */
    private fun fr24(cfg: Cfg, snap: Snapshot): String {
        val z: Entity? = snap[cfg["zone_home"]]
        val lat = z?.num("latitude"); val lon = z?.num("longitude")
        return if (lat != null && lon != null && lat != 0.0) String.format(Locale.ENGLISH, "https://www.flightradar24.com/%.3f,%.3f/9", lat, lon) else "https://www.flightradar24.com/"
    }

    private fun parse(iso: String) = try { OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()) } catch (e: Exception) { null }

    private fun updatedLabel(iso: String): String? = parse(iso)?.format(DateTimeFormatter.ofPattern("HH:mm"))
}

class OverheadSmallWidget : OverheadWidget()
