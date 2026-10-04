package in_.weenja.hawidgets.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    private val now = 1_790_000_000_000L // 2026-09-21T14:13:20Z

    @Test fun jsonRoundTrip() {
        val text = """{"a":1,"b":[true,false,null,"x\"y\n"],"c":{"d":-2.5e3},"e":"é ✓ é"}"""
        val j = Json.parse(text)
        assertEquals(1.0, j.num("a"))
        assertEquals("x\"y\n", j["b"]!!.at(3)!!.string)
        assertEquals(-2500.0, j["c"]!!.num("d"))
        assertEquals("é ✓ é", j.str("e"))
        assertEquals(j.toString(), Json.parse(j.toString()).toString())
        assertNull(Json.parseOrNull("{broken"))
        assertEquals("3", Json.of(3).string)
        assertEquals("3.5", Json.of(3.5).string)
    }

    @Test fun isoTimes() {
        assertEquals(0L, Time.parseMillis("1970-01-01T00:00:00Z"))
        assertEquals(1_790_000_000_000L, Time.parseMillis("2026-09-21T14:13:20+00:00"))
        assertEquals(1_790_000_000_123L, Time.parseMillis("2026-09-21T16:13:20.123456+02:00"))
        assertEquals(Time.parseMillis("2026-09-21T00:00:00Z"), Time.parseMillis("2026-09-21"))
        assertNull(Time.parseMillis("not a date"))
        assertEquals("5 min ago", Time.ago(now - 5 * 60_000, now))
        assertEquals("just now", Time.ago(now - 10_000, now))
        assertEquals("14:13", Time.clock(now, 0))
        assertEquals("16:13", Time.clock(now, 120))
    }

    @Test fun registryForDisplay() {
        val entities = Json.parse("""{"entity_categories":{"0":"config","1":"diagnostic"},"entities":[
            {"ei":"light.a","di":"d1","en":"A"},{"ei":"sensor.b","ai":"kitchen","ec":1},{"ei":"switch.c","hb":true,"pl":"mobile_app"}]}""")
        val devices = Json.parse("""[{"id":"d1","name":"Lamp","area_id":"living","manufacturer":"IKEA","model":"X"}]""")
        val areas = Json.parse("""[{"area_id":"living","name":"Living","floor_id":"g","temperature_entity_id":"sensor.t"},{"area_id":"kitchen","name":"Kitchen"}]""")
        val floors = Json.parse("""[{"floor_id":"g","name":"Ground","level":0}]""")
        val reg = Registry.parse(areas, floors, devices, entities)
        assertEquals("Living", reg.areaOf("light.a")?.name)
        assertEquals("Ground", reg.floorOf("light.a")?.name)
        assertEquals("Kitchen", reg.areaOf("sensor.b")?.name)
        assertEquals("diagnostic", reg.entry("sensor.b")?.entityCategory)
        assertTrue(reg.entry("switch.c")!!.hidden)
        assertEquals("sensor.t", reg.area("living")?.temperatureEntityId)
        val back = Registry.fromJson(Json.parse(reg.toJson().toString()))
        assertEquals("Living", back.areaOf("light.a")?.name)
        assertEquals("diagnostic", back.entry("sensor.b")?.entityCategory)
    }

    @Test fun noiseAndLabels() {
        val home = DemoHome.home(now, 0)
        val upd = home["update.home_assistant_core_update"]!!
        assertTrue(Rules.isNoise(upd, home.registry.entry(upd.id)))
        assertTrue(Rules.isNoise(home["sun.sun"]!!, null))
        assertFalse(Rules.isNoise(home["light.kitchen_ceiling"]!!, home.registry.entry("light.kitchen_ceiling")))
        assertEquals("on · 60%", Rules.stateLabel(home["light.kitchen_ceiling"]!!))
        assertEquals("Kitchen ceiling · on · 60%", Rules.pickerLine(home["light.kitchen_ceiling"]!!))
        assertEquals("20.5°C", Rules.stateLabel(home["sensor.living_room_temperature"]!!))
        assertEquals("open", Rules.stateLabel(home["binary_sensor.bedroom_window"]!!))
        assertEquals("Locked", Rules.stateLabel(home["lock.front_door"]!!))
        assertEquals("Heating · 20.5° → 21.5°", Rules.stateLabel(home["climate.living_room"]!!))
        assertEquals(Bucket.SECURITY, Rules.category(home["cover.garage_door"]!!))
        assertEquals(Bucket.CLIMATE, Rules.category(home["cover.living_room_blinds"]!!))
        val unlock = Rules.tapAction(home["lock.front_door"]!!)!!
        assertEquals("unlock", unlock.service); assertTrue(unlock.confirm)
        assertEquals("toggle", Rules.tapAction(home["light.bedroom"]!!)!!.service)
        assertEquals("open_cover", Rules.tapAction(home["cover.garage_door"]!!)!!.service)
    }

    @Test fun planner() {
        val home = DemoHome.home(now, 0)
        val plans = Planner.plan(home)
        val kinds = plans.map { it.kind }
        assertEquals("favorites", kinds.first())
        assertTrue("suggested" in kinds && "energy" in kinds && "security" in kinds && "weather" in kinds, kinds.toString())
        val rooms = plans.filter { it.kind == "room" }
        assertTrue(rooms.size >= 4, rooms.map { it.title }.toString())
        assertEquals("Living room", rooms.first().title)   // the richest room first
        val living = rooms.first()
        assertEquals("sensor.living_room_temperature", living.first("temperature"))
        assertEquals("climate.living_room", living.first("climate"))
        val sec = Planner.security(home, 12)
        assertEquals("alarm_control_panel.home_alarm", sec.first())
        assertTrue("lock.front_door" in sec && "cover.garage_door" in sec && "binary_sensor.bedroom_window" in sec)
        // fill order: favorites first, then suggestions, then area rules
        val toggles = Planner.fillToggles(home, 6)
        assertEquals(listOf("light.living_room_ceiling", "light.kitchen_ceiling", "switch.coffee_machine", "lock.front_door", "cover.garage_door", "fan.bedroom_fan"), toggles)
        val second = Planner.fill("room", home, taken = setOf("living_room"))
        assertNotNull(second); assertFalse(second.option("area") == "living_room")
        assertEquals("ev", Planner.ev(home)?.kind)
        assertEquals("sensor.car_charger_power", Planner.ev(home)?.first("power"))
        assertEquals(listOf("Porch light"), Planner.offlineDevices(home))
        val spec = WidgetSpec.parse(living.toString())!!
        assertEquals(living.toString(), spec.toString())
    }

    @Test fun pickerGroups() {
        val home = DemoHome.home(now, 0)
        val items = EntityPicker.items(home, Kinds.DEVICES.slot("items"), "", now)
        assertEquals(EntityPicker.Kind.FLOOR, items.first().kind)
        assertEquals("Ground floor", items.first().title)
        assertTrue(items.any { it.kind == EntityPicker.Kind.ENTITY && it.entityId == "cover.garage_door" })
        assertFalse(items.any { it.entityId == "sensor.car_battery" })
        val search = EntityPicker.items(home, null, "kitchen", now).filter { it.kind == EntityPicker.Kind.ENTITY }
        assertTrue(search.isNotEmpty() && search.all { it.title.contains("Kitchen", true) || EntityPicker.whereLine(home, it.entityId!!).contains("Kitchen") }, search.map { it.title }.toString())
        val flat = EntityPicker.items(Home.of(home.states.values.toList()), Kinds.DEVICES.slot("items"), "", now)
        assertEquals("Covers", flat.first().title)
    }

    @Test fun energy() {
        val prefs = Json.parse("""{"energy_sources":[
            {"type":"grid","flow_from":[{"stat_energy_from":"sensor.imp"}],"flow_to":[{"stat_energy_to":"sensor.exp"}],"power":[{"stat_rate":"sensor.grid_w"}]},
            {"type":"solar","stat_energy_from":"sensor.pv","stat_rate":"sensor.pv_w"},
            {"type":"battery","stat_energy_from":"sensor.bout","stat_energy_to":"sensor.bin","stat_rate":"sensor.bat_w","stat_soc":"sensor.soc"},
            {"type":"gas","stat_energy_from":"sensor.gas"}],
            "device_consumption":[{"stat_consumption":"external:fridge","name":"Fridge"}]}""")
        val e = EnergySetup.parse(prefs)
        assertEquals(listOf("sensor.imp"), e.gridImport)
        assertEquals("sensor.grid_w", e.gridPower)
        assertEquals("sensor.pv_w", e.solarPower)
        assertEquals("sensor.soc", e.batterySoc)
        assertTrue(EnergySetup.isExternal(e.devices.first().stat))
        val today = EnergyToday.compute(e, mapOf("sensor.imp" to 5.0, "sensor.pv" to 10.0, "sensor.exp" to 2.0), emptyMap())
        assertEquals(13.0, today.home)
        assertEquals("1.5 kW", EnergyToday.power(1500.0))
        assertEquals(EnergySetup.fromJson(e.toJson()).toJson().toString(), e.toJson().toString())
    }

    @Test fun wsFrames() {
        assertEquals("""{"id":3,"type":"config/area_registry/list"}""", Ws.command(3, "config/area_registry/list"))
        val msgs = Ws.parse("""[{"id":3,"type":"result","success":true,"result":[1]},{"type":"auth_ok","ha_version":"2026.9.1"}]""")
        assertEquals(2, msgs.size); assertTrue(msgs[0].success); assertTrue(msgs[1].isAuthOk)
        assertEquals(listOf("light.a", "switch.b"), Ws.favoritesOf(Json.parse("""{"value":{"favorite_entities":["light.a","switch.b"]}}""")))
        assertEquals(listOf("light.a"), Ws.suggestedOf(Json.parse("""{"entities":["light.a"]}""")))
        val call = Ws.callService("weather", "get_forecasts", Json.obj("type" to "daily"), returnResponse = true)
        assertEquals(true, call.fields!!.flag("return_response"))
    }

    @Test fun lovelaceImport() {
        val home = DemoHome.home(now, 0)
        val cfg = Json.parse("""{"views":[{"title":"Home","cards":[
            {"type":"entities","title":"Lights","entities":["light.kitchen_ceiling",{"entity":"light.bedroom"}]},
            {"type":"thermostat","entity":"climate.living_room"},
            {"type":"vertical-stack","cards":[{"type":"tile","entity":"switch.coffee_machine"},{"type":"tile","entity":"fan.bedroom_fan"}]},
            {"type":"weather-forecast","entity":"weather.home"}]}]}""")
        val plans = Lovelace.plans(cfg, home, "Overview")
        val kinds = plans.map { it.kind }
        assertEquals(listOf("favorites", "room", "weather", "favorites"), kinds)
        assertEquals(listOf("switch.coffee_machine", "fan.bedroom_fan"), plans.last().list("items"))
    }

    @Test fun historySeries() {
        val j = Json.parse("""[[{"entity_id":"sensor.t","state":"20.5","last_changed":"2026-09-21T10:00:00+00:00","attributes":{"unit_of_measurement":"°C"}},
            {"state":"21","last_changed":"2026-09-21T11:00:00+00:00"},{"state":"unavailable","last_changed":"2026-09-21T11:30:00+00:00"},{"state":"19.5","last_changed":"2026-09-21T12:00:00+00:00"}]]""")
        val s = Series.parseHistory(j).single()
        assertEquals(3, s.points.size); assertEquals("°C", s.unit); assertEquals(19.5, s.min); assertEquals(21.0, s.max)
        val from = Time.parseMillis("2026-09-21T10:00:00Z")!!
        val b = s.buckets(4, from, from + 4 * 3_600_000)
        assertEquals(listOf(20.5, 21.0, 19.5, 19.5), b)
        assertEquals(s.toJson().toString(), Series.fromJson(s.toJson())!!.toJson().toString())
    }
}
