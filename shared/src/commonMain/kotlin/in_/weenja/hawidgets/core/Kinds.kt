package in_.weenja.hawidgets.core

/** One configurable entity slot of a widget kind. */
class SlotSpec(
    val key: String,
    val label: String,
    /** Allowed domains; empty = any. */
    val domains: Set<String>,
    /** Allowed device classes (for sensors / binary sensors); empty = any. */
    val deviceClasses: Set<String> = emptySet(),
    val multiple: Boolean = false,
    val max: Int = 1,
    val hint: String = "",
) {
    fun accepts(e: EntityState): Boolean =
        (domains.isEmpty() || e.domain in domains) &&
            (deviceClasses.isEmpty() || e.deviceClass in deviceClasses || e.domain !in setOf("sensor", "binary_sensor", "cover"))
}

/** A widget kind both apps know by [id]. */
class KindSpec(
    val id: String,
    val title: String,
    val blurb: String,
    val slots: List<SlotSpec>,
    /** Core widgets show on first run; extras need the author's own Home Assistant packages. */
    val core: Boolean = true,
    /** Fills itself from Home Assistant when added from the launcher. */
    val autoFill: Boolean = true,
) {
    fun slot(key: String): SlotSpec? = slots.firstOrNull { it.key == key }
}

/** A configured widget: its kind, a title, entity slots and free options. Plans and saved configs share it. */
class WidgetSpec(
    val kind: String,
    val title: String = "",
    val slots: Map<String, List<String>> = emptyMap(),
    val options: Map<String, String> = emptyMap(),
    /** Filled by auto-setup (true) or edited by the user (false). */
    val auto: Boolean = false,
    /** Why auto-setup suggests it ("8 lights and a thermostat"). */
    val reason: String = "",
) {
    fun first(slot: String): String? = slots[slot]?.firstOrNull()
    fun list(slot: String): List<String> = slots[slot] ?: emptyList()
    fun option(key: String): String? = options[key]
    val entityIds: List<String> get() = slots.values.flatten().distinct()

    fun with(kind: String = this.kind, title: String = this.title, slots: Map<String, List<String>> = this.slots,
             options: Map<String, String> = this.options, auto: Boolean = this.auto, reason: String = this.reason) =
        WidgetSpec(kind, title, slots, options, auto, reason)

    fun withSlot(key: String, ids: List<String>) = with(slots = LinkedHashMap(slots).apply { if (ids.isEmpty()) remove(key) else put(key, ids) }, auto = false)
    fun withOption(key: String, value: String?) = with(options = LinkedHashMap(options).apply { if (value.isNullOrEmpty()) remove(key) else put(key, value) }, auto = false)

    fun toJson(): Json = Json.obj("kind" to kind, "title" to title, "slots" to slots, "options" to options, "auto" to auto, "reason" to reason)
    override fun toString(): String = toJson().toString()

    companion object {
        fun fromJson(j: Json?): WidgetSpec? {
            val kind = j?.str("kind") ?: return null
            val slots = LinkedHashMap<String, List<String>>()
            j["slots"]?.obj?.forEach { (k, v) -> slots[k] = v.list.mapNotNull { it.string } }
            val opts = LinkedHashMap<String, String>()
            j["options"]?.obj?.forEach { (k, v) -> v.string?.let { opts[k] = it } }
            return WidgetSpec(kind, j.str("title") ?: "", slots, opts, j.flag("auto") == true, j.str("reason") ?: "")
        }

        fun parse(text: String?): WidgetSpec? = fromJson(Json.parseOrNull(text))
    }
}

object Kinds {
    private val TAPPABLE = Rules.TAPPABLE_DOMAINS
    private val TOGGLE = Rules.TOGGLE_DOMAINS

