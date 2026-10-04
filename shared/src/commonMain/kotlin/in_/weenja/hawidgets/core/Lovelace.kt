package in_.weenja.hawidgets.core

/**
 * Dashboard import: turns the cards of a Lovelace dashboard (`lovelace/config`) into widget plans.
 * Only the card types that map onto a widget are used; the rest are skipped.
 */
object Lovelace {
    class Dashboard(val urlPath: String?, val title: String)

    /** `lovelace/dashboards/list` plus the default dashboard. */
    fun dashboards(result: Json?): List<Dashboard> =
        listOf(Dashboard(null, "Overview")) + (result?.list ?: emptyList()).mapNotNull { d ->
            val path = d.str("url_path") ?: return@mapNotNull null
            if (d.str("mode") == "yaml" && d.str("filename").isNullOrEmpty()) null else Dashboard(path, d.str("title") ?: path)
        }

    fun plans(config: Json?, home: Home, dashboardTitle: String): List<WidgetSpec> {
        if (config == null) return emptyList()
        val out = ArrayList<WidgetSpec>()
        for (view in config.arr("views")) {
            val viewTitle = view.str("title") ?: dashboardTitle
            val cards = view.arr("cards") + view.arr("sections").flatMap { it.arr("cards") }
            // loose tiles / buttons of one view become one Favorites-style grid
            val loose = ArrayList<String>()
            for (c in cards) walk(c, home, viewTitle, out, loose)
            val known = loose.filter { home[it] != null }.distinct()
            if (known.size >= 2) out.add(WidgetSpec("favorites", viewTitle, mapOf("items" to known.take(12)), mapOf("source" to "custom"), auto = true,
                reason = "From $dashboardTitle · $viewTitle"))
        }
        return out.distinctBy { it.toString() }
    }

    private fun entityOf(c: Json): String? = c.str("entity") ?: c["entity"]?.str("entity")

    private fun entitiesOf(c: Json): List<String> = c.arr("entities").mapNotNull { it.string ?: it.str("entity") }

    private fun walk(c: Json, home: Home, viewTitle: String, out: MutableList<WidgetSpec>, loose: MutableList<String>) {
        val title = c.str("title") ?: c.str("name") ?: ""
        val src = "From dashboard · $viewTitle"
        when (c.str("type")) {
            "vertical-stack", "horizontal-stack", "grid", "conditional" -> {
                (c.arr("cards") + listOfNotNull(c["card"])).forEach { walk(it, home, viewTitle, out, loose) }
            }
            "entities", "glance" -> {
                val ids = entitiesOf(c).filter { home[it] != null }
                if (ids.size >= 2) out.add(WidgetSpec("favorites", title.ifEmpty { viewTitle }, mapOf("items" to ids.take(12)), mapOf("source" to "custom"), auto = true, reason = src))
                else loose.addAll(ids)
            }
            "tile", "button", "light", "entity", "mushroom-entity-card", "mushroom-light-card" -> entityOf(c)?.let { loose.add(it) }
            "thermostat", "humidifier" -> entityOf(c)?.let { id ->
                val area = home.registry.areaOf(id)
                out.add(WidgetSpec("room", area?.name ?: home[id]?.name ?: title, mapOf("climate" to listOf(id)), area?.let { mapOf("area" to it.id) } ?: emptyMap(), auto = true, reason = src))
            }
            "weather-forecast" -> entityOf(c)?.let { out.add(WidgetSpec("weather", home[it]?.name ?: "Weather", mapOf("weather" to listOf(it)), auto = true, reason = src)) }
            "media-control" -> entityOf(c)?.let { out.add(WidgetSpec("now", "Now playing", mapOf("players" to listOf(it)), auto = true, reason = src)) }
            "picture-entity", "picture-glance" -> (c.str("camera_image") ?: entityOf(c))?.takeIf { it.startsWith("camera.") }?.let {
                out.add(WidgetSpec("camera", home[it]?.name ?: title, mapOf("camera" to listOf(it)), auto = true, reason = src))
            }
            "history-graph", "sensor", "statistics-graph", "statistic", "gauge" -> (entityOf(c) ?: entitiesOf(c).firstOrNull())?.takeIf { it.startsWith("sensor.") }?.let {
                out.add(WidgetSpec("graph", home[it]?.name ?: title, mapOf("sensor" to listOf(it)), auto = true, reason = src))
            }
            "area" -> c.str("area")?.let { areaId -> Planner.rooms(home).firstOrNull { it.option("area") == areaId }?.let { out.add(it.with(reason = src)) } }
            "alarm-panel" -> out.add(WidgetSpec("security", "Security", auto = true, reason = src))
            "todo-list", "shopping-list" -> entityOf(c)?.let { out.add(WidgetSpec("todo", home[it]?.name ?: "To-do", mapOf("list" to listOf(it)), auto = true, reason = src)) }
            "calendar" -> entitiesOf(c).takeIf { it.isNotEmpty() }?.let { out.add(WidgetSpec("calendar", "Calendar", mapOf("items" to it.take(6)), auto = true, reason = src)) }
            "energy-distribution", "energy-usage-graph", "energy-sources-table", "power-sources-graph" -> out.add(WidgetSpec("energy", "Energy", auto = true, reason = src))
            "map" -> entitiesOf(c).filter { it.startsWith("person.") }.takeIf { it.isNotEmpty() }?.let {
                out.add(WidgetSpec("people", "Who's home", mapOf("items" to it.take(6)), auto = true, reason = src))
            }
        }
    }
}
