package in_.weenja.hawidgets.core

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Attribute names the apps use to carry fetched extras (forecasts, history, events) inside a state. */
object Extra {
    const val DAILY = "hb_daily"
    const val HOURLY = "hb_hourly"
    const val HISTORY = "hb_history"
    const val EVENTS = "hb_events"
    const val ITEMS = "items"
    /** Synthetic entity that carries today's energy totals and the energy setup. */
    const val ENERGY_ID = "homebase.energy"
}

/**
 * A made-up home with every kind of device the widgets know. Used for previews before login, for the
 * store screenshots and as the App Review demo mode. No real entity ids, names or addresses.
 */
object DemoHome {
    private fun iso(ms: Long, offsetMin: Int): String {
        val local = ms + offsetMin * 60_000L
        val days = floorDiv(local, 86_400_000L)
        val msOfDay = local - days * 86_400_000L
        val (y, m, d) = civil(days)
        val h = msOfDay / 3_600_000; val mi = msOfDay % 3_600_000 / 60_000; val s = msOfDay % 60_000 / 1000
        val sign = if (offsetMin < 0) "-" else "+"
        val oh = kotlin.math.abs(offsetMin) / 60; val om = kotlin.math.abs(offsetMin) % 60
        return "${y.toString().padStart(4, '0')}-${p2(m)}-${p2(d)}T${p2(h.toInt())}:${p2(mi.toInt())}:${p2(s.toInt())}$sign${p2(oh)}:${p2(om)}"
    }

    private fun p2(n: Int) = n.toString().padStart(2, '0')
    private fun floorDiv(a: Long, b: Long): Long { val q = a / b; return if ((a % b != 0L) && ((a < 0) != (b < 0))) q - 1 else q }

    /** Days since epoch -> (year, month, day). */
    private fun civil(z0: Long): Triple<Int, Int, Int> {
        val z = z0 + 719468
        val era = (if (z >= 0) z else z - 146096) / 146097
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val y = yoe + era * 400
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        return Triple((if (m <= 2) y + 1 else y).toInt(), m.toInt(), d.toInt())
    }

    private class B(val now: Long, val off: Int) {
        val list = ArrayList<Json>()
        fun e(id: String, state: String, vararg attrs: Pair<String, Any?>) {
            list.add(Json.obj("entity_id" to id, "state" to state, "attributes" to Json.obj(*attrs), "last_changed" to iso(now - 3_600_000, off), "last_updated" to iso(now - 60_000, off)))
        }
        fun at(deltaMin: Int) = iso(now + deltaMin * 60_000L, off)
    }