    val FAVORITES = KindSpec("favorites", "Favorites", "Your Home Assistant favorites: state and one-tap control.",
        listOf(SlotSpec("items", "Entities", TAPPABLE + setOf("sensor", "binary_sensor", "person", "camera", "weather"), multiple = true, max = 12,
            hint = "Empty = follow the favorites you starred in Home Assistant")))
    val SUGGESTED = KindSpec("suggested", "Suggested", "What you usually control at this time of day, learned by Home Assistant.", emptyList())
    val DEVICES = KindSpec("devices", "My devices", "Tiles for anything that toggles. More tiles appear as you resize.",
        listOf(SlotSpec("items", "Tiles", TOGGLE, multiple = true, max = 12, hint = "Lights, switches, fans, covers, locks, input booleans")))
    val ROOM = KindSpec("room", "Room", "Temperature, humidity, the thermostat and the devices of one area.",
        listOf(SlotSpec("temperature", "Temperature", setOf("sensor"), setOf("temperature")),
            SlotSpec("humidity", "Humidity", setOf("sensor"), setOf("humidity")),
            SlotSpec("climate", "Thermostat", setOf("climate")),
            SlotSpec("items", "Devices", TAPPABLE, multiple = true, max = 8)))
    val ENERGY = KindSpec("energy", "Energy", "Live power, solar and battery, and today's kWh from your Energy dashboard.",
        listOf(SlotSpec("power", "Live power (optional)", setOf("sensor"), setOf("power"))))
    val SECURITY = KindSpec("security", "Security", "Doors, windows, garage, locks and the alarm at a glance.",
        listOf(SlotSpec("items", "Entities", setOf("lock", "alarm_control_panel", "cover", "binary_sensor", "camera"), multiple = true, max = 12,
            hint = "Empty = every door, window, lock, garage and alarm")))
    val GRAPH = KindSpec("graph", "Sensor graph", "The last 24 hours of one sensor.",
        listOf(SlotSpec("sensor", "Sensor", setOf("sensor", "number", "input_number"))))
    val WEATHER = KindSpec("weather", "Weather", "Now, hi/lo, the next days and hours.",
        listOf(SlotSpec("weather", "Weather entity", setOf("weather"))))
    val NOW = KindSpec("now", "Now playing", "Artwork, title and transport keys of whatever plays.",
        listOf(SlotSpec("players", "Players in priority order", setOf("media_player"), multiple = true, max = 8)))
    val TODO = KindSpec("todo", "To-do", "A to-do list; tap to tick.", listOf(SlotSpec("list", "List", setOf("todo"))))
    val SCENES = KindSpec("scenes", "Scenes", "Scenes, scripts and buttons as gradient tiles.",
        listOf(SlotSpec("items", "Scenes & scripts", setOf("scene", "script", "button", "input_button"), multiple = true, max = 6)))
    val LIGHT = KindSpec("light", "Light", "One light: toggle, brightness, colour temperature, colours.", listOf(SlotSpec("light", "Light", setOf("light"))))
    val TILE = KindSpec("tile", "Tile", "One entity as a 1x1 or 2x1 tile. Tap = toggle or run.", listOf(SlotSpec("entity", "Entity", TAPPABLE + setOf("sensor", "binary_sensor"))))
    val PEOPLE = KindSpec("people", "Who's home", "Everyone in Home Assistant and where they are.", listOf(SlotSpec("items", "People", setOf("person"), multiple = true, max = 6)))
    val CAMERA = KindSpec("camera", "Camera", "The latest snapshot of a camera or doorbell.", listOf(SlotSpec("camera", "Camera", setOf("camera"))))
    val VACUUM = KindSpec("vacuum", "Vacuum", "Robot vacuum state, battery, start, pause and dock.", listOf(SlotSpec("vacuum", "Vacuum", setOf("vacuum"))))
    val COVERS = KindSpec("covers", "Blinds & garage", "Open, stop and close blinds, shades and the garage.",
        listOf(SlotSpec("items", "Covers", setOf("cover"), multiple = true, max = 6)))
    val COUNTDOWN = KindSpec("countdown", "Timers", "Washer, dryer and timer countdowns.",
        listOf(SlotSpec("items", "Timers", setOf("timer", "sensor"), setOf("timestamp", "duration"), multiple = true, max = 4)))
    val CALENDAR = KindSpec("calendar", "Calendar", "The next events of your calendars.", listOf(SlotSpec("items", "Calendars", setOf("calendar"), multiple = true, max = 6)))
    val BATTERY = KindSpec("battery", "Batteries & offline", "Low batteries and devices that dropped off.", emptyList())
    val BINS = KindSpec("bins", "Bin day", "The next waste collection per bin.",
        listOf(SlotSpec("items", "Bins", setOf("sensor", "calendar"), multiple = true, max = 4)))
    val EV = KindSpec("ev", "Car charging", "Battery, range and charging state of an electric car.",
        listOf(SlotSpec("battery", "Battery", setOf("sensor"), setOf("battery")), SlotSpec("charging", "Charging", setOf("binary_sensor", "switch", "sensor")),
            SlotSpec("range", "Range", setOf("sensor"), setOf("distance")), SlotSpec("power", "Charger power", setOf("sensor"), setOf("power"))))
    val REMOTE = KindSpec("remote", "TV remote", "TV keys and a D-pad.",
        listOf(SlotSpec("tv", "TV", setOf("media_player")), SlotSpec("remote", "Remote", setOf("remote"))))
    val SKY = KindSpec("sky", "Sky", "Sun arc with sunrise, sunset and golden hour.", emptyList())

    val CORE: List<KindSpec> = listOf(FAVORITES, SUGGESTED, ROOM, ENERGY, SECURITY, GRAPH, DEVICES, WEATHER, NOW, TODO, SCENES, LIGHT, TILE,
        PEOPLE, CAMERA, VACUUM, COVERS, COUNTDOWN, CALENDAR, BATTERY, BINS, EV, REMOTE, SKY)

    fun get(id: String): KindSpec? = CORE.firstOrNull { it.id == id }
}
