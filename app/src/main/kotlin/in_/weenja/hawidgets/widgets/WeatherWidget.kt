package in_.weenja.hawidgets.widgets

import android.content.Context
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import in_.weenja.hawidgets.Cfg
import in_.weenja.hawidgets.R
import in_.weenja.hawidgets.ha.Snapshot
import in_.weenja.hawidgets.ui.Align
import in_.weenja.hawidgets.ui.Painter
import in_.weenja.hawidgets.ui.Palette
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/** Weather condition -> label / icon / tint, same tables as the dashboard's weather card. */
object Wx {
    fun label(c: String) = when (c) {
        "sunny" -> "Sunny"; "clear-night" -> "Clear"; "partlycloudy" -> "Partly cloudy"; "cloudy" -> "Cloudy"; "rainy" -> "Rain"
        "pouring" -> "Heavy rain"; "lightning", "lightning-rainy" -> "Storm"; "snowy" -> "Snow"; "snowy-rainy" -> "Sleet"; "fog" -> "Fog"
        "hail" -> "Hail"; "windy", "windy-variant" -> "Windy"; "exceptional" -> "Exceptional"; else -> c.replaceFirstChar { it.uppercase() }
    }
    fun icon(c: String, day: Boolean = true) = when (c) {
        "sunny" -> R.drawable.ic_weather_sunny; "clear-night" -> R.drawable.ic_weather_night
        "partlycloudy" -> if (day) R.drawable.ic_weather_partly_cloudy else R.drawable.ic_weather_night_partly_cloudy
        "cloudy" -> R.drawable.ic_weather_cloudy; "rainy" -> R.drawable.ic_weather_rainy; "pouring" -> R.drawable.ic_weather_pouring
        "lightning" -> R.drawable.ic_weather_lightning; "lightning-rainy" -> R.drawable.ic_weather_lightning_rainy
        "snowy" -> R.drawable.ic_weather_snowy; "snowy-rainy" -> R.drawable.ic_weather_snowy_rainy; "fog" -> R.drawable.ic_weather_fog
        "hail" -> R.drawable.ic_weather_hail; "windy" -> R.drawable.ic_weather_windy; "windy-variant" -> R.drawable.ic_weather_windy_variant
        else -> R.drawable.ic_weather_partly_cloudy
    }
    fun tint(c: String): Int = when (c) {
        "sunny" -> 0xffff9f2e; "clear-night" -> 0xff7b61ff; "rainy", "pouring" -> 0xff3fe3ff; "lightning", "lightning-rainy" -> 0xff9d7bff
        "cloudy" -> 0xffaab2c5; "partlycloudy" -> 0xffffd24d; "snowy", "snowy-rainy", "hail" -> 0xffe8f4ff; "fog" -> 0xffaab2c5; else -> 0xff3fe3ff
    }.toInt()

    /** HA datetimes come as ISO with offset; forecast items sometimes without. */
    fun parse(s: String): LocalDateTime? = try { OffsetDateTime.parse(s).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime() }
        catch (e: Exception) { try { LocalDateTime.parse(s) } catch (e2: Exception) { null } }
}

/**
 * The dashboard's weather card: big temperature next to a tinted blob, hi/lo, tomorrow + the day after,
 * and (when tall) the next hours as a strip like the Sky view.
 */
