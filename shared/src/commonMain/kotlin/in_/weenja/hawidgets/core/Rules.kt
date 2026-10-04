package in_.weenja.hawidgets.core

import kotlin.math.abs
import kotlin.math.roundToInt

/** Theme accent a tile uses when active. Each platform maps these to its palette. */
enum class Accent { ORANGE, CYAN, PINK, LIME, PURPLE, MUTED }

/** Buckets from Home Assistant's Home dashboard strategy, plus the ones the widgets need. */
enum class Bucket { LIGHTS, CLIMATE, SECURITY, MEDIA, ENERGY, SENSORS, SWITCHES, PEOPLE, OTHER }

/** A service call a tap performs. [optimistic] is the state to paint before Home Assistant answers. */
class ServiceCall(
    val domain: String,
    val service: String,
    val data: Json.Obj,
    val optimistic: String?,
    /** Security-sensitive (unlock, open the garage): the widget asks for a second tap. */
    val confirm: Boolean,
) {
    val key: String get() = "$domain.$service:${data}"
}

object Rules {
    /** Domains a slot / tile can toggle. */
    val TOGGLE_DOMAINS = setOf("light", "switch", "fan", "cover", "lock", "input_boolean")

    /** Everything a tap can act on in a grid (favorites, suggested, rooms). */
    val TAPPABLE_DOMAINS = TOGGLE_DOMAINS + setOf("scene", "script", "button", "input_button", "media_player", "climate",
        "humidifier", "vacuum", "valve", "siren", "automation", "water_heater", "lawn_mower")

    /** Domains the default dashboard never shows (HA frontend generate-lovelace-config), plus Assist entities. */
    val HIDDEN_DOMAINS = setOf("automation", "script", "device_tracker", "event", "sun", "zone", "todo", "tag", "notify", "ai_task",
        "geo_location", "configurator", "persistent_notification", "conversation", "stt", "tts", "wake_word", "assist_satellite")
    val HIDDEN_PLATFORMS = setOf("backup", "mobile_app")
    private val ASSIST_SUFFIXES = listOf("_assist_pipeline", "_vad_sensitivity", "_finished_speaking_detection", "_wake_word")

    /** True for entities a person would never put on a widget (config/diagnostic, hidden, backup, Assist…). */
    fun isNoise(e: EntityState, entry: EntityEntry?): Boolean {
        if (e.domain in HIDDEN_DOMAINS) return true
        if (entry != null) {
            if (entry.entityCategory != null || entry.hidden) return true
            if (entry.platform != null && entry.platform in HIDDEN_PLATFORMS) return true
        }
        if (ASSIST_SUFFIXES.any { e.id.endsWith(it) }) return true
        return false
    }

    private val CLIMATE_COVERS = setOf("blind", "shade", "window", "shutter", "curtain", "awning")
    private val SECURITY_COVERS = setOf("door", "garage", "gate")
    private val SECURITY_BINARY = setOf("door", "garage_door", "window", "opening", "smoke", "moisture", "gas", "carbon_monoxide", "safety", "tamper", "lock")
    private val ENERGY_CLASSES = setOf("power", "energy", "current", "voltage", "battery")

    fun category(e: EntityState): Bucket = when (e.domain) {
        "light" -> Bucket.LIGHTS
        "climate", "fan", "humidifier", "water_heater" -> Bucket.CLIMATE
        "camera", "lock", "alarm_control_panel" -> Bucket.SECURITY
        "cover" -> when (e.deviceClass) { in SECURITY_COVERS -> Bucket.SECURITY; else -> Bucket.CLIMATE }
        "binary_sensor" -> when (e.deviceClass) { in SECURITY_BINARY -> Bucket.SECURITY; else -> Bucket.SENSORS }
        "media_player", "remote" -> Bucket.MEDIA
        "switch", "input_boolean" -> Bucket.SWITCHES
        "person" -> Bucket.PEOPLE
        "sensor" -> if (e.deviceClass in ENERGY_CLASSES && e.deviceClass != "battery") Bucket.ENERGY else Bucket.SENSORS
        else -> Bucket.OTHER
    }

