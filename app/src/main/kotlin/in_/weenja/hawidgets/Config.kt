package in_.weenja.hawidgets

import android.content.Context
import android.content.SharedPreferences
import in_.weenja.hawidgets.core.WidgetSpec

/**
 * Global defaults every widget falls back to when its own configuration (see [WidgetStore]) leaves a
 * key open. Nothing here names a real entity of anyone's home: core keys default to empty (the widgets
 * then fill themselves from Home Assistant), extras keys default to the names the Extras YAML packages
 * create (assets/extras).
 */
object Config {
    enum class Type { ENTITY, ENTITIES, SCRIPT, TEXT, PATH }

    class Key(val id: String, val label: String, val def: String, val hint: String = "", val type: Type = Type.ENTITY, val domains: Set<String> = emptySet())
    class Group(val id: String, val title: String, val blurb: String, val keys: List<Key>, val extras: Boolean = false)

    private fun e(id: String, label: String, def: String, vararg domains: String, hint: String = "") = Key(id, label, def, hint, Type.ENTITY, domains.toSet())
    private fun many(id: String, label: String, vararg domains: String, hint: String = "") = Key(id, label, "", hint, Type.ENTITIES, domains.toSet())
    private fun script(id: String, label: String, def: String, hint: String = "") = Key(id, label, def, hint, Type.SCRIPT, setOf("script"))
    private fun text(id: String, label: String, def: String, hint: String = "") = Key(id, label, def, hint, Type.TEXT)
    private fun path(id: String, label: String, hint: String = "") = Key(id, label, "", hint, Type.PATH)

