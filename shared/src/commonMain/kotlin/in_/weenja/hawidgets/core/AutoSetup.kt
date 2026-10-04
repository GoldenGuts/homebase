package in_.weenja.hawidgets.core

/** Everything one scan of Home Assistant learned. The planner and the picker read only this. */
class Home(
    val states: Map<String, EntityState>,
    val registry: Registry,
    /** Favorites starred in Home Assistant (`frontend/get_system_data` home). */
    val favorites: List<String>,
    /** What the user usually controls at this time of day (`usage_prediction/common_control`). */
    val suggested: List<String>,
    val energy: EnergySetup,
    val locationName: String = "",
    val scannedAt: Long = 0L,
) {
    operator fun get(id: String): EntityState? = states[id]

    /** Entities worth showing: noise removed, available first. */
    val useful: List<EntityState> by lazy {
        states.values.filter { !Rules.isNoise(it, registry.entry(it.id)) }.sortedBy { it.name.lowercase() }
    }

    fun inArea(areaId: String): List<EntityState> = useful.filter { registry.areaOf(it.id)?.id == areaId }
    fun byDomain(vararg domains: String): List<EntityState> = useful.filter { it.domain in domains }
    /** [byDomain] for one domain (Swift sees varargs as arrays). */
    fun ofDomain(domain: String): List<EntityState> = useful.filter { it.domain == domain }

    fun toJson(): Json = Json.obj("registry" to registry.toJson(), "favorites" to favorites, "suggested" to suggested, "energy" to energy.toJson(),
        "location_name" to locationName, "scanned_at" to scannedAt)

    companion object {
        /** Rebuild from a cached scan ([toJson]) plus the current states. */
        fun fromJson(j: Json?, states: List<EntityState>): Home = Home(
            states.associateBy { it.id }, Registry.fromJson(j?.get("registry")), j?.strings("favorites") ?: emptyList(),
            j?.strings("suggested") ?: emptyList(), EnergySetup.fromJson(j?.get("energy")), j?.str("location_name") ?: "", j?.num("scanned_at")?.toLong() ?: 0L)

        fun of(states: List<EntityState>): Home = Home(states.associateBy { it.id }, Registry.EMPTY, emptyList(), emptyList(), EnergySetup.EMPTY)
    }
}

/**
 * Auto-setup: which widgets fit this home, and how a widget fills itself. The fill order for entity
 * slots is: Home Assistant favorites, then what `common_control` suggests, then area and domain rules.
 */
object Planner {
    /** Order the toggle fallbacks are taken in when favorites and suggestions run out. */
    private val DOMAIN_ORDER = listOf("light", "switch", "fan", "cover", "lock", "input_boolean", "climate", "media_player", "scene", "script")