    fun isSecurity(e: EntityState) = category(e) == Bucket.SECURITY
    /** The window binary_sensor counts for climate too (open window = heating wasted). */
    fun isClimate(e: EntityState) = category(e) == Bucket.CLIMATE || (e.domain == "binary_sensor" && e.deviceClass == "window") ||
        (e.domain == "cover" && e.deviceClass in CLIMATE_COVERS)

    fun isToggle(e: EntityState) = e.domain in TOGGLE_DOMAINS

    // ------------------------------------------------------------ state

    /** "On-ish": the tile is drawn in colour. */
    fun isActive(e: EntityState): Boolean = when (e.domain) {
        "light", "switch", "fan", "input_boolean", "siren", "humidifier", "automation" -> e.state == "on"
        "cover", "valve" -> e.state == "open" || e.state == "opening" || e.state == "closing"
        "lock" -> e.state == "unlocked" || e.state == "unlocking" || e.state == "open" || e.state == "opening" || e.state == "jammed"
        "media_player" -> e.state == "playing" || e.state == "paused" || e.state == "on" || e.state == "buffering"
        "climate" -> e.state != "off" && e.available
        "water_heater" -> e.state != "off" && e.available
        "vacuum", "lawn_mower" -> e.state == "cleaning" || e.state == "returning" || e.state == "mowing"
        "binary_sensor" -> e.state == "on"
        "alarm_control_panel" -> e.state.startsWith("armed") || e.state == "triggered" || e.state == "arming" || e.state == "pending"
        "person", "device_tracker" -> e.state == "home"
        "timer" -> e.state == "active"
        "camera" -> e.state == "recording" || e.state == "streaming"
        else -> e.state == "on"
    }

    /** Attention colour for security tiles: an open door, an unlocked lock, smoke. */
    fun isAlert(e: EntityState): Boolean = when {
        e.domain == "binary_sensor" && e.deviceClass in SECURITY_BINARY -> e.state == "on"
        e.domain == "lock" -> e.state != "locked" && e.available
        e.domain == "cover" && e.deviceClass in SECURITY_COVERS -> e.state != "closed" && e.available
        e.domain == "alarm_control_panel" -> e.state == "triggered"
        else -> false
    }

    /** What a tap on a tile does. Null when the entity has no sensible one-tap action. */
    fun tapAction(e: EntityState): ServiceCall? {
        val t = Json.obj("entity_id" to e.id)
        fun call(d: String, s: String, opt: String?, confirm: Boolean = false) = ServiceCall(d, s, t, opt, confirm)
        return when (e.domain) {
            "light", "switch", "fan", "input_boolean", "siren", "humidifier", "automation" -> call(e.domain, "toggle", if (e.isOn) "off" else "on")
            "cover" -> {
                val sensitive = e.deviceClass in SECURITY_COVERS
                if (e.state == "open" || e.state == "opening") call("cover", "close_cover", "closing", confirm = false)
                else call("cover", "open_cover", "opening", confirm = sensitive)
            }
            "valve" -> if (e.state == "open") call("valve", "close_valve", "closing") else call("valve", "open_valve", "opening")
            "lock" -> if (e.state == "locked") call("lock", "unlock", "unlocking", confirm = true) else call("lock", "lock", "locking")
            "scene" -> call("scene", "turn_on", null)
            "script" -> call("script", "turn_on", null)
            "button" -> call("button", "press", null)
            "input_button" -> call("input_button", "press", null)
            "media_player" -> if (e.state == "playing" || e.state == "paused") call("media_player", "media_play_pause", if (e.state == "playing") "paused" else "playing")
                else call("media_player", "toggle", if (e.state == "off" || e.state == "standby") "on" else "off")
            "climate" -> call("climate", "toggle", null)
            "water_heater" -> call("water_heater", if (e.state == "off") "turn_on" else "turn_off", null)
            "vacuum" -> if (e.state == "cleaning") call("vacuum", "return_to_base", "returning") else call("vacuum", "start", "cleaning")
            "lawn_mower" -> if (e.state == "mowing") call("lawn_mower", "dock", "docked") else call("lawn_mower", "start_mowing", "mowing")
            "input_select", "select" -> call(e.domain, "select_next", null)
            else -> null
        }
    }

