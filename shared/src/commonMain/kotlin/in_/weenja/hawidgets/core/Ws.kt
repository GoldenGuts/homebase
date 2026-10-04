package in_.weenja.hawidgets.core

/**
 * Home Assistant WebSocket API messages (https://developers.home-assistant.io/docs/api/websocket).
 * Transport lives in each app; this builds the frames and reads the answers. Nothing here needs an
 * admin user.
 */
object Ws {
    fun auth(accessToken: String): String = Json.obj("type" to "auth", "access_token" to accessToken).toString()

    /** A command frame: {"id": id, "type": type, ...fields}. */
    fun command(id: Int, type: String, fields: Json.Obj? = null): String {
        val m = LinkedHashMap<String, Json>()
        m["id"] = Json.Num(id.toDouble()); m["type"] = Json.Str(type)
        fields?.map?.forEach { (k, v) -> m[k] = v }
        return Json.Obj(m).toString()
    }

    fun commandOf(id: Int, cmd: Command): String = command(id, cmd.type, cmd.fields)

    class Command(val type: String, val fields: Json.Obj? = null)

    class Message(
        val type: String,
        val id: Int?,
        val success: Boolean,
        val result: Json?,
        val errorCode: String?,
        val errorMessage: String?,
        val event: Json?,
        val haVersion: String?,
    ) {
        val isAuthRequired get() = type == "auth_required"
        val isAuthOk get() = type == "auth_ok"
        val isAuthInvalid get() = type == "auth_invalid"
        val isResult get() = type == "result"
        val isEvent get() = type == "event"
    }

    /** One incoming frame. HA may coalesce several messages into a JSON array. */
    fun parse(text: String): List<Message> {
        val j = Json.parseOrNull(text) ?: return emptyList()
        val items = if (j is Json.Arr) j.items else listOf(j)
        return items.map { m ->
            Message(m.str("type") ?: "", m.num("id")?.toInt(), m.flag("success") == true, m["result"],
                m["error"]?.str("code"), m["error"]?.str("message") ?: m.str("message"), m["event"], m.str("ha_version"))
        }
    }

    // ------------------------------------------------------------ the scan

    /** Everything auto-setup reads, in order. Every one works for a non-admin user. */
    val SCAN: List<Pair<String, Command>> = listOf(
        "states" to Command("get_states"),
        "areas" to Command("config/area_registry/list"),
        "floors" to Command("config/floor_registry/list"),
        "labels" to Command("config/label_registry/list"),
        "devices" to Command("config/device_registry/list"),
        "entities" to Command("config/entity_registry/list_for_display"),
        "favorites" to Command("frontend/get_system_data", Json.obj("key" to "home")),
        // takes no options: Home Assistant rejects extra keys ("limit") with invalid_format
        "suggested" to Command("usage_prediction/common_control"),
        "energy" to Command("energy/get_prefs"),
        "config" to Command("get_config"),
    )

    /** Statistic change over today (energy totals). */
    fun statisticToday(statisticId: String): Command =
        Command("recorder/statistic_during_period", Json.obj("statistic_id" to statisticId, "calendar" to Json.obj("period" to "day"), "types" to listOf("change")))

    fun subscribe(eventType: String): Command = Command("subscribe_events", Json.obj("event_type" to eventType))

    /** Events that mean the scan is stale. */
    val SYNC_EVENTS = listOf("area_registry_updated", "floor_registry_updated", "device_registry_updated", "entity_registry_updated",
        "label_registry_updated", "lovelace_updated")

    fun lovelaceDashboards(): Command = Command("lovelace/dashboards/list")
    fun lovelaceConfig(urlPath: String?): Command = Command("lovelace/config", Json.obj("url_path" to urlPath))

    fun callService(domain: String, service: String, data: Json.Obj?, returnResponse: Boolean = false): Command =
        Command("call_service", Json.obj("domain" to domain, "service" to service, "service_data" to (data ?: Json.Obj(emptyMap())), "return_response" to if (returnResponse) true else null)
            .let { o -> Json.Obj(o.map.filterValues { !it.isNull }) })

    /** Entity ids in a `frontend/get_system_data` home answer, wherever they sit. */
    fun favoritesOf(result: Json?): List<String> {
        if (result == null) return emptyList()
        val found = ArrayList<String>()
        fun walk(x: Json) {
            when (x) {
                is Json.Obj -> for ((k, v) in x.map) { if (k == "favorite_entities" || k == "favorites") found.addAll(v.list.mapNotNull { it.string ?: it.str("entity_id") }) else walk(v) }
                is Json.Arr -> x.items.forEach { walk(it) }
                else -> {}
            }
        }
        walk(result)
        return found.filter { it.contains('.') }.distinct()
    }

    /** Entity ids in a `usage_prediction/common_control` answer ({"entities": [...]}). */
    fun suggestedOf(result: Json?): List<String> {
        if (result == null) return emptyList()
        val direct = (result as? Json.Arr)?.items ?: result.arr("entities").ifEmpty { result.arr("entity_ids") }
        return direct.mapNotNull { it.string ?: it.str("entity_id") }.filter { it.contains('.') }.distinct()
    }

    /** `recorder/statistic_during_period` answer: {"change": 12.3}. */
    fun changeOf(result: Json?): Double? = result?.num("change")
}