    /** Demo states at [nowMillis] in a timezone [offsetMinutes] east of UTC. */
    fun states(nowMillis: Long, offsetMinutes: Int): List<EntityState> {
        val b = B(nowMillis, offsetMinutes)
        val localMin = ((nowMillis / 60_000 + offsetMinutes) % 1440 + 1440) % 1440
        val day = localMin in (6 * 60 + 40)..(19 * 60 + 5)
        with(b) {
            // living room
            e("light.living_room_ceiling", "on", "friendly_name" to "Living room ceiling", "brightness" to 178, "color_mode" to "color_temp", "color_temp_kelvin" to 3000,
                "supported_color_modes" to listOf("color_temp"), "min_color_temp_kelvin" to 2200, "max_color_temp_kelvin" to 6500)
            e("light.floor_lamp", "off", "friendly_name" to "Floor lamp", "supported_color_modes" to listOf("hs", "color_temp"))
            e("media_player.living_room_tv", "playing", "friendly_name" to "Living room TV", "device_class" to "tv", "app_name" to "Netflix", "media_title" to "The Great Bake Off",
                "media_series_title" to "Season 3", "volume_level" to 0.3, "media_duration" to 3120, "media_position" to 1210, "media_position_updated_at" to at(-2))
            e("remote.living_room_tv", "on", "friendly_name" to "Living room TV remote")
            e("climate.living_room", "heat", "friendly_name" to "Living room thermostat", "current_temperature" to 20.5, "temperature" to 21.5, "hvac_action" to "heating",
                "hvac_modes" to listOf("off", "heat", "auto"), "min_temp" to 7, "max_temp" to 30, "target_temp_step" to 0.5)
            e("sensor.living_room_temperature", "20.5", "friendly_name" to "Living room temperature", "device_class" to "temperature", "unit_of_measurement" to "°C", "state_class" to "measurement",
                Extra.HISTORY to series("sensor.living_room_temperature", nowMillis, offsetMinutes, 20.5, 2.5, "°C").toJson())
            e("sensor.living_room_humidity", "48", "friendly_name" to "Living room humidity", "device_class" to "humidity", "unit_of_measurement" to "%", "state_class" to "measurement")
            e("cover.living_room_blinds", "open", "friendly_name" to "Living room blinds", "device_class" to "blind", "current_position" to 60)
            // kitchen
            e("light.kitchen_ceiling", "on", "friendly_name" to "Kitchen ceiling", "brightness" to 153, "supported_color_modes" to listOf("brightness"))
            e("light.kitchen_counter", "off", "friendly_name" to "Kitchen counter", "supported_color_modes" to listOf("brightness"))
            e("switch.coffee_machine", "on", "friendly_name" to "Coffee machine", "device_class" to "outlet")
            e("sensor.kitchen_temperature", "21.8", "friendly_name" to "Kitchen temperature", "device_class" to "temperature", "unit_of_measurement" to "°C", "state_class" to "measurement",
                Extra.HISTORY to series("sensor.kitchen_temperature", nowMillis, offsetMinutes, 21.8, 2.5, "°C").toJson())
            e("sensor.kitchen_humidity", "52", "friendly_name" to "Kitchen humidity", "device_class" to "humidity", "unit_of_measurement" to "%", "state_class" to "measurement")
            e("binary_sensor.fridge_door", "off", "friendly_name" to "Fridge door", "device_class" to "door")
            e("sensor.dishwasher_end_time", at(42), "friendly_name" to "Dishwasher end time", "device_class" to "timestamp")
            // hallway
            e("lock.front_door", "locked", "friendly_name" to "Front door lock")
            e("binary_sensor.front_door", "off", "friendly_name" to "Front door", "device_class" to "door")
            e("light.hallway", "off", "friendly_name" to "Hallway", "supported_color_modes" to listOf("brightness"))
            e("camera.doorbell", "idle", "friendly_name" to "Doorbell", "entity_picture" to "/api/camera_proxy/camera.doorbell?token=demo")
            e("alarm_control_panel.home_alarm", "armed_home", "friendly_name" to "Home alarm", "code_arm_required" to false)
            e("binary_sensor.hallway_motion", "on", "friendly_name" to "Hallway motion", "device_class" to "motion")
            e("binary_sensor.hallway_smoke", "off", "friendly_name" to "Hallway smoke", "device_class" to "smoke")
            // garage
            e("cover.garage_door", "closed", "friendly_name" to "Garage door", "device_class" to "garage")
            e("light.garage", "off", "friendly_name" to "Garage light", "supported_color_modes" to listOf("onoff"))
            e("sensor.car_battery", "74", "friendly_name" to "Car battery", "device_class" to "battery", "unit_of_measurement" to "%", "state_class" to "measurement")
            e("binary_sensor.car_charging", "on", "friendly_name" to "Car charging", "device_class" to "battery_charging")
            e("sensor.car_range", "312", "friendly_name" to "Car range", "device_class" to "distance", "unit_of_measurement" to "km")
            e("sensor.car_charger_power", "7.2", "friendly_name" to "Car charger power", "device_class" to "power", "unit_of_measurement" to "kW", "state_class" to "measurement")
            // bedroom
            e("light.bedroom", "off", "friendly_name" to "Bedroom", "supported_color_modes" to listOf("color_temp"))
            e("fan.bedroom_fan", "on", "friendly_name" to "Bedroom fan", "percentage" to 33, "percentage_step" to 33.33)
            e("cover.bedroom_shades", "closed", "friendly_name" to "Bedroom shades", "device_class" to "shade", "current_position" to 0)
            e("sensor.bedroom_temperature", "19.4", "friendly_name" to "Bedroom temperature", "device_class" to "temperature", "unit_of_measurement" to "°C", "state_class" to "measurement",
                Extra.HISTORY to series("sensor.bedroom_temperature", nowMillis, offsetMinutes, 19.4, 2.5, "°C").toJson())
            e("sensor.bedroom_humidity", "55", "friendly_name" to "Bedroom humidity", "device_class" to "humidity", "unit_of_measurement" to "%", "state_class" to "measurement")
            e("binary_sensor.bedroom_window", "on", "friendly_name" to "Bedroom window", "device_class" to "window")
            // office
            e("light.office_desk", "on", "friendly_name" to "Desk lamp", "brightness" to 255, "supported_color_modes" to listOf("hs", "color_temp"), "color_mode" to "color_temp", "color_temp_kelvin" to 4000)
            e("switch.office_monitor", "on", "friendly_name" to "Monitor plug", "device_class" to "outlet")
            e("sensor.office_temperature", "22.3", "friendly_name" to "Office temperature", "device_class" to "temperature", "unit_of_measurement" to "°C", "state_class" to "measurement",
                Extra.HISTORY to series("sensor.office_temperature", nowMillis, offsetMinutes, 22.3, 2.5, "°C").toJson())
            e("sensor.office_co2", "820", "friendly_name" to "Office CO₂", "device_class" to "carbon_dioxide", "unit_of_measurement" to "ppm", "state_class" to "measurement")
            e("media_player.office_speaker", "paused", "friendly_name" to "Office speaker", "device_class" to "speaker", "media_title" to "Weightless", "media_artist" to "Marconi Union",
                "volume_level" to 0.25, "media_duration" to 480, "media_position" to 95, "media_position_updated_at" to at(-10))
            // bathroom
            e("light.bathroom", "off", "friendly_name" to "Bathroom", "supported_color_modes" to listOf("brightness"))
            e("fan.bathroom_extractor", "off", "friendly_name" to "Bathroom extractor")
            e("sensor.bathroom_humidity", "67", "friendly_name" to "Bathroom humidity", "device_class" to "humidity", "unit_of_measurement" to "%", "state_class" to "measurement")
            e("binary_sensor.bathroom_leak", "off", "friendly_name" to "Bathroom leak", "device_class" to "moisture")
            // garden
            e("switch.garden_irrigation", "off", "friendly_name" to "Garden irrigation")
            e("light.garden", "off", "friendly_name" to "Garden lights", "supported_color_modes" to listOf("onoff"))
            e("sensor.outdoor_temperature", "14.2", "friendly_name" to "Outdoor temperature", "device_class" to "temperature", "unit_of_measurement" to "°C", "state_class" to "measurement",
                Extra.HISTORY to series("sensor.outdoor_temperature", nowMillis, offsetMinutes, 14.2, 5.5, "°C").toJson())
            e("light.porch", "unavailable", "friendly_name" to "Porch light")
            // energy
            e("sensor.grid_power", "820", "friendly_name" to "Grid power", "device_class" to "power", "unit_of_measurement" to "W", "state_class" to "measurement")
            e("sensor.solar_power", if (day) "1640" else "0", "friendly_name" to "Solar power", "device_class" to "power", "unit_of_measurement" to "W", "state_class" to "measurement")
            e("sensor.home_battery_power", if (day) "-450" else "380", "friendly_name" to "Home battery power", "device_class" to "power", "unit_of_measurement" to "W", "state_class" to "measurement")
            e("sensor.home_battery_level", "64", "friendly_name" to "Home battery", "device_class" to "battery", "unit_of_measurement" to "%", "state_class" to "measurement")
            e("sensor.grid_energy_import", "4821.4", "friendly_name" to "Grid import", "device_class" to "energy", "unit_of_measurement" to "kWh", "state_class" to "total_increasing")
            e("sensor.grid_energy_export", "1210.9", "friendly_name" to "Grid export", "device_class" to "energy", "unit_of_measurement" to "kWh", "state_class" to "total_increasing")
            e("sensor.solar_energy", "6630.2", "friendly_name" to "Solar production", "device_class" to "energy", "unit_of_measurement" to "kWh", "state_class" to "total_increasing")
            e("sensor.battery_energy_in", "912.4", "friendly_name" to "Battery charged", "device_class" to "energy", "unit_of_measurement" to "kWh", "state_class" to "total_increasing")
            e("sensor.battery_energy_out", "860.7", "friendly_name" to "Battery discharged", "device_class" to "energy", "unit_of_measurement" to "kWh", "state_class" to "total_increasing")
            e(Extra.ENERGY_ID, "ok", "friendly_name" to "Energy today", "grid_in" to 6.4, "grid_out" to 3.1, "solar" to 12.8, "battery_in" to 4.2, "battery_out" to 3.5)
            // everything else
            val dailyConds = listOf("partlycloudy", "rainy", "sunny", "cloudy", "sunny", "lightning-rainy", "partlycloudy")
            e("weather.home", if (day) "partlycloudy" else "clear-night", "friendly_name" to "Home", "temperature" to 16.4, "humidity" to 61, "wind_speed" to 12.4,
                "wind_speed_unit" to "km/h", "temperature_unit" to "°C", "pressure" to 1016,
                Extra.DAILY to (0 until 7).map { i -> Json.obj("datetime" to iso(nowMillis + i * 86_400_000L, offsetMinutes).substring(0, 10) + "T00:00:00" + iso(nowMillis, offsetMinutes).substring(19),
                    "condition" to dailyConds[i], "temperature" to 18 + (i * 7 % 5), "templow" to 9 + (i * 3 % 4), "precipitation" to if (dailyConds[i].contains("rain")) 3.4 else 0.0) },
                Extra.HOURLY to (0 until 24).map { h ->
                    val t = (nowMillis / 3_600_000 + h) * 3_600_000
                    val lm = ((t / 60_000 + offsetMinutes) % 1440 + 1440) % 1440
                    Json.obj("datetime" to iso(t, offsetMinutes), "condition" to listOf("partlycloudy", "sunny", "cloudy", "rainy", "partlycloudy", "sunny")[h % 6],
                        "temperature" to (13 + 5 * sin((lm / 1440.0 - .3) * 2 * PI)).roundToInt(), "is_daytime" to (lm in 400..1145))
                })
            val riseMin = 6 * 60 + 40; val setMin = 19 * 60 + 5
            fun next(minOfDay: Int): String { val delta = (minOfDay - localMin + 1440) % 1440; return iso(nowMillis + delta * 60_000L, offsetMinutes) }
            e("sun.sun", if (day) "above_horizon" else "below_horizon", "friendly_name" to "Sun", "next_rising" to next(riseMin), "next_setting" to next(setMin), "elevation" to if (day) 31.5 else -18.0)
            e("person.alex", "home", "friendly_name" to "Alex")
            e("person.sam", "Work", "friendly_name" to "Sam")
            e("person.robin", "not_home", "friendly_name" to "Robin")
            e("zone.home", "2", "friendly_name" to "Home", "latitude" to 52.37, "longitude" to 4.89, "radius" to 100)
            e("todo.shopping_list", "3", "friendly_name" to "Shopping list", Extra.ITEMS to listOf(
                Json.obj("uid" to "1", "summary" to "Oat milk", "status" to "needs_action"), Json.obj("uid" to "2", "summary" to "Coffee beans", "status" to "needs_action"),
                Json.obj("uid" to "3", "summary" to "Batteries (AA)", "status" to "needs_action"), Json.obj("uid" to "4", "summary" to "Bread", "status" to "completed")))
            e("calendar.family", "off", "friendly_name" to "Family", Extra.EVENTS to listOf(
                Json.obj("summary" to "Dentist", "start" to at(95), "end" to at(125)),
                Json.obj("summary" to "Football practice", "start" to at(60 * 26), "end" to at(60 * 27 + 30)),
                Json.obj("summary" to "Dinner with Jo & Kim", "start" to at(60 * 50), "end" to at(60 * 53)),
                Json.obj("summary" to "School holiday", "start" to iso(nowMillis + 4 * 86_400_000L, offsetMinutes).substring(0, 10), "end" to iso(nowMillis + 5 * 86_400_000L, offsetMinutes).substring(0, 10), "all_day" to true)))
            e("vacuum.robot", "docked", "friendly_name" to "Robot vacuum", "battery_level" to 100, "fan_speed" to "standard")
            e("timer.pasta", "active", "friendly_name" to "Pasta timer", "duration" to "0:10:00", "finishes_at" to at(7), "remaining" to "0:07:00")
            e("sensor.recycling_collection", at(60 * 40).substring(0, 10), "friendly_name" to "Recycling collection", "device_class" to "date")
            e("sensor.general_waste_collection", at(60 * 24 * 5).substring(0, 10), "friendly_name" to "General waste collection", "device_class" to "date")
            e("sensor.phone_battery_level", "18", "friendly_name" to "Phone battery", "device_class" to "battery", "unit_of_measurement" to "%")
            e("sensor.front_door_sensor_battery", "9", "friendly_name" to "Front door sensor battery", "device_class" to "battery", "unit_of_measurement" to "%")
            e("sensor.hallway_motion_battery", "35", "friendly_name" to "Hallway motion battery", "device_class" to "battery", "unit_of_measurement" to "%")
            e("sensor.bedroom_window_battery", "88", "friendly_name" to "Bedroom window battery", "device_class" to "battery", "unit_of_measurement" to "%")
            e("scene.movie_night", "2026-09-20T20:00:00+00:00", "friendly_name" to "Movie night")
            e("scene.good_morning", "2026-09-27T07:00:00+00:00", "friendly_name" to "Good morning")
            e("script.good_night", "off", "friendly_name" to "Good night")
            e("script.leaving_home", "off", "friendly_name" to "Leaving home")
            e("update.home_assistant_core_update", "off", "friendly_name" to "Home Assistant Core update")
        }
        return EntityState.parseList(Json.Arr(b.list))
    }