    fun accent(e: EntityState): Accent = when (e.domain) {
        "light" -> Accent.ORANGE
        "switch", "input_boolean", "valve" -> Accent.CYAN
        "fan", "scene", "humidifier" -> Accent.PURPLE
        "cover", "script", "vacuum", "lawn_mower", "button", "input_button" -> Accent.LIME
        "lock", "alarm_control_panel", "media_player", "camera", "siren" -> Accent.PINK
        "climate", "water_heater" -> when (e.state) { "cool" -> Accent.CYAN; "dry", "fan_only" -> Accent.PURPLE; else -> Accent.ORANGE }
        "person", "device_tracker" -> Accent.CYAN
        "binary_sensor" -> if (isAlert(e)) Accent.PINK else Accent.CYAN
        "sensor" -> when (e.deviceClass) { "temperature" -> Accent.ORANGE; "humidity", "moisture" -> Accent.CYAN; "power", "energy" -> Accent.LIME; "battery" -> Accent.LIME; else -> Accent.CYAN }
        else -> Accent.CYAN
    }

    // ------------------------------------------------------------ labels

    /** The short state line: "on · 60%", "21.5 °C", "open · 40%", "Locked", "Home". */
    fun stateLabel(e: EntityState, nowMillis: Long = 0L): String {
        if (e.state == "unavailable") return "unavailable"
        if (e.state == "unknown") return "unknown"
        return when (e.domain) {
            "light" -> if (e.isOn) listOfNotNull("on", e.brightnessPct?.let { "$it%" }).joinToString(" · ") else "off"
            "fan" -> if (e.isOn) listOfNotNull("on", e.attrDouble("percentage")?.let { "${it.roundToInt()}%" }).joinToString(" · ") else "off"
            "cover" -> listOfNotNull(pretty(e.state), e.attrDouble("current_position")?.takeIf { e.state == "open" && it in 1.0..99.0 }?.let { "${it.roundToInt()}%" }).joinToString(" · ")
            "climate" -> {
                val cur = e.attrDouble("current_temperature")
                val target = e.attrDouble("temperature")
                val temps = listOfNotNull(cur?.let { "${fmt(it)}°" }, target?.takeIf { e.state != "off" }?.let { "${fmt(it)}°" }).joinToString(" → ")
                listOf(pretty(e.attrString("hvac_action")?.takeIf { it != "off" && it != "idle" } ?: e.state), temps).filter { it.isNotEmpty() }.joinToString(" · ")
            }
            "sensor", "number", "input_number" -> {
                val n = e.number
                if (n != null) (fmt(n) + (if (e.unit.isNotEmpty()) (if (e.unit == "%" || e.unit.startsWith("°")) e.unit else " ${e.unit}") else ""))
                else if (e.deviceClass == "timestamp") Time.parseMillis(e.state)?.let { if (nowMillis > 0) relative(it, nowMillis) else null } ?: e.state
                else e.state
            }
            "binary_sensor" -> binaryLabel(e)
            "person", "device_tracker" -> when (e.state) { "home" -> "Home"; "not_home" -> "Away"; else -> pretty(e.state) }
            "media_player" -> when (e.state) {
                "playing", "paused" -> listOfNotNull(e.state, e.attrString("media_title")).joinToString(" · ")
                else -> e.state.replace('_', ' ')
            }
            "alarm_control_panel" -> pretty(e.state)
            "lock" -> pretty(e.state)
            "vacuum" -> listOfNotNull(pretty(e.state), e.attrDouble("battery_level")?.let { "${it.roundToInt()}%" }).joinToString(" · ")
            "timer" -> if (e.state == "active" && nowMillis > 0) Time.parseMillis(e.attrString("finishes_at"))?.let { "${Time.duration(it - nowMillis)} left" } ?: "active" else e.state
            "weather" -> listOfNotNull(Weather.label(e.state), e.attrDouble("temperature")?.let { "${fmt(it)}°" }).joinToString(" · ")
            "scene", "script", "button", "input_button" -> if (e.domain == "script" && e.isOn) "running" else "tap to run"
            "update" -> if (e.isOn) "update available" else "up to date"
            else -> e.state.replace('_', ' ')
        }
    }

