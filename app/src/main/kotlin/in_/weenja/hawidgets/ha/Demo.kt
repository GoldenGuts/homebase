package in_.weenja.hawidgets.ha

import in_.weenja.hawidgets.core.DemoHome
import in_.weenja.hawidgets.core.Home
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone

/**
 * Sample states for previews before the first login: the shared demo home (lights, climate, security,
 * energy, …) plus sample sensors for the Extras widgets, named like the Extras YAML packages name them.
 */
object Demo {
    private fun e(id: String, state: String, attrs: Map<String, Any?> = emptyMap()) = JSONObject()
        .put("entity_id", id).put("state", state).put("attributes", JSONObject(attrs))

    private fun offsetMinutes(now: Long) = TimeZone.getDefault().getOffset(now) / 60_000

    fun snapshot(): Snapshot {
        val nowMs = System.currentTimeMillis()
        val arr = JSONArray(Json2.states(nowMs, offsetMinutes(nowMs)))
        extras(nowMs / 1000).forEach { arr.put(it) }
        return Snapshot.parse(arr, nowMs, demo = true)
    }

    /** The demo home for the planner / picker, with the same states as [snapshot]. */
    fun home(): Home {
        val snap = snapshot()
        return Home(snap.coreStates.associateBy { it.id }, DemoHome.registry(), DemoHome.FAVORITES, DemoHome.SUGGESTED, DemoHome.ENERGY, "Demo home", snap.fetchedAt)
    }

    private object Json2 {
        /** Shared demo states as org.json text. */
        fun states(now: Long, off: Int): String = in_.weenja.hawidgets.core.Json.Arr(DemoHome.states(now, off).map { it.toJson() }).toString()
    }