    /** A 24 h series with a daily swing around [mean]. */
    fun series(id: String, nowMillis: Long, offsetMinutes: Int, last: Double, swing: Double, unit: String): Series {
        val pts = (0..96).map { i ->
            val t = nowMillis - (96 - i) * 15 * 60_000L
            val lm = ((t / 60_000 + offsetMinutes) % 1440 + 1440) % 1440
            val v = last - swing * .5 + swing * (0.5 + 0.5 * sin((lm / 1440.0 - .375) * 2 * PI)) + 0.25 * sin(i * .23) * sin(i * .07)
            Series.Point(t, (v * 10).roundToInt() / 10.0)
        }
        return Series(id, pts, unit)
    }

    private val AREAS = listOf(
        Triple("living_room", "Living room", "ground"), Triple("kitchen", "Kitchen", "ground"), Triple("hallway", "Hallway", "ground"),
        Triple("garage", "Garage", "ground"), Triple("bedroom", "Bedroom", "first"), Triple("office", "Office", "first"),
        Triple("bathroom", "Bathroom", "first"), Triple("garden", "Garden", null),
    )

    /** entity id -> (area, device) */
    private val PLACES: Map<String, Pair<String, String?>> = mapOf(
        "light.living_room_ceiling" to ("living_room" to "hue_ceiling"), "light.floor_lamp" to ("living_room" to "floor_lamp"),
        "media_player.living_room_tv" to ("living_room" to "tv"), "remote.living_room_tv" to ("living_room" to "tv"),
        "climate.living_room" to ("living_room" to "thermostat"), "sensor.living_room_temperature" to ("living_room" to "thermostat"),
        "sensor.living_room_humidity" to ("living_room" to "thermostat"), "cover.living_room_blinds" to ("living_room" to "blinds"),
        "light.kitchen_ceiling" to ("kitchen" to null), "light.kitchen_counter" to ("kitchen" to null), "switch.coffee_machine" to ("kitchen" to "coffee_plug"),
        "sensor.kitchen_temperature" to ("kitchen" to "kitchen_climate"), "sensor.kitchen_humidity" to ("kitchen" to "kitchen_climate"),
        "binary_sensor.fridge_door" to ("kitchen" to null), "sensor.dishwasher_end_time" to ("kitchen" to "dishwasher"),
        "lock.front_door" to ("hallway" to "front_lock"), "binary_sensor.front_door" to ("hallway" to "front_contact"), "sensor.front_door_sensor_battery" to ("hallway" to "front_contact"),
        "light.hallway" to ("hallway" to null), "camera.doorbell" to ("hallway" to "doorbell"), "alarm_control_panel.home_alarm" to ("hallway" to "alarm"),
        "binary_sensor.hallway_motion" to ("hallway" to "hall_motion"), "sensor.hallway_motion_battery" to ("hallway" to "hall_motion"), "binary_sensor.hallway_smoke" to ("hallway" to null),
        "cover.garage_door" to ("garage" to "garage_opener"), "light.garage" to ("garage" to null),
        "sensor.car_battery" to ("garage" to "car"), "binary_sensor.car_charging" to ("garage" to "car"), "sensor.car_range" to ("garage" to "car"), "sensor.car_charger_power" to ("garage" to "car"),
        "light.bedroom" to ("bedroom" to null), "fan.bedroom_fan" to ("bedroom" to null), "cover.bedroom_shades" to ("bedroom" to null),
        "sensor.bedroom_temperature" to ("bedroom" to "bedroom_climate"), "sensor.bedroom_humidity" to ("bedroom" to "bedroom_climate"),
        "binary_sensor.bedroom_window" to ("bedroom" to "bedroom_window"), "sensor.bedroom_window_battery" to ("bedroom" to "bedroom_window"),
        "light.office_desk" to ("office" to null), "switch.office_monitor" to ("office" to null), "sensor.office_temperature" to ("office" to "office_air"),
        "sensor.office_co2" to ("office" to "office_air"), "media_player.office_speaker" to ("office" to null),
        "light.bathroom" to ("bathroom" to null), "fan.bathroom_extractor" to ("bathroom" to null), "sensor.bathroom_humidity" to ("bathroom" to null), "binary_sensor.bathroom_leak" to ("bathroom" to null),
        "switch.garden_irrigation" to ("garden" to null), "light.garden" to ("garden" to null), "sensor.outdoor_temperature" to ("garden" to null), "light.porch" to ("garden" to "porch_light"),
        "vacuum.robot" to ("living_room" to null),
    )