    private fun binaryLabel(e: EntityState): String {
        val on = e.isOn
        return when (e.deviceClass) {
            "door", "garage_door", "window", "opening" -> if (on) "open" else "closed"
            "motion", "occupancy", "presence" -> if (on) "detected" else "clear"
            "moisture" -> if (on) "wet" else "dry"
            "smoke", "gas", "carbon_monoxide" -> if (on) "detected" else "clear"
            "battery" -> if (on) "low" else "normal"
            "battery_charging" -> if (on) "charging" else "not charging"
            "connectivity" -> if (on) "connected" else "disconnected"
            "plug" -> if (on) "plugged in" else "unplugged"
            "lock" -> if (on) "unlocked" else "locked"
            "problem" -> if (on) "problem" else "OK"
            "safety" -> if (on) "unsafe" else "safe"
            "tamper" -> if (on) "tampered" else "clear"
            "running" -> if (on) "running" else "idle"
            "update" -> if (on) "update available" else "up to date"
            "vibration", "sound" -> if (on) "detected" else "clear"
            else -> if (on) "on" else "off"
        }
    }

    /** "Kitchen ceiling · on · 60%" */
    fun pickerLine(e: EntityState, nowMillis: Long = 0L): String = "${e.name} · ${stateLabel(e, nowMillis)}"

    fun relative(atMillis: Long, nowMillis: Long): String {
        val d = atMillis - nowMillis
        return if (d >= 0) "in ${Time.duration(d)}" else Time.ago(atMillis, nowMillis)
    }

    fun pretty(s: String): String = s.replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** 21.456 -> "21.5", 1234.4 -> "1234", 3.0 -> "3". */
    fun fmt(v: Double): String {
        val a = abs(v)
        val r = when {
            a >= 100 -> v.roundToInt().toDouble()
            a >= 10 -> (v * 10).roundToInt() / 10.0
            else -> (v * 100).roundToInt() / 100.0
        }
        val s = if (r == r.toLong().toDouble()) r.toLong().toString() else r.toString()
        return if (a in 10.0..100.0 && s.contains('.')) s.trimEnd('0').trimEnd('.') else s
    }

    // ------------------------------------------------------------ icons

    /**
     * Logical icon key for an entity: Material Design Icons names without the "mdi:" prefix.
     * Android maps them to vector drawables, iOS to SF Symbols. The entity's own mdi icon wins when the
     * platform has it.
     */
    fun icon(e: EntityState): String {
        val on = isActive(e)
        return when (e.domain) {
            "light" -> if (on) "lightbulb-on" else "lightbulb"
            "switch" -> when (e.deviceClass) { "outlet" -> "power-socket-eu"; else -> if (on) "toggle-switch" else "toggle-switch-off" }
            "input_boolean" -> if (on) "toggle-switch" else "toggle-switch-off"
            "fan" -> if (on) "fan" else "fan-off"
            "cover" -> when (e.deviceClass) {
                "garage" -> if (on) "garage-open" else "garage"
                "door", "gate" -> if (on) "door-open" else "door-closed"
                "window" -> if (on) "window-open" else "window-closed"
                "curtain" -> if (on) "curtains" else "curtains-closed"
                else -> if (on) "blinds-open" else "blinds"
            }
            "lock" -> if (e.state == "locked") "lock" else "lock-open-variant"
            "climate" -> "thermostat"
            "water_heater" -> "water-boiler"
            "humidifier" -> "air-humidifier"
            "media_player" -> when (e.deviceClass) { "tv" -> "television"; "speaker" -> "speaker"; else -> if (e.state == "playing") "play-circle" else "cast" }
            "camera" -> "cctv"
            "alarm_control_panel" -> if (e.state == "disarmed") "shield-off" else "shield-home"
            "vacuum" -> "robot-vacuum"
            "lawn_mower" -> "robot-mower"
            "person" -> "account"
            "device_tracker" -> "cellphone"
            "scene" -> "palette"
            "script" -> "script-text-play"
            "button", "input_button" -> "gesture-tap-button"
            "weather" -> Weather.icon(e.state, true)
            "sun" -> if (e.state == "above_horizon") "weather-sunny" else "weather-night"
            "timer" -> "timer-outline"
            "calendar" -> "calendar"
            "todo" -> "clipboard-list"
            "valve" -> "valve"
            "siren" -> "alarm-light"
            "update" -> "package-up"
            "binary_sensor" -> when (e.deviceClass) {
                "door" -> if (on) "door-open" else "door-closed"
                "garage_door" -> if (on) "garage-open" else "garage"
                "window" -> if (on) "window-open" else "window-closed"
                "opening" -> if (on) "square-outline" else "square"
                "motion", "occupancy", "presence" -> if (on) "motion-sensor" else "motion-sensor-off"
                "moisture" -> if (on) "water-alert" else "water-off"
                "smoke" -> if (on) "smoke-detector-alert" else "smoke-detector"
                "gas", "carbon_monoxide" -> if (on) "alert" else "check-circle-outline"
                "connectivity" -> if (on) "wifi" else "wifi-off"
                "plug", "power" -> if (on) "power-plug" else "power-plug-off"
                "battery" -> if (on) "battery-alert" else "battery"
                "lock" -> if (on) "lock-open-variant" else "lock"
                else -> if (on) "checkbox-marked-circle" else "checkbox-blank-circle-outline"
            }
            "sensor" -> when (e.deviceClass) {
                "temperature" -> "thermometer"
                "humidity" -> "water-percent"
                "power", "current", "voltage" -> "flash"
                "energy" -> "lightning-bolt"
                "battery" -> batteryIcon(e.number)
                "illuminance" -> "brightness-5"
                "pressure" -> "gauge"
                "carbon_dioxide", "volatile_organic_compounds", "pm25", "pm10" -> "molecule-co2"
                "timestamp", "date" -> "clock-outline"
                "monetary" -> "cash"
                else -> "eye"
            }
            else -> "circle-medium"
        }
    }