    /** Candidate widgets for the "For your home" screen, most useful first. */
    fun plan(home: Home): List<WidgetSpec> {
        val out = ArrayList<WidgetSpec>()
        val favs = home.favorites.filter { home[it] != null }
        if (favs.isNotEmpty()) out.add(WidgetSpec("favorites", "Favorites", emptyMap(), mapOf("source" to "ha"), auto = true,
            reason = "${favs.size} favorite${s(favs.size)} starred in Home Assistant"))
        if (home.suggested.any { home[it] != null }) out.add(WidgetSpec("suggested", "Suggested", auto = true, reason = "Changes with the time of day"))
        out.addAll(rooms(home).take(6))
        if (!home.energy.isEmpty) out.add(WidgetSpec("energy", "Energy", auto = true, reason = listOfNotNull(
            "grid".takeIf { home.energy.hasGrid }, "solar".takeIf { home.energy.hasSolar }, "battery".takeIf { home.energy.hasBattery }).joinToString(" · ")))
        security(home, 12).takeIf { it.isNotEmpty() }?.let { ids ->
            out.add(WidgetSpec("security", "Security", auto = true, reason = summarize(ids.mapNotNull { home[it] })))
        }
        home.byDomain("weather").firstOrNull()?.let { out.add(WidgetSpec("weather", it.name, mapOf("weather" to listOf(it.id)), auto = true, reason = "Forecast from ${it.name}")) }
        val players = home.byDomain("media_player")
        if (players.isNotEmpty()) out.add(WidgetSpec("now", "Now playing", mapOf("players" to players.take(8).map { it.id }), auto = true, reason = "${players.size} media player${s(players.size)}"))
        val people = home.byDomain("person")
        if (people.isNotEmpty()) out.add(WidgetSpec("people", "Who's home", mapOf("items" to people.take(6).map { it.id }), auto = true, reason = people.joinToString(", ") { it.name }))
        home.byDomain("camera").firstOrNull()?.let { out.add(WidgetSpec("camera", it.name, mapOf("camera" to listOf(it.id)), auto = true, reason = "Latest snapshot")) }
        home.byDomain("vacuum").firstOrNull()?.let { out.add(WidgetSpec("vacuum", it.name, mapOf("vacuum" to listOf(it.id)), auto = true, reason = Rules.stateLabel(it))) }
        val covers = home.byDomain("cover").filter { it.deviceClass !in setOf("door", "gate") }
        if (covers.size >= 2) out.add(WidgetSpec("covers", "Blinds & garage", mapOf("items" to covers.take(6).map { it.id }), auto = true, reason = "${covers.size} covers"))
        home.byDomain("todo").firstOrNull()?.let { out.add(WidgetSpec("todo", it.name, mapOf("list" to listOf(it.id)), auto = true, reason = "Tap an item to tick it")) }
        val cals = home.byDomain("calendar")
        if (cals.isNotEmpty()) out.add(WidgetSpec("calendar", "Calendar", mapOf("items" to cals.take(6).map { it.id }), auto = true, reason = "${cals.size} calendar${s(cals.size)}"))
        val timers = countdowns(home)
        if (timers.isNotEmpty()) out.add(WidgetSpec("countdown", "Timers", mapOf("items" to timers.take(4)), auto = true, reason = timers.take(3).mapNotNull { home[it]?.name }.joinToString(", ")))
        val low = batteries(home)
        if (low.isNotEmpty()) out.add(WidgetSpec("battery", "Batteries & offline", auto = true, reason = "${low.size} battery-powered device${s(low.size)}"))
        bins(home).takeIf { it.isNotEmpty() }?.let { out.add(WidgetSpec("bins", "Bin day", mapOf("items" to it.take(4)), auto = true, reason = it.take(3).mapNotNull { id -> home[id]?.name }.joinToString(", "))) }
        ev(home)?.let { out.add(it) }
        graphCandidate(home)?.let { out.add(WidgetSpec("graph", it.name, mapOf("sensor" to listOf(it.id)), auto = true, reason = "24 h of ${it.name}")) }
        val scenes = scenes(home).take(6)
        if (scenes.size >= 2) out.add(WidgetSpec("scenes", "Scenes", mapOf("items" to scenes), auto = true, reason = scenes.take(3).mapNotNull { home[it]?.name }.joinToString(", ")))
        return out
    }

