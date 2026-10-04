package in_.weenja.hawidgets.core

/** One entity state as `/api/states` or the WebSocket `get_states` returns it. */
class EntityState(
    val id: String,
    val state: String,
    val attributes: Json,
    val lastChanged: String = "",
    val lastUpdated: String = "",
) {
    val domain: String get() = id.substringBefore('.')
    val objectId: String get() = id.substringAfter('.')
    val name: String get() = attributes.str("friendly_name")?.takeIf { it.isNotBlank() } ?: objectId.replace('_', ' ').replaceFirstChar { it.uppercase() }
    val deviceClass: String get() = attributes.str("device_class") ?: ""
    val unit: String get() = attributes.str("unit_of_measurement") ?: ""
    val icon: String get() = attributes.str("icon") ?: ""
    val available: Boolean get() = state != "unavailable" && state != "unknown"
    val isOn: Boolean get() = state == "on"
    val number: Double? get() = state.toDoubleOrNull()

    fun attr(key: String): Json? = attributes[key]
    fun attrString(key: String): String? = attributes.str(key)
    fun attrDouble(key: String): Double? = attributes.num(key)
    fun attrBool(key: String): Boolean? = attributes.flag(key)

    // Swift-friendly accessors (nullable numbers cross into Swift as boxed objects)
    fun numberOr(def: Double): Double = number ?: def
    fun attrNumberOr(key: String, def: Double): Double = attrDouble(key) ?: def
    fun attrStringOr(key: String, def: String): String = attrString(key) ?: def
    val isAvailable: Boolean get() = available

    /** Brightness 0..100 of a light that is on, else null. */
    val brightnessPct: Int? get() = attrDouble("brightness")?.let { kotlin.math.round(it / 2.55).toInt() }

    fun toJson(): Json = Json.obj("entity_id" to id, "state" to state, "attributes" to attributes, "last_changed" to lastChanged, "last_updated" to lastUpdated)

    /** Same entity with another state (optimistic repaint after a tap). */
    fun withState(newState: String): EntityState = EntityState(id, newState, attributes, lastChanged, lastUpdated)

    companion object {
        fun from(o: Json): EntityState? {
            val id = o.str("entity_id") ?: return null
            if (!id.contains('.')) return null
            return EntityState(id, o.str("state") ?: "unknown", o["attributes"] ?: Json.Obj(emptyMap()), o.str("last_changed") ?: "", o.str("last_updated") ?: "")
        }

        fun parseList(json: Json): List<EntityState> = json.list.mapNotNull { from(it) }
        fun parseList(text: String): List<EntityState> = parseList(Json.parse(text))
    }
}

class Floor(val id: String, val name: String, val level: Int?, val icon: String)

class Area(
    val id: String,
    val name: String,
    val floorId: String?,
    val icon: String,
    val temperatureEntityId: String?,
    val humidityEntityId: String?,
    val labels: List<String>,
)

class Device(
    val id: String,
    val name: String,
    val areaId: String?,
    val manufacturer: String,
    val model: String,
    val disabled: Boolean,
)

/** The compact `list_for_display` record: ei, di, ai, lb, ec, hb, en, pl. */
class EntityEntry(
    val entityId: String,
    val name: String?,
    val areaId: String?,
    val deviceId: String?,
    val entityCategory: String?,
    val hidden: Boolean,
    val platform: String?,
    val labels: List<String>,
)

class HaLabel(val id: String, val name: String, val color: String, val icon: String)