    private val DEVICES = mapOf(
        "hue_ceiling" to ("Ceiling light" to "Signify"), "floor_lamp" to ("Floor lamp" to "IKEA"), "tv" to ("Living room TV" to "LG"), "thermostat" to ("Thermostat" to "Nest"),
        "blinds" to ("Blinds" to "IKEA"), "coffee_plug" to ("Coffee plug" to "Shelly"), "kitchen_climate" to ("Kitchen climate" to "Aqara"), "dishwasher" to ("Dishwasher" to "Bosch"),
        "front_lock" to ("Front door lock" to "Nuki"), "front_contact" to ("Front door contact" to "Aqara"), "doorbell" to ("Doorbell" to "Reolink"), "alarm" to ("Alarm" to "Ajax"),
        "hall_motion" to ("Hallway motion" to "Aqara"), "garage_opener" to ("Garage opener" to "Meross"), "car" to ("Car" to "Tesla"), "bedroom_climate" to ("Bedroom climate" to "Aqara"),
        "bedroom_window" to ("Bedroom window" to "Aqara"), "office_air" to ("Air monitor" to "Airthings"), "porch_light" to ("Porch light" to "Shelly"),
    )

    fun registry(): Registry {
        val floors = listOf(Floor("ground", "Ground floor", 0, "mdi:home-floor-0"), Floor("first", "First floor", 1, "mdi:home-floor-1"))
        val temps = mapOf("living_room" to "sensor.living_room_temperature", "kitchen" to "sensor.kitchen_temperature", "bedroom" to "sensor.bedroom_temperature", "office" to "sensor.office_temperature")
        val hums = mapOf("living_room" to "sensor.living_room_humidity", "kitchen" to "sensor.kitchen_humidity", "bedroom" to "sensor.bedroom_humidity", "bathroom" to "sensor.bathroom_humidity")
        val areas = AREAS.map { (id, name, floor) -> Area(id, name, floor, "", temps[id], hums[id], emptyList()) }
        val devices = DEVICES.map { (id, v) -> Device(id, v.first, PLACES.values.firstOrNull { it.second == id }?.first, v.second, "", false) }
        val entries = PLACES.map { (eid, place) -> EntityEntry(eid, null, if (place.second == null) place.first else null, place.second, null, false, null, emptyList()) } +
            listOf(EntityEntry("update.home_assistant_core_update", null, null, null, "config", false, "hassio", emptyList()))
        return Registry(areas, floors, devices, entries)
    }

    val FAVORITES = listOf("light.living_room_ceiling", "light.kitchen_ceiling", "switch.coffee_machine", "lock.front_door", "cover.garage_door",
        "climate.living_room", "scene.movie_night", "fan.bedroom_fan")
    val SUGGESTED = listOf("light.kitchen_ceiling", "switch.coffee_machine", "media_player.living_room_tv", "light.office_desk", "cover.living_room_blinds", "scene.good_morning")

    val ENERGY = EnergySetup(listOf("sensor.grid_energy_import"), listOf("sensor.grid_energy_export"), "sensor.grid_power", listOf("sensor.solar_energy"), "sensor.solar_power",
        listOf("sensor.battery_energy_in"), listOf("sensor.battery_energy_out"), "sensor.home_battery_power", "sensor.home_battery_level", emptyList(), emptyList(), emptyList())

    fun home(nowMillis: Long, offsetMinutes: Int): Home =
        Home(states(nowMillis, offsetMinutes).associateBy { it.id }, registry(), FAVORITES, SUGGESTED, ENERGY, "Demo home", nowMillis)
}