    /**
     * Auto-fill for a widget added from the launcher. [taken] are areas / entities other widgets of the same
     * kind already show, so a second Room widget picks the next room. Null when nothing fits.
     */
    fun fill(kind: String, home: Home, n: Int = 8, taken: Set<String> = emptySet()): WidgetSpec? = when (kind) {
        "favorites" -> WidgetSpec("favorites", "Favorites", if (home.favorites.isEmpty()) mapOf("items" to fillTaps(home, 12, emptySet())) else emptyMap(),
            if (home.favorites.isEmpty()) emptyMap() else mapOf("source" to "ha"), auto = true)
        "suggested" -> WidgetSpec("suggested", "Suggested", auto = true)
        "devices" -> WidgetSpec("devices", "My devices", mapOf("items" to fillToggles(home, n, taken)), auto = true)
        "room" -> rooms(home).firstOrNull { it.option("area") !in taken } ?: rooms(home).firstOrNull()
        "energy" -> WidgetSpec("energy", "Energy", auto = true)
        "security" -> WidgetSpec("security", "Security", auto = true)
        "graph" -> graphCandidate(home, taken)?.let { WidgetSpec("graph", it.name, mapOf("sensor" to listOf(it.id)), auto = true) }
        "weather" -> home.byDomain("weather").firstOrNull()?.let { WidgetSpec("weather", it.name, mapOf("weather" to listOf(it.id)), auto = true) }
        "now" -> WidgetSpec("now", "Now playing", mapOf("players" to home.byDomain("media_player").take(8).map { it.id }), auto = true)
        "todo" -> (home.byDomain("todo").firstOrNull { it.id !in taken } ?: home.byDomain("todo").firstOrNull())?.let { WidgetSpec("todo", it.name, mapOf("list" to listOf(it.id)), auto = true) }
        "scenes" -> WidgetSpec("scenes", "Scenes", mapOf("items" to scenes(home).take(6)), auto = true)
        "light" -> fillOne(home, taken, "light")?.let { WidgetSpec("light", it.name, mapOf("light" to listOf(it.id)), auto = true) }
        "tile" -> fillTaps(home, 1, taken).firstOrNull()?.let { WidgetSpec("tile", home[it]?.name ?: "", mapOf("entity" to listOf(it)), auto = true) }
        "people" -> WidgetSpec("people", "Who's home", mapOf("items" to home.byDomain("person").take(6).map { it.id }), auto = true)
        "camera" -> fillOne(home, taken, "camera")?.let { WidgetSpec("camera", it.name, mapOf("camera" to listOf(it.id)), auto = true) }
        "vacuum" -> fillOne(home, taken, "vacuum")?.let { WidgetSpec("vacuum", it.name, mapOf("vacuum" to listOf(it.id)), auto = true) }
        "covers" -> WidgetSpec("covers", "Blinds & garage", mapOf("items" to home.byDomain("cover").take(6).map { it.id }), auto = true)
        "countdown" -> WidgetSpec("countdown", "Timers", mapOf("items" to countdowns(home).take(4)), auto = true)
        "calendar" -> WidgetSpec("calendar", "Calendar", mapOf("items" to home.byDomain("calendar").take(6).map { it.id }), auto = true)
        "battery" -> WidgetSpec("battery", "Batteries & offline", auto = true)
        "bins" -> WidgetSpec("bins", "Bin day", mapOf("items" to bins(home).take(4)), auto = true)
        "ev" -> ev(home)
        "remote" -> home.byDomain("media_player").firstOrNull { it.deviceClass == "tv" }?.let { tv ->
            WidgetSpec("remote", tv.name, mapOf("tv" to listOf(tv.id)) + (home.byDomain("remote").firstOrNull()?.let { mapOf("remote" to listOf(it.id)) } ?: emptyMap()), auto = true)
        }
        else -> null
    }

    /** Toggle slots: HA favorites, then common_control, then lights, switches, fans… by area. */
    fun fillToggles(home: Home, n: Int, exclude: Set<String> = emptySet()): List<String> = fillFrom(home, n, exclude) { Rules.isToggle(it) }

    /** Tappable slots (adds scenes, scripts, media, climate). */
    fun fillTaps(home: Home, n: Int, exclude: Set<String> = emptySet()): List<String> = fillFrom(home, n, exclude) { it.domain in Rules.TAPPABLE_DOMAINS }

    private fun fillFrom(home: Home, n: Int, exclude: Set<String>, accept: (EntityState) -> Boolean): List<String> {
        val out = LinkedHashSet<String>()
        fun take(ids: List<String>) { for (id in ids) { if (out.size >= n) return; val e = home[id] ?: continue; if (id !in exclude && accept(e) && e.available) out.add(id) } }
        take(home.favorites)
        take(home.suggested)
        // area and domain rules: rooms in floor order, lights first
        val byArea = home.useful.filter(accept).sortedWith(compareBy<EntityState>(
            { floorRank(home, it) }, { home.registry.areaOf(it.id)?.name ?: "~" },
            { DOMAIN_ORDER.indexOf(it.domain).let { i -> if (i < 0) 99 else i } }, { it.name.lowercase() }))
        take(byArea.map { it.id })
        return out.toList()
    }

    private fun fillOne(home: Home, taken: Set<String>, domain: String): EntityState? {
        val order = fillFrom(home, 50, emptySet()) { it.domain == domain }
        return order.firstOrNull { it !in taken }?.let { home[it] } ?: order.firstOrNull()?.let { home[it] }
    }

    private fun floorRank(home: Home, e: EntityState): Int {
        val f = home.registry.floorOf(e.id) ?: return Int.MAX_VALUE
        return home.registry.floorsOrdered.indexOf(f)
    }