    val groups: List<Group> = listOf(
        Group("general", "General", "Shown on several widgets.", listOf(
            text("place", "Place name", "", "Weather / Sky header. Empty = the weather entity's name"),
            text("currency", "Currency symbol", "", "Empty = the currency set in Home Assistant. ₹ uses Indian digit grouping (1,20,000), anything else groups by thousands"),
            path("path_home", "Dashboard to open", "A path like /lovelace/home. Empty = your default dashboard"),
            path("path_sky", "Dashboard · weather"),
        )),
        Group("lights", "Lights & fan", "Used by the Light, Scenes and legacy tiles when a widget has no light of its own.", listOf(
            e("light_bulb", "Light A", "", "light", hint = "Empty = your first favorite light"),
            e("light_tube", "Light B", "", "light", hint = "Empty = your second light"),
            e("light_group", "Light group", "", "light", hint = "Switched by Scenes → Lights"),
            e("fan", "Fan", "", "fan", hint = "Empty = the first fan"),
        )),
        Group("media", "TV & media", "The TV remote, Now playing and the Music tile.", listOf(
            e("media_tv", "TV media player", "", "media_player", hint = "Empty = the first media player that is a TV"),
            e("remote_tv", "TV remote entity", "", "remote", hint = "remote.send_command target for the keys"),
            e("media_music", "Music player (play button target)", "", "media_player", "script"),
            e("media_speaker", "Speaker for volume", "", "media_player", hint = "Used when the playing player has no volume"),
            many("media_players", "Players, in priority order", "media_player", hint = "Empty = every media player"),
            many("all_off", "\"All off\" entities", "light", "switch", "fan", "media_player", "cover", hint = "Empty = every light and media player"),
            script("script_movie", "Script · movie mode", ""),
            script("script_tv_app", "Script · open a TV app", "", "Gets app"),
            text("tv_youtube", "YouTube app id on the TV", "", "com.google.android.youtube.tv (Android TV), com.amazon.firetv.youtube (Fire TV)"),
            script("script_kodi_open", "Script · open Kodi", ""),
            script("script_kodi_play", "Script · play Kodi item", "", "Gets kind, id, resume"),
            e("kodi_menu", "Kodi menu sensor", "", "sensor", hint = "Attributes continue + new"),
        )),
        Group("weather", "Weather & sky", "Weather, sun and the home zone.", listOf(
            e("weather", "Weather entity", "", "weather", hint = "Empty = the first weather entity"),
            e("forecast_daily", "Daily forecast sensor (legacy)", "", "sensor", hint = "Empty = weather.get_forecasts, which every weather entity supports"),
            e("forecast_hourly", "Hourly forecast sensor (legacy)", "", "sensor"),
            e("sun", "Sun", "sun.sun", "sun"),
            e("zone_home", "Home zone", "zone.home", "zone"),
        )),
        Group("today", "Today", "The to-do widget's status pills.", listOf(
            e("todo", "To-do list", "", "todo", hint = "Empty = the first list"),
            e("phone_battery", "Phone battery level", "", "sensor", hint = "Empty = the battery of a phone with the Home Assistant app"),
            e("phone_battery_state", "Phone battery state", "", "sensor"),
        )),
        Group("body", "Body", "Garmin Connect sensors.", listOf(
            text("garmin_prefix", "Sensor prefix", "sensor.garmin_connect_", "+ body_battery, steps, daily_step_goal, sleep_score, sleep_duration, resting_heart_rate, …"),
            path("path_fitness", "Dashboard · fitness"),
        )),
        Group("gaming", "Gaming PC", "Wake-on-LAN + a game picker. Guide and YAML under Extras.", extras = true, keys = listOf(
            e("pc_active", "PC online sensor", "binary_sensor.pc_active", "binary_sensor"),
            e("pc_usage", "PC hours today", "sensor.pc_usage_today", "sensor"),
            text("pc_mac", "PC MAC address", "", "For wake_on_lan, e.g. aa:bb:cc:dd:ee:ff"),
            text("pc_broadcast", "PC broadcast address", "", "Optional, e.g. 192.168.1.255"),
            e("pc_game", "Game select entity", "input_select.pc_game", "select", "input_select"),
            e("pc_launcher", "Launcher running sensor", "", "binary_sensor", hint = "Optional"),
            script("script_launch_game", "Script · launch selected game", "launch_selected_game"),
            script("script_game_quick", "Script · quick launch", ""),
            text("game_quick_label", "Quick launch label", ""),
            path("path_gaming", "Dashboard · gaming PC"),
        )),
        Group("mac", "Mac & Claude Code", "Status sensor with attributes and scripts named <prefix>sleep, <prefix>lock, …", extras = true, keys = listOf(
            e("mac_status", "Mac status sensor", "sensor.mac_status", "sensor", hint = "Attributes: battery, load, disk_used_pct, uptime_hours, apps, sessions_json, projects, …"),
            e("mac_online", "Mac online sensor", "binary_sensor.mac_online", "binary_sensor"),
            e("mac_battery", "Mac battery sensor", "sensor.mac_battery", "sensor"),
            e("mac_caffeinated", "Keep-awake helper", "input_boolean.mac_caffeinated", "input_boolean", "binary_sensor"),
            e("mac_camera", "Screenshot camera", "camera.mac_screen", "camera"),
            text("mac_mac", "Mac MAC address", "", "For Wake"),
            text("mac_broadcast", "Mac broadcast address", "", "Optional"),
            text("mac_script_prefix", "Script prefix", "mac_", "Scripts: open_app, sleep, lock, display_off, screenshot, caffeinate, decaffeinate"),
            script("claude_start", "Script · start Claude host", "claude_rc_start", "Gets mode: resume for Resume"),
            script("claude_stop", "Script · stop one host", "mac_stop_claude", "Gets name"),
            script("claude_stop_all", "Script · stop every host", "mac_stop_all_claude"),
            e("claude_folder", "Selected folder (input_text)", "input_text.claude_folder", "input_text"),
            e("claude_mode", "Permission mode (input_select)", "input_select.claude_mode", "input_select"),
            path("path_mac", "Dashboard · Mac"),
        )),
        Group("overhead", "Overhead", "Planes and satellites sensors.", extras = true, keys = listOf(
            e("planes", "Planes overhead sensor", "sensor.planes_overhead", "sensor"),
            e("satellites", "Satellites overhead sensor", "sensor.satellites_overhead", "sensor"),
        )),
        Group("screentime", "Screen time", "Hours as numbers.", extras = true, keys = listOf(
            e("st_today", "Total today", "sensor.screen_time_today", "sensor"),
            e("st_week", "Total this week", "sensor.screen_time_week", "sensor"),
            e("st_tv", "TV today", "sensor.screen_time_tv", "sensor"),
            e("st_phone", "Phone today", "sensor.screen_time_phone", "sensor"),
            e("st_mac", "Computer today", "sensor.screen_time_computer", "sensor"),
        )),
        Group("fuel", "Fuel", "Calories, macros and water.", extras = true, keys = listOf(
            e("nut_cal", "Calories today", "input_number.fuel_calories", "sensor", "input_number"),
            e("nut_goal", "Calorie goal", "input_number.fuel_calorie_goal", "sensor", "input_number"),
            e("nut_protein", "Protein (g)", "input_number.fuel_protein", "sensor", "input_number"),
            e("nut_carbs", "Carbs (g)", "input_number.fuel_carbs", "sensor", "input_number"),
            e("nut_fat", "Fat (g)", "input_number.fuel_fat", "sensor", "input_number"),
            e("goal_protein", "Protein goal", "input_number.fuel_protein_goal", "sensor", "input_number"),
            e("water", "Water today (ml)", "input_number.fuel_water", "sensor", "input_number"),
            e("water_goal", "Water goal (ml)", "input_number.fuel_water_goal", "sensor", "input_number"),
            e("last_food", "Last entry text", "input_text.fuel_last_entry", "input_text"),
            script("script_water", "Script · add water", "fuel_add_water", "Gets ml"),
            script("script_food", "Script · add food", "fuel_add_food", "Gets calories, name"),
        )),
        Group("money", "Money", "Spend this month, category totals and the feed sensor.", extras = true, keys = listOf(
            e("spent_month", "Spent this month", "input_number.spent_this_month", "input_number", "sensor"),
            e("budget_month", "Monthly budget", "input_number.budget_month", "input_number", "sensor"),
            e("bills_14d", "Bills due in 14 days", "sensor.bills_due_14d", "sensor", "input_number"),
            e("spend_feed", "Spend feed sensor", "sensor.spend_feed", "sensor", hint = "Attributes insight, coming_up, cards, recent, days"),
            e("cat_food", "Category · Food", "input_number.spent_food", "input_number", "sensor"),
            e("cat_subs", "Category · Subs", "input_number.spent_subs", "input_number", "sensor"),
            e("cat_other", "Category · Rent / other", "input_number.spent_other", "input_number", "sensor"),
            e("cat_travel", "Category · Travel", "input_number.spent_travel", "input_number", "sensor"),
            e("cat_shopping", "Category · Shopping", "input_number.spent_shopping", "input_number", "sensor"),
        )),
    )

