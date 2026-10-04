package in_.weenja.hawidgets.core

/**
 * The entity picker as a flat list both apps render: floor headers, area headers, device headers and
 * entity rows ("Kitchen ceiling · on · 60%"). Without a registry scan it groups by domain instead.
 */
object EntityPicker {
    enum class Kind { FLOOR, AREA, DEVICE, ENTITY }

    class Item(
        val kind: Kind,
        val title: String,
        val subtitle: String,
        val entityId: String?,
        val stateLabel: String,
        val icon: String,
        val accent: Accent,
        val active: Boolean,
        val depth: Int,
    )

    /**
     * Rows for the entities [slot] accepts (all entities when null), matching [query] (name, id, area, device,
     * state). [nowMillis] makes timestamps relative.
     */
    fun items(home: Home, slot: SlotSpec?, query: String, nowMillis: Long, includeNoise: Boolean = false): List<Item> {
        val q = query.trim().lowercase()
        val reg = home.registry
        val pool = (if (includeNoise) home.states.values.toList() else home.useful)
            .filter { slot == null || slot.accepts(it) }
            .filter { e ->
                q.isEmpty() || listOf(e.name, e.id, reg.areaOf(e.id)?.name ?: "", reg.deviceOf(e.id)?.name ?: "", reg.floorOf(e.id)?.name ?: "", Rules.stateLabel(e, nowMillis))
                    .any { it.lowercase().contains(q) }
            }
        if (reg.isEmpty) return byDomain(pool, nowMillis)
        val out = ArrayList<Item>()
        val byArea = pool.groupBy { reg.areaOf(it.id)?.id }
        val floorsInUse = reg.floorsOrdered.filter { f -> reg.areas.any { it.floorId == f.id && byArea.containsKey(it.id) } }
        fun areaBlock(area: Area, depth: Int) {
            val es = byArea[area.id] ?: return
            out.add(Item(Kind.AREA, area.name, "${es.size}", null, "", area.icon.removePrefix("mdi:").ifEmpty { "texture-box" }, Accent.MUTED, false, depth))
            deviceBlocks(home, es, depth + 1, nowMillis, out)
        }
        for (f in floorsInUse) {
            out.add(Item(Kind.FLOOR, f.name, "", null, "", f.icon.removePrefix("mdi:").ifEmpty { "home-floor-1" }, Accent.MUTED, false, 0))
            reg.areas.filter { it.floorId == f.id }.sortedBy { it.name.lowercase() }.forEach { areaBlock(it, 1) }
        }
        val noFloor = reg.areas.filter { a -> (a.floorId == null || reg.floor(a.floorId) == null) && byArea.containsKey(a.id) }.sortedBy { it.name.lowercase() }
        if (noFloor.isNotEmpty() && floorsInUse.isNotEmpty()) out.add(Item(Kind.FLOOR, "Other areas", "", null, "", "home-outline", Accent.MUTED, false, 0))
        noFloor.forEach { areaBlock(it, if (floorsInUse.isNotEmpty()) 1 else 0) }
        byArea[null]?.let { es ->
            out.add(Item(Kind.AREA, "No area", "${es.size}", null, "", "help-circle-outline", Accent.MUTED, false, 0))
            deviceBlocks(home, es, 1, nowMillis, out)
        }
        return out
    }

    private fun deviceBlocks(home: Home, es: List<EntityState>, depth: Int, now: Long, out: MutableList<Item>) {
        val reg = home.registry
        val byDevice = es.groupBy { reg.deviceOf(it.id)?.id }
        val sorted = byDevice.entries.sortedBy { (id, list) -> reg.device(id)?.name?.lowercase() ?: list.first().name.lowercase() }
        for ((devId, list) in sorted) {
            val dev = reg.device(devId)
            // a device header only when it groups several entities; single entities stay flat
            val header = dev != null && list.size > 1
            if (dev != null && header) out.add(Item(Kind.DEVICE, dev.name, listOf(dev.manufacturer, dev.model).filter { it.isNotBlank() }.joinToString(" "), null, "", "devices", Accent.MUTED, false, depth))
            for (e in list.sortedBy { it.name.lowercase() }) out.add(row(e, if (header) depth + 1 else depth, now, if (header) "" else dev?.name ?: ""))
        }
    }

    private fun byDomain(es: List<EntityState>, now: Long): List<Item> {
        val out = ArrayList<Item>()
        for ((d, list) in es.groupBy { it.domain }.entries.sortedBy { it.key }) {
            out.add(Item(Kind.AREA, domainTitle(d), "${list.size}", null, "", Rules.icon(list.first()), Accent.MUTED, false, 0))
            list.sortedBy { it.name.lowercase() }.forEach { out.add(row(it, 1, now, "")) }
        }
        return out
    }

    fun row(e: EntityState, depth: Int, now: Long, subtitle: String): Item =
        Item(Kind.ENTITY, e.name, subtitle.ifEmpty { e.id }, e.id, Rules.stateLabel(e, now), Rules.icon(e), Rules.accent(e), Rules.isActive(e), depth)

    fun domainTitle(d: String): String = when (d) {
        "light" -> "Lights"; "switch" -> "Switches"; "fan" -> "Fans"; "cover" -> "Covers"; "lock" -> "Locks"; "climate" -> "Climate"
        "media_player" -> "Media players"; "sensor" -> "Sensors"; "binary_sensor" -> "Binary sensors"; "scene" -> "Scenes"; "script" -> "Scripts"
        "input_boolean" -> "Toggles"; "camera" -> "Cameras"; "person" -> "People"; "weather" -> "Weather"; "todo" -> "To-do lists"
        "vacuum" -> "Vacuums"; "calendar" -> "Calendars"; "alarm_control_panel" -> "Alarms"; "timer" -> "Timers"
        else -> Rules.pretty(d)
    }

    /** "Kitchen · Ground floor" for an entity's secondary line. */
    fun whereLine(home: Home, entityId: String): String {
        val reg = home.registry
        return listOfNotNull(reg.areaOf(entityId)?.name, reg.floorOf(entityId)?.name, reg.deviceOf(entityId)?.name?.takeIf { it.isNotBlank() }).distinct().joinToString(" · ")
    }
}