    fun batteryIcon(level: Double?): String = when {
        level == null -> "battery"
        level < 15 -> "battery-alert"
        level < 40 -> "battery-30"
        level < 70 -> "battery-60"
        level < 95 -> "battery-80"
        else -> "battery"
    }
}

/** Weather condition names, the same table as the dashboard's weather card. */
object Weather {
    fun label(c: String): String = when (c) {
        "sunny" -> "Sunny"; "clear-night" -> "Clear"; "partlycloudy" -> "Partly cloudy"; "cloudy" -> "Cloudy"; "rainy" -> "Rain"
        "pouring" -> "Heavy rain"; "lightning", "lightning-rainy" -> "Storm"; "snowy" -> "Snow"; "snowy-rainy" -> "Sleet"; "fog" -> "Fog"
        "hail" -> "Hail"; "windy", "windy-variant" -> "Windy"; "exceptional" -> "Exceptional"
        else -> c.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    fun icon(c: String, day: Boolean): String = when (c) {
        "sunny" -> if (day) "weather-sunny" else "weather-night"
        "clear-night" -> "weather-night"
        "partlycloudy" -> if (day) "weather-partly-cloudy" else "weather-night-partly-cloudy"
        "cloudy" -> "weather-cloudy"; "rainy" -> "weather-rainy"; "pouring" -> "weather-pouring"
        "lightning" -> "weather-lightning"; "lightning-rainy" -> "weather-lightning-rainy"
        "snowy" -> "weather-snowy"; "snowy-rainy" -> "weather-snowy-rainy"; "fog" -> "weather-fog"; "hail" -> "weather-hail"
        "windy" -> "weather-windy"; "windy-variant" -> "weather-windy-variant"
        else -> "weather-partly-cloudy"
    }

    /** One forecast entry of `weather.get_forecasts`. */
    class Forecast(val atMillis: Long, val condition: String, val temperature: Double?, val templow: Double?, val precipitation: Double?, val isDaytime: Boolean?)

    /** The forecasts the apps attached to a weather entity (daily or hourly). */
    fun forecasts(e: EntityState, daily: Boolean): List<Forecast> =
        parseForecasts(Json.obj("forecast" to (e.attr(if (daily) Extra.DAILY else Extra.HOURLY) ?: Json.Arr(emptyList()))), "")

    /** `weather.get_forecasts` service response: {"weather.home": {"forecast": [...]}}. */
    fun parseForecasts(response: Json?, entityId: String): List<Forecast> {
        val list = response?.get(entityId)?.arr("forecast") ?: response?.arr("forecast") ?: emptyList()
        return list.mapNotNull { f ->
            val at = Time.parseMillis(f.str("datetime")) ?: return@mapNotNull null
            Forecast(at, f.str("condition") ?: "", f.num("temperature"), f.num("templow"), f.num("precipitation"), f.flag("is_daytime"))
        }
    }
}