/** Areas, floors, devices, labels and entity entries of one Home Assistant. Empty when never scanned. */
class Registry(
    val areas: List<Area>,
    val floors: List<Floor>,
    val devices: List<Device>,
    val entities: List<EntityEntry>,
    val labels: List<HaLabel> = emptyList(),
) {
    private val areaById = areas.associateBy { it.id }
    private val floorById = floors.associateBy { it.id }
    private val deviceById = devices.associateBy { it.id }
    private val entryById = entities.associateBy { it.entityId }

    val isEmpty: Boolean get() = areas.isEmpty() && devices.isEmpty() && entities.isEmpty()

    fun area(id: String?): Area? = id?.let { areaById[it] }
    fun floor(id: String?): Floor? = id?.let { floorById[it] }
    fun device(id: String?): Device? = id?.let { deviceById[it] }
    fun entry(entityId: String): EntityEntry? = entryById[entityId]

    /** The entity's own area, else its device's area. */
    fun areaOf(entityId: String): Area? {
        val e = entryById[entityId] ?: return null
        return area(e.areaId) ?: area(device(e.deviceId)?.areaId)
    }

    fun floorOf(entityId: String): Floor? = floor(areaOf(entityId)?.floorId)
    fun deviceOf(entityId: String): Device? = device(entryById[entityId]?.deviceId)

    /** Floors ordered by level, then name; areas without a floor come last. */
    val floorsOrdered: List<Floor> get() = floors.sortedWith(compareBy<Floor>({ it.level ?: Int.MAX_VALUE }, { it.name.lowercase() }))

    fun toJson(): Json = Json.obj(
        "areas" to areas.map { Json.obj("area_id" to it.id, "name" to it.name, "floor_id" to it.floorId, "icon" to it.icon, "temperature_entity_id" to it.temperatureEntityId, "humidity_entity_id" to it.humidityEntityId, "labels" to it.labels) },
        "floors" to floors.map { Json.obj("floor_id" to it.id, "name" to it.name, "level" to it.level, "icon" to it.icon) },
        "devices" to devices.map { Json.obj("id" to it.id, "name" to it.name, "area_id" to it.areaId, "manufacturer" to it.manufacturer, "model" to it.model, "disabled_by" to if (it.disabled) "user" else null) },
        "entities" to Json.obj("entity_categories" to Json.obj("0" to "config", "1" to "diagnostic"), "entities" to entities.map {
            Json.obj("ei" to it.entityId, "en" to it.name, "ai" to it.areaId, "di" to it.deviceId,
                "ec" to when (it.entityCategory) { "config" -> 0; "diagnostic" -> 1; else -> null }, "hb" to if (it.hidden) true else null, "pl" to it.platform, "lb" to it.labels)
        }),
        "labels" to labels.map { Json.obj("label_id" to it.id, "name" to it.name, "color" to it.color, "icon" to it.icon) },
    )

    companion object {
        val EMPTY = Registry(emptyList(), emptyList(), emptyList(), emptyList())

        fun parseAreas(j: Json): List<Area> = j.list.mapNotNull { a ->
            val id = a.str("area_id") ?: return@mapNotNull null
            Area(id, a.str("name") ?: id, a.str("floor_id"), a.str("icon") ?: "", a.str("temperature_entity_id"), a.str("humidity_entity_id"), a.strings("labels"))
        }

        fun parseFloors(j: Json): List<Floor> = j.list.mapNotNull { f ->
            val id = f.str("floor_id") ?: return@mapNotNull null
            Floor(id, f.str("name") ?: id, f.num("level")?.toInt(), f.str("icon") ?: "")
        }

        fun parseDevices(j: Json): List<Device> = j.list.mapNotNull { d ->
            val id = d.str("id") ?: return@mapNotNull null
            Device(id, d.str("name_by_user") ?: d.str("name") ?: "", d.str("area_id"), d.str("manufacturer") ?: "", d.str("model") ?: "", d["disabled_by"]?.string != null)
        }

        fun parseLabels(j: Json): List<HaLabel> = j.list.mapNotNull { l ->
            val id = l.str("label_id") ?: return@mapNotNull null
            HaLabel(id, l.str("name") ?: id, l.str("color") ?: "", l.str("icon") ?: "")
        }

        /** `config/entity_registry/list_for_display`: {entity_categories: {"0": "config"}, entities: [{ei, …}]}. */
        fun parseEntities(j: Json): List<EntityEntry> {
            val cats = j["entity_categories"]?.obj ?: emptyMap()
            val list = if (j is Json.Arr) j.list else j.arr("entities")
            return list.mapNotNull { e ->
                val id = e.str("ei") ?: e.str("entity_id") ?: return@mapNotNull null
                val ec = e["ec"]?.let { v -> (v as? Json.Num)?.let { cats[it.string]?.string } ?: v.string } ?: e.str("entity_category")
                Entry(id, e.str("en") ?: e.str("name"), e.str("ai") ?: e.str("area_id"), e.str("di") ?: e.str("device_id"), ec,
                    e.flag("hb") == true || e["hidden_by"]?.string != null, e.str("pl") ?: e.str("platform"), e.strings("lb").ifEmpty { e.strings("labels") })
            }
        }

        private fun Entry(id: String, name: String?, ai: String?, di: String?, ec: String?, hidden: Boolean, pl: String?, lb: List<String>) =
            EntityEntry(id, name, ai, di, ec, hidden, pl, lb)

        fun parse(areas: Json?, floors: Json?, devices: Json?, entities: Json?, labels: Json? = null): Registry = Registry(
            areas?.let { parseAreas(it) } ?: emptyList(), floors?.let { parseFloors(it) } ?: emptyList(),
            devices?.let { parseDevices(it) } ?: emptyList(), entities?.let { parseEntities(it) } ?: emptyList(),
            labels?.let { parseLabels(it) } ?: emptyList(),
        )

        /** Inverse of [toJson]. */
        fun fromJson(j: Json?): Registry = if (j == null) EMPTY else parse(j["areas"], j["floors"], j["devices"], j["entities"], j["labels"])

        fun fromJsonText(text: String?): Registry = fromJson(Json.parseOrNull(text))
    }
}
