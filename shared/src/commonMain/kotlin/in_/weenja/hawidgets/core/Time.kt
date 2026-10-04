package in_.weenja.hawidgets.core

/** ISO-8601 parsing and relative-time labels. Platform clocks pass "now" in, so this stays pure. */
object Time {
    /**
     * Epoch milliseconds of an ISO-8601 date-time ("2026-09-27T22:54:20.123456+00:00", "…Z", "2026-09-27").
     * A value without an offset is read as UTC plus [defaultOffsetMinutes]. Null when unparseable.
     */
    fun parseMillis(iso: String?, defaultOffsetMinutes: Int = 0): Long? {
        if (iso.isNullOrBlank()) return null
        val s = iso.trim()
        val m = ISO.matchEntire(s) ?: return null
        val g = m.groupValues
        val year = g[1].toInt(); val month = g[2].toInt(); val day = g[3].toInt()
        val hour = g[4].toIntOrNull() ?: 0; val minute = g[5].toIntOrNull() ?: 0; val second = g[6].toIntOrNull() ?: 0
        val frac = g[7].takeIf { it.isNotEmpty() }?.let { (it + "000").substring(0, 3).toInt() } ?: 0
        val offsetMin = when {
            g[8].isEmpty() -> defaultOffsetMinutes
            g[8] == "Z" || g[8] == "z" -> 0
            else -> {
                val sign = if (g[8][0] == '-') -1 else 1
                val digits = g[8].substring(1).replace(":", "")
                sign * (digits.substring(0, 2).toInt() * 60 + (digits.substring(2).toIntOrNull() ?: 0))
            }
        }
        if (month !in 1..12 || day !in 1..31) return null
        val days = daysFromCivil(year, month, day)
        return ((days * 86400L + hour * 3600L + minute * 60L + second) - offsetMin * 60L) * 1000L + frac
    }

    /** [parseMillis] for Swift: [def] when unparseable. */
    fun parseOr(iso: String?, def: Long): Long = parseMillis(iso) ?: def

    private val ISO = Regex("""(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:[.,](\d+))?)?(Z|z|[+-]\d{2}:?\d{2})?""")

    /** Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's algorithm). */
    fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }

    /** "just now", "4 min ago", "2 h ago", "3 days ago". */
    fun ago(thenMillis: Long, nowMillis: Long): String {
        val s = ((nowMillis - thenMillis) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> "${s / 3600} h ago"
            else -> "${s / 86400} day${if (s / 86400 == 1L) "" else "s"} ago"
        }
    }

    /** Short duration: "45s", "12 min", "1 h 05", "2 d 3 h". */
    fun duration(millis: Long): String {
        val s = (millis / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60} min"
            s < 86400 -> "${s / 3600} h ${(s % 3600 / 60).toString().padStart(2, '0')}"
            else -> "${s / 86400} d ${s % 86400 / 3600} h"
        }
    }

    /** Clock "HH:mm" of [millis] at a local UTC offset in minutes. */
    fun clock(millis: Long, offsetMinutes: Int): String {
        val local = millis / 1000 + offsetMinutes * 60L
        val secOfDay = ((local % 86400) + 86400) % 86400
        return "${(secOfDay / 3600).toString().padStart(2, '0')}:${(secOfDay % 3600 / 60).toString().padStart(2, '0')}"
    }
}