    /** One Room plan per area worth a widget: it has a climate reading or at least two things to control. */
    fun rooms(home: Home): List<WidgetSpec> {
        val reg = home.registry
        val areas = reg.areas.sortedWith(compareBy<Area>({ a -> reg.floor(a.floorId)?.let { reg.floorsOrdered.indexOf(it) } ?: Int.MAX_VALUE }, { it.name.lowercase() }))
        return areas.mapNotNull { area -> roomOf(home, area) }.sortedByDescending { it.option("score")?.toIntOrNull() ?: 0 }
            .map { it.withOption("score", null).with(auto = true) }
    }

    private fun roomOf(home: Home, area: Area): WidgetSpec? {
        val inside = home.inArea(area.id)
        val temp = area.temperatureEntityId?.takeIf { home[it] != null }
            ?: inside.firstOrNull { it.domain == "sensor" && it.deviceClass == "temperature" && it.available }?.id
        val hum = area.humidityEntityId?.takeIf { home[it] != null }
            ?: inside.firstOrNull { it.domain == "sensor" && it.deviceClass == "humidity" && it.available }?.id
        val climate = inside.firstOrNull { it.domain == "climate" }?.id
        val controls = inside.filter { it.domain in Rules.TAPPABLE_DOMAINS && it.domain !in setOf("climate", "script", "automation", "button", "input_button") }
            .sortedWith(compareBy({ DOMAIN_ORDER.indexOf(it.domain).let { i -> if (i < 0) 99 else i } }, { it.name.lowercase() }))
        val score = (if (temp != null || climate != null) 2 else 0) + controls.size
        if (score < 2) return null
        val slots = LinkedHashMap<String, List<String>>()
        temp?.let { slots["temperature"] = listOf(it) }
        hum?.let { slots["humidity"] = listOf(it) }
        climate?.let { slots["climate"] = listOf(it) }
        if (controls.isNotEmpty()) slots["items"] = controls.take(8).map { it.id }
        return WidgetSpec("room", area.name, slots, mapOf("area" to area.id, "score" to score.toString()), auto = true,
            reason = summarize(controls) + (if (climate != null) " · thermostat" else ""))
    }

    /** Scenes first, then scripts (which the default dashboard hides, but a Scenes widget wants). */
    fun scenes(home: Home): List<String> {
        val favs = home.favorites.filter { it.startsWith("scene.") || it.startsWith("script.") }
        val rest = home.states.values.filter { (it.domain == "scene" || it.domain == "script") && home.registry.entry(it.id)?.let { e -> e.hidden || e.entityCategory != null } != true }
            .sortedWith(compareBy({ if (it.domain == "scene") 0 else 1 }, { it.name.lowercase() })).map { it.id }
        return (favs + rest).distinct()
    }

    /** Security entities: alarm, locks, garage, doors, windows, then smoke and leaks. */
    fun security(home: Home, n: Int): List<String> {
        fun rank(e: EntityState): Int = when {
            e.domain == "alarm_control_panel" -> 0
            e.domain == "lock" -> 1
            e.domain == "cover" -> 2
            e.deviceClass == "garage_door" -> 2
            e.deviceClass == "door" -> 3
            e.deviceClass == "window" -> 4
            e.deviceClass in setOf("smoke", "gas", "carbon_monoxide") -> 5
            e.deviceClass == "moisture" -> 6
            e.domain == "camera" -> 8
            else -> 7
        }
        return home.useful.filter { Rules.isSecurity(it) && it.domain != "camera" }.sortedWith(compareBy({ rank(it) }, { it.name.lowercase() })).take(n).map { it.id }
    }

    fun countdowns(home: Home): List<String> = home.useful.filter {
        it.domain == "timer" || (it.domain == "sensor" && it.deviceClass == "timestamp" && TIMERISH.containsMatchIn(it.id + " " + it.name.lowercase()))
    }.map { it.id }

    /** Battery sensors (for the battery widget); low ones first. */
    fun batteries(home: Home): List<String> = home.states.values.filter { it.domain == "sensor" && it.deviceClass == "battery" && it.number != null && it.id != home.energy.batterySoc }
        .sortedBy { it.number ?: 100.0 }.map { it.id }

