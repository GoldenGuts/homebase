package in_.weenja.hawidgets.widgets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import in_.weenja.hawidgets.Cfg
import in_.weenja.hawidgets.Prefs
import in_.weenja.hawidgets.R
import in_.weenja.hawidgets.ha.Snapshot
import in_.weenja.hawidgets.ui.Align
import in_.weenja.hawidgets.ui.Painter
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The lights, fan and TV the legacy widgets use: this widget's own slot, else the global setting,
 * else auto-detected (the n-th available light, the first fan, the first TV).
 */
object Lights {
    /** Lights in auto-fill order: Home Assistant favorites, suggestions, then room by room. */
    private fun lights(cfg: Cfg, snap: Snapshot): List<String> =
        in_.weenja.hawidgets.core.Planner.fillToggles(in_.weenja.hawidgets.HomeStore.home(cfg.ctx, snap), 8).filter { it.startsWith("light.") }
            .ifEmpty { snap.byDomain("light").filter { it.available }.map { it.id } }
    private fun pick(cfg: Cfg, snap: Snapshot, slot: String, key: String, index: Int): in_.weenja.hawidgets.ha.Entity? {
        (cfg.one(slot) ?: cfg[key].takeIf { it.isNotEmpty() })?.let { id -> return snap[id] }
        return lights(cfg, snap).getOrNull(index)?.let { snap[it] }
    }
    fun bulb(cfg: Cfg, snap: Snapshot) = pick(cfg, snap, "light", "light_bulb", 0)
    fun tube(cfg: Cfg, snap: Snapshot) = pick(cfg, snap, "light", "light_tube", 1)
    fun group(cfg: Cfg, snap: Snapshot) = cfg["light_group"].takeIf { it.isNotEmpty() }?.let { snap[it] } ?: bulb(cfg, snap)
    /** The configured fan, else the first one. */
    fun fan(cfg: Cfg, snap: Snapshot) = cfg["fan"].takeIf { it.isNotEmpty() }?.let { snap[it] } ?: snap.byDomain("fan").firstOrNull()
    /** The configured TV, else the first media player that says it is a TV. */
    fun tv(cfg: Cfg, snap: Snapshot) = (cfg.one("tv") ?: cfg["media_tv"].takeIf { it.isNotEmpty() })?.let { snap[it] }
        ?: snap.byDomain("media_player").firstOrNull { it.str("device_class") == "tv" }
    fun tvRemote(cfg: Cfg, snap: Snapshot) = (cfg.one("remote") ?: cfg["remote_tv"].takeIf { it.isNotEmpty() })?.let { snap[it] } ?: snap.byDomain("remote").firstOrNull()
    /** "All off": the configured list, else every light and media player that is on. */
    fun allOff(cfg: Cfg, snap: Snapshot): List<String> = cfg.list("all_off").ifEmpty {
        (snap.byDomain("light") + snap.byDomain("media_player") + snap.byDomain("fan")).filter { it.available }.map { it.id }
    }
}

/** Entities several widgets share, resolved with auto-detection when the setting is empty. */
object Find {
    fun weather(cfg: Cfg, snap: Snapshot) = (cfg.one("weather") ?: cfg["weather"].takeIf { it.isNotEmpty() })?.let { snap[it] } ?: snap.byDomain("weather").firstOrNull()
    fun todo(cfg: Cfg, snap: Snapshot) = (cfg.one("list") ?: cfg["todo"].takeIf { it.isNotEmpty() })?.let { snap[it] } ?: snap.byDomain("todo").firstOrNull()
    /** Header place name: the setting, else the weather entity's name, else the home zone's. */
    fun place(cfg: Cfg, snap: Snapshot): String = cfg["place"].ifEmpty { weather(cfg, snap)?.name?.takeIf { it.isNotEmpty() && !it.equals("home", true) && !it.startsWith("Forecast", true) } ?: snap[cfg["zone_home"]]?.name ?: "Home" }
    /** The phone battery for the Today pills: the setting, else a companion-app battery level sensor. */
    fun phoneBattery(cfg: Cfg, snap: Snapshot): in_.weenja.hawidgets.ha.Entity? {
        cfg["phone_battery"].takeIf { it.isNotEmpty() }?.let { return snap[it] }
        val reg = in_.weenja.hawidgets.HomeStore.home(cfg.ctx, snap).registry
        val candidates = snap.entities.values.filter { it.domain == "sensor" && it.id.endsWith("_battery_level") && it.str("device_class") == "battery" }
        // the Home Assistant companion app names it sensor.<phone>_battery_level
        return candidates.firstOrNull { reg.entry(it.id)?.platform == "mobile_app" }
            ?: candidates.firstOrNull { !Regex("home|house|powerwall|storage|inverter|car|ev_").containsMatchIn(it.id) }
    }
}

/** Bits shared by the widgets: money formatting, dates, the demo badge, artwork cache. */
object Common {
    /** Money. ₹ uses Indian grouping (38420 -> ₹38,420 ; 120000 -> ₹1,20,000), other symbols group by thousands. */
    fun rs(n: Double, sym: String = symbol): String {
        val v = abs(n).roundToLong()
        val s = v.toString()
        val grouped = if (s.length <= 3) s else if (sym != "₹") String.format(Locale.ENGLISH, "%,d", v) else {
            val last3 = s.takeLast(3)
            val rest = s.dropLast(3)
            val parts = ArrayList<String>()
            var r = rest
            while (r.length > 2) { parts.add(0, r.takeLast(2)); r = r.dropLast(2) }
            if (r.isNotEmpty()) parts.add(0, r)
            parts.joinToString(",") + "," + last3
        }
        return (if (n < 0) "-$sym" else sym) + grouped
    }

