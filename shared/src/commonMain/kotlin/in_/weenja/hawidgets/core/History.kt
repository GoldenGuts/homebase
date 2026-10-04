package in_.weenja.hawidgets.core

/** A numeric series for the 24 h sensor graph. */
class Series(val entityId: String, val points: List<Point>, val unit: String) {
    class Point(val atMillis: Long, val value: Double)

    val isEmpty get() = points.isEmpty()
    val min: Double? get() = points.minOfOrNull { it.value }
    val max: Double? get() = points.maxOfOrNull { it.value }
    val last: Double? get() = points.lastOrNull()?.value

    /** Average into at most [n] buckets over [fromMillis]..[toMillis], carrying the last value across empty buckets. */
    fun buckets(n: Int, fromMillis: Long, toMillis: Long): List<Double?> {
        if (n <= 0 || toMillis <= fromMillis) return emptyList()
        val sums = DoubleArray(n); val counts = IntArray(n)
        val span = (toMillis - fromMillis).toDouble()
        var before: Double? = null
        for (p in points) {
            if (p.atMillis < fromMillis) { before = p.value; continue }
            val i = (((p.atMillis - fromMillis) / span) * n).toInt().coerceIn(0, n - 1)
            sums[i] += p.value; counts[i]++
        }
        var carry = before
        return (0 until n).map { i -> if (counts[i] > 0) (sums[i] / counts[i]).also { carry = it } else carry }
    }

    /** [buckets] with NaN for empty buckets (Swift cannot see nullable list elements). */
    fun bucketsNaN(n: Int, fromMillis: Long, toMillis: Long): List<Double> = buckets(n, fromMillis, toMillis).map { it ?: Double.NaN }
    val minOr: Double get() = min ?: 0.0
    val maxOr: Double get() = max ?: 0.0

    fun toJson(): Json = Json.obj("entity_id" to entityId, "unit" to unit, "t" to points.map { it.atMillis }, "v" to points.map { it.value })

    companion object {
        /**
         * `/api/history/period/<start>?filter_entity_id=…&minimal_response&no_attributes`: an array of arrays,
         * one per entity; the first row of each carries the entity id, later rows only state + last_changed.
         */
        fun parseHistory(json: Json?, unit: (String) -> String = { "" }): List<Series> {
            if (json == null) return emptyList()
            return json.list.mapNotNull { rows ->
                val list = rows.list
                val id = list.firstOrNull()?.str("entity_id") ?: return@mapNotNull null
                val pts = list.mapNotNull { r ->
                    val v = r.str("state")?.toDoubleOrNull() ?: return@mapNotNull null
                    val t = Time.parseMillis(r.str("last_changed") ?: r.str("last_updated")) ?: return@mapNotNull null
                    Point(t, v)
                }
                Series(id, pts, list.firstOrNull()?.get("attributes")?.str("unit_of_measurement") ?: unit(id))
            }
        }

        fun fromJson(j: Json?): Series? {
            val id = j?.str("entity_id") ?: return null
            val t = j.arr("t"); val v = j.arr("v")
            val pts = t.indices.mapNotNull { i -> val a = t[i].double?.toLong(); val b = v.getOrNull(i)?.double; if (a != null && b != null) Point(a, b) else null }
            return Series(id, pts, j.str("unit") ?: "")
        }
    }
}