    val keys: Map<String, Key> = groups.flatMap { it.keys }.associateBy { it.id }

    fun group(id: String): Group? = groups.firstOrNull { it.id == id }

    /** Overrides live in their own file (backed up; the tokens are not). */
    fun sp(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences("config", Context.MODE_PRIVATE)

    fun get(ctx: Context, id: String): String {
        val k = keys[id] ?: error("unknown config key $id")
        return sp(ctx).getString("cfg_$id", null)?.trim()?.takeIf { it.isNotEmpty() } ?: k.def
    }

    /** Stores the value; empty or equal to the default removes the override. */
    fun set(ctx: Context, id: String, value: String) {
        val v = value.trim()
        val e = sp(ctx).edit()
        if (v.isEmpty() || v == keys[id]?.def) e.remove("cfg_$id") else e.putString("cfg_$id", v)
        e.apply()
    }

    fun isOverridden(ctx: Context, id: String) = sp(ctx).contains("cfg_$id")

    fun reset(ctx: Context) {
        val e = sp(ctx).edit(); keys.keys.forEach { e.remove("cfg_$it") }; e.apply()
    }

    /** Every override, for the backup file. */
    fun overrides(ctx: Context): Map<String, String> = keys.keys.mapNotNull { k -> sp(ctx).getString("cfg_$k", null)?.let { k to it } }.toMap()

    /** 2.0 kept the overrides next to the tokens; move them into their own file once. */
    fun migrate(ctx: Context) {
        val old = ctx.applicationContext.getSharedPreferences("hawidgets", Context.MODE_PRIVATE)
        if (old.getBoolean("cfg_migrated", false)) return
        val e = sp(ctx).edit(); val o = old.edit()
        for ((k, v) in old.all) if (k.startsWith("cfg_") && v is String) { e.putString(k, v); o.remove(k) }
        e.apply(); o.putBoolean("cfg_migrated", true).apply()
    }

    /** Every entity id the global configuration mentions (for the fetch filter). */
    fun entityIds(ctx: Context): Set<String> = keys.keys.flatMap { id -> get(ctx, id).split(',').map { it.trim() } }
        .filter { looksLikeEntity(it) }.toSet()

    fun looksLikeEntity(s: String) = s.contains('.') && !s.startsWith("/") && !s.contains(' ') && s.substringBefore('.').all { it.isLowerCase() || it == '_' }

    /** Entity id prefixes the widgets read by pattern (Garmin sensors). */
    fun prefixes(ctx: Context): List<String> = listOf(get(ctx, "garmin_prefix"))
}

/**
 * Per-draw view of the configuration of one widget: its own [WidgetSpec] first (slots and options), then
 * the global [Config]. `cfg["light_group"]`, `cfg.list("all_off")`, `cfg.slot("items")`.
 */
class Cfg(val ctx: Context, val spec: WidgetSpec? = null) {
    private val cache = HashMap<String, String>()
    operator fun get(id: String): String = cache.getOrPut(id) {
        spec?.options?.get(id)?.takeIf { it.isNotBlank() } ?: spec?.slots?.get(id)?.takeIf { it.isNotEmpty() }?.joinToString(",")
            ?: if (Config.keys.containsKey(id)) Config.get(ctx, id) else ""
    }
    fun list(id: String): List<String> = get(id).split(',').map { it.trim() }.filter { it.isNotEmpty() }
    fun has(id: String): Boolean = get(id).isNotEmpty()
    /** Entity ids of a slot of this widget (never the global defaults). */
    fun slot(key: String): List<String> = spec?.list(key) ?: emptyList()
    fun one(key: String): String? = spec?.first(key)
    fun option(key: String): String? = spec?.option(key)
    val title: String get() = spec?.title ?: ""
    /** Symbol + grouping for money: ₹ groups the Indian way. Empty = Home Assistant's currency (from the scan). */
    val currency: String get() = get("currency").ifEmpty { symbolOf(Prefs(ctx).haCurrency) }

    private fun symbolOf(code: String): String =
        try { java.util.Currency.getInstance(code).getSymbol(java.util.Locale.getDefault()) } catch (e: Exception) { "€" }
}