    /** Set once per draw from the configuration so the money helpers need no context. */
    @Volatile var symbol: String = "₹"

    private val MON = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    /** "2026-09-21" -> "21 Sep" */
    fun dm(iso: String?): String {
        val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})").find(iso ?: "") ?: return ""
        return "${m.groupValues[3].toInt()} ${MON[m.groupValues[2].toInt() - 1]}"
    }

    fun daysUntil(iso: String?): Int? = try { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(iso)).toInt() } catch (e: Exception) { null }

    fun monthName(): String = LocalDate.now().format(DateTimeFormatter.ofPattern("LLLL", Locale.ENGLISH))

    fun cap(s: String?) = s?.replaceFirstChar { it.uppercase() } ?: ""

    /** Epoch seconds -> "Today" / "Yesterday" / "21 Sep" */
    fun whenLabel(ts: Long): String {
        val d = java.time.Instant.ofEpochSecond(ts).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val today = LocalDate.now()
        return when (d) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> "${d.dayOfMonth} ${MON[d.monthValue - 1]}" }
    }

    /** Top-right badge when the widget shows demo data or a stale/failed fetch. Returns its hotspot. */
    fun statusBadge(ctx: Context, p: Painter, a: Actions, snap: Snapshot, x: Float, y: Float): Hotspot? {
        val prefs = Prefs(ctx)
        if (!snap.badge) return null
        val label = when {
            snap.demo && !prefs.isLoggedIn -> "Demo · tap to connect"
            snap.demo -> "No data yet · tap"
            else -> return null
        }
        val wdt = p.pill(0f, 0f, label, 0, 0, size = 10f, height = 20f, draw = false)
        val r = RectF(x - wdt, y - 10f, x, y + 10f)
        p.pill(r.left, r.top, label, p.p.mix(p.p.error, .2f), p.p.error.toInt(), size = 10f, height = 20f)
        return Hotspot(RectF(r.left - 6f, r.top - 6f, r.right + 6f, r.bottom + 6f), a.app())
    }

    /** "Sep 19, 14:02" of the last successful fetch. */
    fun fetchedLabel(snap: Snapshot): String {
        if (snap.demo || snap.fetchedAt == 0L) return ""
        val t = java.time.Instant.ofEpochMilli(snap.fetchedAt).atZone(java.time.ZoneId.systemDefault())
        return t.format(DateTimeFormatter.ofPattern("HH:mm"))
    }

    // ---------------------------------------------------------------- artwork cache (same files Api.image writes)

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun cachedArt(ctx: Context, pathOrUrl: String?): Bitmap? {
        if (pathOrUrl.isNullOrBlank()) return null
        val h = sha1(pathOrUrl)
        val f = File(ctx.cacheDir, "keep_$h").takeIf { it.exists() } ?: File(ctx.cacheDir, "art_$h")
        return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }

    /** Muted "empty" line in the middle of a region. */
    fun empty(p: Painter, r: RectF, icon: Int, line1: String, line2: String) {
        p.icon(icon, r.centerX(), r.centerY() - 16f, 22f, p.p.muted.toInt())
        p.text(line1, r.centerX(), r.centerY() + 6f, 13f, 500, p.p.muted, Align.CENTER, maxW = r.width() - 8f)
        p.text(fitWords(p, line2, r.width() - 8f, 11.5f, 400), r.centerX(), r.centerY() + 22f, 11.5f, 400, p.p.dim, Align.CENTER, maxW = r.width() - 8f)
    }

    /** Names that end in an id ("Shelly Plus 1PM 4022D88E1234 Switch 0") keep their end, which tells
     *  two devices apart: "Shelly Plus…E1234 Switch 0". Other names are left to the usual end ellipsis. */
    fun keepTail(p: Painter, s: String, maxW: Float, size: Float, weight: Int): String {
        if (p.measure(s, size, weight) <= maxW || !Regex("[0-9]").containsMatchIn(s.substringAfterLast(' ', s).ifEmpty { s }) && !Regex("[0-9A-Fa-f]{6,}").containsMatchIn(s)) return s
        var tail = 1
        while (tail < s.length - 1 && p.measure("…" + s.takeLast(tail + 1), size, weight) <= maxW * 0.45f) tail++
        var head = 1
        while (head < s.length - tail && p.measure(s.take(head + 1) + "…" + s.takeLast(tail), size, weight) <= maxW) head++
        return s.take(head).trimEnd() + "…" + s.takeLast(tail).trimStart()
    }

    /** [s] cut at a word boundary (with "…") so it fits [maxW], instead of mid-word. */
    fun fitWords(p: Painter, s: String, maxW: Float, size: Float, weight: Int): String {
        if (p.measure(s, size, weight) <= maxW) return s
        val words = s.split(' ')
        for (n in words.size - 1 downTo 1) {
            val t = words.take(n).joinToString(" ").trimEnd('·', ' ', ',') + "…"
            if (p.measure(t, size, weight) <= maxW) return t
        }
        return s
    }

    val ICON_EMPTY = R.drawable.ic_message_text_outline
}