    private fun extras(now: Long): List<JSONObject> {
        val today = LocalDate.now()
        return listOf(
            // gaming PC
            e("binary_sensor.pc_active", "off", mapOf("friendly_name" to "PC active")),
            e("sensor.pc_usage_today", "1.4", mapOf("unit_of_measurement" to "h")),
            e("input_select.pc_game", "Hades II", mapOf("friendly_name" to "Game", "options" to JSONArray(listOf("Hades II", "Elden Ring", "Rocket League", "Stardew Valley")))),
            // Mac + Claude Code
            e("binary_sensor.mac_online", "on", mapOf("friendly_name" to "Mac online")),
            e("binary_sensor.mac_caffeinated", "on"),
            e("sensor.mac_battery", "88", mapOf("unit_of_measurement" to "%", "device_class" to "battery")),
            e("sensor.mac_status", "online", mapOf("session_count" to 2, "active_sessions" to 3, "claude_today" to 5, "load" to "1.8", "disk_used_pct" to 63, "uptime_hours" to 74,
                "battery" to 88, "charging" to true, "keychain" to "unlocked", "caffeinate" to true, "caffeinate_until" to "15:40", "screen_ts" to (now - 600),
                "apps" to JSONObject(mapOf("Xcode" to false, "Cursor" to true, "Zed" to false, "Google Chrome" to true, "Ghostty" to true)),
                "sessions_json" to JSONArray(listOf(
                    JSONObject(mapOf("name" to "homebase", "folder" to "/Users/me/projects/homebase", "project" to "homebase", "status" to "connected", "started" to (now - 5400), "sessions" to 2, "capacity" to 32,
                        "link" to "https://claude.ai/code", "env_link" to "https://claude.ai/code",
                        "served" to JSONArray(listOf(JSONObject(mapOf("title" to "Widget layouts", "link" to "https://claude.ai/code")), JSONObject(mapOf("title" to "Release notes", "link" to "https://claude.ai/code")))))),
                    JSONObject(mapOf("name" to "website", "folder" to "/Users/me/projects/website", "project" to "website", "status" to "starting", "started" to (now - 40), "sessions" to 0, "capacity" to 32, "link" to null, "env_link" to null, "served" to JSONArray())))),
                "projects" to JSONArray(listOf(
                    JSONObject(mapOf("name" to "homebase", "path" to "/Users/me/projects/homebase", "git" to true, "branch" to "main", "dirty" to 2, "ahead" to 1, "behind" to 0, "last" to (now - 300))),
                    JSONObject(mapOf("name" to "website", "path" to "/Users/me/projects/website", "git" to true, "branch" to "feat/hero", "dirty" to 0, "last" to (now - 7200))),
                    JSONObject(mapOf("name" to "notes-app", "path" to "/Users/me/projects/notes-app", "git" to true, "branch" to "main", "dirty" to 0, "last" to (now - 86400))),
                    JSONObject(mapOf("name" to "api-server", "path" to "/Users/me/projects/api-server", "git" to true, "branch" to "main", "dirty" to 12, "last" to (now - 3 * 86400))),
                    JSONObject(mapOf("name" to "dotfiles", "path" to "/Users/me/projects/dotfiles", "git" to false, "last" to (now - 9 * 86400))))))),
            e("input_text.claude_folder", "/Users/me/projects/homebase"),
            e("input_select.claude_mode", "auto", mapOf("options" to JSONArray(listOf("auto", "acceptEdits", "plan", "default")))),
            e("camera.mac_screen", "idle", mapOf("entity_picture" to "/api/camera_proxy/camera.mac_screen?token=demo")),
            // money
            e("input_number.spent_this_month", "38420"), e("input_number.budget_month", "60000"),
            e("input_number.spent_food", "12480"), e("input_number.spent_subs", "3190"), e("input_number.spent_other", "18000"),
            e("input_number.spent_travel", "2650"), e("input_number.spent_shopping", "2100"), e("sensor.bills_due_14d", "4040"),
            e("sensor.spend_feed", now.toString(), mapOf(
                "insight" to JSONObject(mapOf("text" to "Food is 60% of this month so far. Takeaway alone is ₹6,200 across 14 orders.")),
                "coming_up" to JSONArray(listOf(
                    JSONObject(mapOf("merchant" to "Netflix", "amount" to 649, "bill" to null, "date" to today.plusDays(2).toString(), "days" to 2)),
                    JSONObject(mapOf("merchant" to "Electricity", "amount" to 2860, "bill" to "electricity", "date" to today.plusDays(5).toString(), "days" to 5)),
                    JSONObject(mapOf("merchant" to "Internet", "amount" to 1180, "bill" to "internet", "date" to today.plusDays(9).toString(), "days" to 9)),
                    JSONObject(mapOf("merchant" to "Spotify", "amount" to 119, "bill" to null, "date" to today.plusDays(12).toString(), "days" to 12)))),
                "cards" to JSONArray(listOf(
                    JSONObject(mapOf("key" to "card1", "bank" to "Visa", "last" to "1111", "due" to 21600, "due_date" to today.plusDays(2).toString())),
                    JSONObject(mapOf("key" to "card2", "bank" to "Amex", "last" to "2222", "due" to 8340, "due_date" to today.plusDays(11).toString())))),
                "recent" to JSONArray(listOf(
                    JSONObject(mapOf("id" to 1, "ts" to now - 3600, "amount" to 412, "merchant" to "Takeaway", "category" to "food", "kind" to "debit", "bill" to null)),
                    JSONObject(mapOf("id" to 2, "ts" to now - 86400, "amount" to 2860, "merchant" to "Power company", "category" to "other", "kind" to "debit", "bill" to "electricity")),
                    JSONObject(mapOf("id" to 3, "ts" to now - 90000, "amount" to 1299, "merchant" to "Online store", "category" to "shopping", "kind" to "refunded", "bill" to null)),
                    JSONObject(mapOf("id" to 4, "ts" to now - 200000, "amount" to 240, "merchant" to "Taxi", "category" to "travel", "kind" to "debit", "bill" to null)))),
                "days" to JSONArray((4 downTo 0).map { i -> JSONObject(mapOf("date" to today.minusDays(i.toLong()).toString(), "total" to listOf(1240, 380, 2860, 0, 412)[4 - i], "merchants" to JSONArray())) }))),
            // screen time
            e("sensor.screen_time_today", "5.2"), e("sensor.screen_time_week", "23"), e("sensor.screen_time_tv", "1.8"),
            e("sensor.screen_time_phone", "2.1"), e("sensor.screen_time_computer", "3.4"),
            // body (Garmin Connect)
            e("sensor.garmin_connect_body_battery", "72"), e("sensor.garmin_connect_steps", "6842"), e("sensor.garmin_connect_daily_step_goal", "8000"),
            e("sensor.garmin_connect_sleep_score", "81"), e("sensor.garmin_connect_sleep_duration", "7.4"), e("sensor.garmin_connect_resting_heart_rate", "54"),
            e("sensor.garmin_connect_average_stress_level", "27"), e("sensor.garmin_connect_training_readiness", "68"),
            e("sensor.garmin_connect_body_battery_charged", "61"), e("sensor.garmin_connect_body_battery_drained", "24"),
            // fuel
            e("input_number.fuel_calories", "1420"), e("input_number.fuel_calorie_goal", "2200"), e("input_number.fuel_protein", "78"),
            e("input_number.fuel_carbs", "150"), e("input_number.fuel_fat", "48"), e("input_number.fuel_protein_goal", "120"),
            e("input_number.fuel_water", "1250"), e("input_number.fuel_water_goal", "2500"), e("input_text.fuel_last_entry", "13:10 · Lunch · 640 kcal"),
            // overhead
            e("sensor.planes_overhead", "3", mapOf("close" to 1, "radius_km" to 100, "updated" to Instant.now().toString(), "planes" to JSONArray(listOf(
                JSONObject(mapOf("callsign" to "KLM1234", "airline" to "KLM", "alt_ft" to 11000, "dist_km" to 14.2, "dir" to "NE", "climb" to -1)),
                JSONObject(mapOf("callsign" to "BAW432", "airline" to "British Airways", "alt_ft" to 36000, "dist_km" to 61.5, "dir" to "SW", "climb" to 0)),
                JSONObject(mapOf("callsign" to "UAE148", "airline" to "Emirates", "alt_ft" to 39000, "dist_km" to 88.0, "dir" to "W", "climb" to 0)))),
                "closest" to JSONObject(mapOf("callsign" to "KLM1234", "dist_km" to 14.2, "dir" to "NE")))),
            e("sensor.satellites_overhead", "52", mapOf("high" to 244, "starlink" to 31, "nav" to 12, "isro" to 6, "lit" to 52, "night" to false, "updated" to Instant.now().toString(),
                "highest" to JSONArray(listOf(JSONObject(mapOf("name" to "Starlink 30264", "el" to 81, "dir" to "SE", "alt_km" to 549)))),
                "iss" to JSONObject(mapOf("el" to -20, "above" to false, "next_pass" to JSONObject(mapOf("start" to Instant.now().plusSeconds(52 * 60).toString(), "max_el" to 64, "starts_in_min" to 52)))))),
        )
    }
}