    /** Devices whose every entity is unavailable, by name. */
    fun offlineDevices(home: Home): List<String> {
        val reg = home.registry
        if (reg.isEmpty) return home.states.values.filter { it.state == "unavailable" && !Rules.isNoise(it, null) }.map { it.name }.distinct().sorted()
        val byDevice = home.states.values.groupBy { reg.entry(it.id)?.deviceId }
        return byDevice.filterKeys { it != null }.filter { (_, es) -> es.isNotEmpty() && es.all { it.state == "unavailable" } }
            .mapNotNull { (id, _) -> reg.device(id)?.takeIf { !it.disabled }?.name }.distinct().sorted()
    }

    fun bins(home: Home): List<String> = home.useful.filter { (it.domain == "sensor" || it.domain == "calendar") && BINS.containsMatchIn(it.id + " " + it.name.lowercase()) }.map { it.id }

    fun ev(home: Home): WidgetSpec? {
        val battery = home.useful.firstOrNull { it.domain == "sensor" && it.deviceClass == "battery" && EV.containsMatchIn(it.id + " " + it.name.lowercase()) } ?: return null
        val dev = home.registry.deviceOf(battery.id)?.id
        fun sibling(pred: (EntityState) -> Boolean) = home.useful.firstOrNull { pred(it) && (dev == null || home.registry.deviceOf(it.id)?.id == dev) }
        val slots = LinkedHashMap<String, List<String>>()
        slots["battery"] = listOf(battery.id)
        sibling { it.domain == "binary_sensor" && (it.deviceClass == "battery_charging" || it.id.contains("charging")) }?.let { slots["charging"] = listOf(it.id) }
        sibling { it.domain == "sensor" && it.deviceClass == "distance" }?.let { slots["range"] = listOf(it.id) }
        sibling { it.domain == "sensor" && it.deviceClass == "power" }?.let { slots["power"] = listOf(it.id) }
        val name = home.registry.deviceOf(battery.id)?.name?.takeIf { it.isNotBlank() } ?: "Car"
        return WidgetSpec("ev", name, slots, auto = true, reason = Rules.stateLabel(battery))
    }

    /** A sensor worth graphing: a room temperature first, else any power or measurement sensor. */
    fun graphCandidate(home: Home, taken: Set<String> = emptySet()): EntityState? {
        val sensors = home.useful.filter { it.domain == "sensor" && it.number != null && it.attrString("state_class") != null && it.id !in taken }
        return sensors.firstOrNull { it.deviceClass == "temperature" && home.registry.areaOf(it.id) != null }
            ?: sensors.firstOrNull { it.deviceClass == "temperature" } ?: sensors.firstOrNull { it.deviceClass == "power" } ?: sensors.firstOrNull()
    }

    /** "3 lights · 2 switches". */
    fun summarize(es: List<EntityState>): String {
        val groups = es.groupBy { it.domain }.entries.sortedByDescending { it.value.size }.take(3)
        return groups.joinToString(" · ") { (d, l) -> "${l.size} ${noun(d, l.size)}" }
    }

    private fun noun(domain: String, n: Int): String {
        val one = when (domain) {
            "light" -> "light"; "switch" -> "switch"; "fan" -> "fan"; "cover" -> "cover"; "lock" -> "lock"; "input_boolean" -> "toggle"
            "media_player" -> "player"; "binary_sensor" -> "sensor"; "alarm_control_panel" -> "alarm"; "scene" -> "scene"; "script" -> "script"
            "climate" -> "thermostat"; "camera" -> "camera"; else -> domain.replace('_', ' ')
        }
        if (n == 1) return one
        return if (one.endsWith("ch") || one.endsWith("sh")) one + "es" else one + "s"
    }

    private fun s(n: Int) = if (n == 1) "" else "s"

    private val TIMERISH = Regex("wash|dry|dish|oven|timer|remaining|finish|end_time|ends|done|cycle")
    private val BINS = Regex("bin|waste|trash|garbage|recycl|rubbish|compost|refuse|müll|abfall|afval|poubelle")
    private val EV = Regex("\\bev\\b|car|vehicle|tesla|leaf|model_|ioniq|kona|id\\.|polestar|bmw|audi|volvo|niro|zoe|charge")
}