open class WeatherWidget : CardWidget() {
    override fun draw(ctx: Context, p: Painter, snap: Snapshot, a: Actions): List<Hotspot> {
        val pal = p.p
        val spots = ArrayList<Hotspot>()
        p.card()
        val padX = 20f; val padY = 18f
        val innerW = p.w - padX * 2
        val cfg = a.cfg
        val w = Find.weather(cfg, snap)
        val cond = w?.state ?: "cloudy"
        // forecasts: weather.get_forecasts, attached to the weather entity at every refresh; the old
        // template sensors only when they are configured
        val fc = w?.attrs?.optJSONArray(in_.weenja.hawidgets.core.Extra.DAILY)?.takeIf { it.length() > 0 }
            ?: cfg["forecast_daily"].takeIf { it.isNotEmpty() }?.let { snap[it]?.attrs?.optJSONArray("forecast") } ?: JSONArray()
        val hourly = w?.attrs?.optJSONArray(in_.weenja.hawidgets.core.Extra.HOURLY)?.takeIf { it.length() > 0 }
            ?: cfg["forecast_hourly"].takeIf { it.isNotEmpty() }?.let { snap[it]?.attrs?.optJSONArray("forecast") } ?: JSONArray()
        val place = Find.place(cfg, snap)
        val today = LocalDate.now()
        val hi = fc.optJSONObject(0)?.optDouble("temperature")?.takeIf { !it.isNaN() } ?: w?.num("temperature") ?: 0.0
        val lo = fc.optJSONObject(0)?.optDouble("templow")?.takeIf { !it.isNaN() }
        val nowT = w?.num("temperature") ?: hi
        val tint = Wx.tint(cond)
        val night = snap[cfg["sun"]]?.state == "below_horizon"

        if (p.w < 200f) {
            // square tile: icon blob top-right, big temperature, condition + lo
            val bx = p.w - 16f - 26f; val by = 16f + 26f
            val paint0 = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint0.shader = RadialGradient(bx, by, 30f, intArrayOf(Palette.alpha(tint, .34f), Palette.alpha(tint, .1f), Palette.alpha(tint, 0f)), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            p.canvas.drawCircle(bx, by, 30f, paint0)
            p.icon(Wx.icon(cond, !night), bx, by, 30f, tint)
            p.text(place, 16f, 22f, 11f, 600, pal.muted, maxW = p.w - 32f - 60f)
            p.text("${nowT.roundToInt()}°", 14f, p.h / 2 + 4f, 40f, 700, pal.ink, letterSpacing = -.04f)
            p.text(Wx.label(cond), 16f, p.h - 30f, 13f, 700, pal.ink, maxW = p.w - 32f)
            p.text(listOfNotNull(fc.optJSONObject(0)?.optDouble("temperature")?.takeIf { !it.isNaN() }?.let { "H ${it.roundToInt()}°" }, lo?.let { "L ${it.roundToInt()}°" }).joinToString("  "), 16f, p.h - 15f, 11f, 500, pal.muted)
            spots.add(Hotspot(RectF(0f, 0f, p.w, p.h), a.open(cfg["path_sky"])))
            return spots
        }
        var y = padY + 10f
        val badge = Common.statusBadge(ctx, p, a, snap, p.w - padX, y)?.also { spots.add(it) }
        p.header("Weather", if (badge == null) "$place · ${today.dayOfMonth} ${today.month.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)}" else "", padX, y, innerW)
        y += 18f

        // ---- one-row layout for very short widgets: temp · condition · hi/lo, blob right
        if (p.h < 120f) {
            val cy = (y + p.h - padY) / 2 + 2f
            val tw0 = p.text("${nowT.roundToInt()}°", padX, cy, 32f, 700, pal.ink, letterSpacing = -.04f)
            p.text(Wx.label(cond) + (lo?.let { " · L ${it.roundToInt()}°" } ?: ""), padX + tw0 + 10f, cy, 13f, 600, pal.ink, maxW = innerW - tw0 - 80f)
            p.icon(Wx.icon(cond, !night), p.w - padX - 22f, cy, 40f, tint)
            spots.add(0, Hotspot(RectF(0f, 0f, p.w, p.h), a.open(cfg["path_sky"])))
            return spots
        }
        // ---- top: temperature + condition left, glowing blob with the icon right (.wtop/.blob/.big)
        val compact = p.h < 170f
        val blob = if (compact) 72f else if (p.h < 230f) 88f else 104f
        val topH = if (compact) 78f else if (p.h < 230f) 84f else 100f
        val cy = y + topH / 2
        p.text("${nowT.roundToInt()}°", padX, cy - 12f, if (compact) 36f else 44f, 700, pal.ink, letterSpacing = -.04f)
        val tw = p.measure("${nowT.roundToInt()}°", if (compact) 36f else 44f, 700)
        if (lo != null) p.text("${lo.roundToInt()}°", padX + tw + 6f, cy - 6f, 20f, 600, pal.muted)
        p.text(Wx.label(cond), padX, cy + 16f, 14f, 700, pal.ink)
        val extra = listOfNotNull(w?.num("humidity")?.takeIf { it > 0 }?.let { "${it.roundToInt()}% humidity" },
            w?.num("wind_speed")?.takeIf { it > 0 }?.let { "${it.roundToInt()} ${w.str("wind_speed_unit", "km/h")}" }).joinToString(" · ")
        if (extra.isNotEmpty()) p.text(extra, padX, cy + 33f, 12f, 400, pal.muted, maxW = innerW - blob - 20f)
        // blob
        val bx = p.w - padX - blob / 2
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.shader = RadialGradient(bx, cy, blob / 2, intArrayOf(Palette.alpha(tint, .34f), Palette.alpha(tint, .12f), Palette.alpha(tint, 0f)), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
        p.canvas.drawCircle(bx, cy, blob / 2, paint)
        p.icon(Wx.icon(cond, !night), bx, cy, blob * .48f, tint)
        y += topH + 6f

        val bottom = p.h - padY
        val dayName = { f: JSONObject? -> f?.let { Wx.parse(it.optString("datetime"))?.format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)) } ?: "" }
        val nowH = LocalDateTime.now()
        val items = (0 until hourly.length()).map { hourly.getJSONObject(it) }.filter { f -> Wx.parse(f.optString("datetime"))?.let { !it.isBefore(nowH.minusMinutes(30)) } == true }
        val n = (innerW / 40f).toInt().coerceIn(4, 12)

        // ---- plan: forecast day rows (34 dp) first, then hourly rows (60 dp); then stretch every row so the card is full
        val avail = bottom - y
        val dayRowH0 = 34f; val hourRowH0 = 60f
        val days = ArrayList<Pair<JSONObject, String>>()
        val fcAll = (1 until minOf(fc.length(), 7)).mapNotNull { i -> fc.optJSONObject(i)?.let { it to (if (i == 1) "Tomorrow" else dayName(it)) } }
        var hourRows = 0
        if (!compact || avail >= 60f) {
            var used = 0f
            for (d in fcAll.take(2)) { if (avail - used >= dayRowH0) { days.add(d); used += dayRowH0 } }
            val hourlyMax = if (items.isEmpty()) 0 else ((items.size + n - 1) / n).coerceAtMost(3)
            // fill: prefer one hourly row, then more days, then more hourly rows
            if (hourlyMax > 0 && avail - used >= hourRowH0 + 12f) { hourRows = 1; used += hourRowH0 + 12f }
            for (d in fcAll.drop(2)) { if (avail - used >= dayRowH0) { days.add(d); used += dayRowH0 } else break }
            while (hourRows in 1 until hourlyMax && avail - used >= hourRowH0) { hourRows++; used += hourRowH0 }
        }
        val rowsTotal = days.size + hourRows
        val used = days.size * dayRowH0 + hourRows * hourRowH0 + (if (hourRows > 0) 12f else 0f)
        val leftover = (avail - used).coerceAtLeast(0f)
        val stretch = if (rowsTotal > 0) (leftover / rowsTotal).coerceAtMost(26f) else 0f
        val dayRowH = dayRowH0 + stretch; val hourRowH = hourRowH0 + stretch
        // whatever the caps leave goes above the rows, so the top block breathes
        if (rowsTotal > 0) y += (leftover - stretch * rowsTotal).coerceAtLeast(0f) / 2

        // ---- forecast rows
        for ((f, label) in days) {
            val c = f.optString("condition")
            val cy2 = y + dayRowH / 2 - 3f
            p.icon(Wx.icon(c), padX + 13f, cy2, 22f, pal.muted.toInt())
            p.text(label, padX + 36f, cy2 - 6f, 12f, 400, pal.muted)
            p.text(Wx.label(c), padX + 36f, cy2 + 8f, 14f, 700, pal.ink, maxW = innerW - 36f - 80f)
            val h = f.optDouble("temperature").takeIf { !it.isNaN() }?.roundToInt(); val l = f.optDouble("templow").takeIf { !it.isNaN() }?.roundToInt()
            val hw = p.text(h?.let { "$it°" } ?: "", p.w - padX, cy2, 14f, 700, pal.ink, Align.RIGHT)
            if (l != null) p.text("$l° / ", p.w - padX - hw, cy2, 13f, 400, pal.muted, Align.RIGHT)
            y += dayRowH
        }

        // ---- hourly strip(s): hour, icon, temp
        if (hourRows > 0) {
            y += 4f
            p.line(padX, y, p.w - padX, y, pal.track.toInt(), 1f, dashed = true)
            y += 8f
            val cw = innerW / n
            var shown = 0
            for (f in items) {
                if (shown >= n * hourRows) break
                val t = Wx.parse(f.optString("datetime")) ?: continue
                val x = padX + cw * (shown % n) + cw / 2
                val yy = y + (shown / n) * hourRowH + (hourRowH - hourRowH0) / 2
                p.text(if (shown == 0) "Now" else t.format(DateTimeFormatter.ofPattern("HH")), x, yy + 6f, 11f, 600, pal.muted, Align.CENTER)
                val day = if (f.has("is_daytime")) f.optBoolean("is_daytime") else t.hour in 6..18
                p.icon(Wx.icon(f.optString("condition"), day), x, yy + 26f, 20f, Wx.tint(f.optString("condition")))
                p.text("${f.optDouble("temperature").takeIf { !it.isNaN() }?.roundToInt() ?: "–"}°", x, yy + 46f, 12f, 700, pal.ink, Align.CENTER)
                shown++
            }
            y += hourRows * hourRowH
        }
        spots.add(0, Hotspot(RectF(0f, 0f, p.w, p.h), a.open(cfg["path_sky"])))
        return spots
    }
}

class WeatherSmallWidget : WeatherWidget()
