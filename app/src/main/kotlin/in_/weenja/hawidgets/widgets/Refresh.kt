package in_.weenja.hawidgets.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import in_.weenja.hawidgets.Cfg
import in_.weenja.hawidgets.Config
import in_.weenja.hawidgets.HomeStore
import in_.weenja.hawidgets.Prefs
import in_.weenja.hawidgets.WidgetStore
import in_.weenja.hawidgets.core.Extra
import in_.weenja.hawidgets.core.Json
import in_.weenja.hawidgets.core.Planner
import in_.weenja.hawidgets.core.Rules
import in_.weenja.hawidgets.core.Series
import in_.weenja.hawidgets.core.WidgetSpec
import in_.weenja.hawidgets.ha.Api
import in_.weenja.hawidgets.ha.Demo
import in_.weenja.hawidgets.ha.Scanner
import in_.weenja.hawidgets.ha.Snapshot
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Fetch + redraw coordinator. Triggers: a self-re-arming alarm that is allowed while the phone idles
 * (so a widget is fresh as soon as the screen turns on), network-constrained periodic work, taps and
 * boot. Every widget draws "updated N min ago" so stale data is never silent.
 */
object Refresh {
    private const val TAG = Api.TAG
    private val lock = Mutex()

    val providers get() = Catalog.providers

    fun widgetCount(ctx: Context): Int {
        val mgr = AppWidgetManager.getInstance(ctx)
        return providers.sumOf { mgr.getAppWidgetIds(ComponentName(ctx, it)).size }
    }

    /** Every placed widget: id, catalog entry and its own configuration (if any). */
    fun placed(ctx: Context): List<Triple<Int, Catalog.Entry, WidgetSpec?>> {
        val mgr = AppWidgetManager.getInstance(ctx)
        val specs = WidgetStore.all(ctx)
        return Catalog.all.flatMap { e -> mgr.getAppWidgetIds(ComponentName(ctx, e.cls)).map { Triple(it, e, specs[it]) } }
    }

    /** The snapshot to draw right now, without network: last fetch, else demo data. */
    fun current(ctx: Context): Snapshot = Snapshot.load(ctx) ?: Demo.snapshot()

    /** Fetch all states from HA, attach what the widgets need beyond them, save. Null (and the error recorded) on failure. */
    suspend fun fetch(ctx: Context): Snapshot? {
        val prefs = Prefs(ctx)
        if (!prefs.isLoggedIn) { prefs.lastError = "Not logged in"; return null }
        return try {
            val api = Api(ctx)
            val prev = Snapshot.load(ctx)
            val placed = placed(ctx)
            val arr = slim(ctx, api.states(), placed)
            val byId = HashMap<String, JSONObject>()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { byId[it.optString("entity_id")] = it }
            val snap0 = Snapshot.parse(arr, System.currentTimeMillis())
            attachTodo(api, byId, placed, ctx, snap0)
            attachForecasts(api, byId, placed, ctx, snap0)
            attachCalendars(api, byId, placed, prev, prefs)
            attachHistory(api, byId, placed, prev, prefs)
            attachEnergy(ctx, arr, placed, prev, prefs)
            val at = System.currentTimeMillis()
            Snapshot.save(ctx, arr, at)
            prefs.lastFetchAt = at
            prefs.lastError = null
            val snap = Snapshot.parse(arr, at)
            fetchImages(ctx, api, snap, placed)
            snap
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed: $e")
            prefs.lastError = e.message ?: e.toString()
            null
        }
    }

    /** Fetch (optionally) and redraw every widget of every kind. Serialised so taps and alarms do not race. */
    suspend fun all(ctx: Context, fetch: Boolean = true) = lock.withLock {
        val snap = (if (fetch) fetch(ctx) else null) ?: current(ctx)
        drawAll(ctx, snap)
    }

    fun drawAll(ctx: Context, snap: Snapshot) {
        val mgr = AppWidgetManager.getInstance(ctx)
        for (e in Catalog.all) {
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, e.cls))
            if (ids.isEmpty()) continue
            for (id in ids) try { e.widget.render(ctx, mgr, id, snap) } catch (ex: Exception) { Log.e(TAG, "render ${e.id} $id", ex) }
        }
    }

    /** Redraw one widget (after its settings changed). */
    fun drawOne(ctx: Context, id: Int) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val cls = mgr.getAppWidgetInfo(id)?.provider?.className ?: return
        Catalog.byClassName(cls)?.widget?.render(ctx, mgr, id, current(ctx))
    }

    // ------------------------------------------------------------ what to keep and attach

    private val KEEP_DOMAINS = setOf("light", "fan", "switch", "media_player", "input_number", "input_select", "input_boolean", "input_text", "input_button",
        "binary_sensor", "weather", "todo", "sun", "select", "update", "remote", "scene", "script", "camera", "zone", "climate", "cover", "lock",
        "alarm_control_panel", "vacuum", "person", "calendar", "timer", "lawn_mower", "valve", "humidifier", "water_heater", "siren", "button", "number")
    /** Sensors the widgets find on their own (room climate, batteries, energy, timers, cars, bins). */
    private val KEEP_SENSOR_CLASSES = setOf("temperature", "humidity", "battery", "power", "energy", "timestamp", "date", "distance", "carbon_dioxide", "duration")
    /** Domains whose attributes are dead weight for us (update.* carries release notes, scripts their fields). */
    private val STRIP_ATTRS = setOf("update", "script", "scene", "remote", "automation", "button", "input_button")

    /** Drop entities no widget reads, keep every one some widget or setting names. */
    private fun slim(ctx: Context, arr: JSONArray, placed: List<Triple<Int, Catalog.Entry, WidgetSpec?>>): JSONArray {
        val out = JSONArray()
        val ids = Config.entityIds(ctx) + placed.flatMap { it.third?.entityIds ?: emptyList() } + HomeStore.home(ctx, Snapshot(emptyMap(), 0)).let { it.favorites + it.suggested + it.energy.liveEntities }
        val prefixes = Config.prefixes(ctx).filter { it.isNotEmpty() }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("entity_id")
            val dom = id.substringBefore('.')
            val attrs = o.optJSONObject("attributes")
            val keep = dom in KEEP_DOMAINS || id in ids || prefixes.any { id.startsWith(it) } ||
                (dom == "sensor" && attrs?.optString("device_class") in KEEP_SENSOR_CLASSES)
            if (!keep) continue
            if (dom in STRIP_ATTRS) o.put("attributes", JSONObject().put("friendly_name", attrs?.optString("friendly_name") ?: "").apply { attrs?.optString("icon")?.takeIf { it.isNotEmpty() }?.let { put("icon", it) } })
            out.put(o)
        }
        return out
    }

    private fun kinds(placed: List<Triple<Int, Catalog.Entry, WidgetSpec?>>, vararg k: String) = placed.filter { it.second.kind in k }

    /** todo.get_items answers with data, so the items ride along as an attribute of the list. */
    private suspend fun attachTodo(api: Api, byId: Map<String, JSONObject>, placed: List<Triple<Int, Catalog.Entry, WidgetSpec?>>, ctx: Context, snap: Snapshot) {
        val lists = kinds(placed, "todo").mapNotNull { (_, _, spec) -> Find.todo(Cfg(ctx, spec), snap)?.id }.toSet()
        for (id in lists) {
            val o = byId[id] ?: continue
            try {
                val resp = api.callServiceResponse("todo", "get_items", JSONObject().put("entity_id", id)) ?: continue
                val items = resp.optJSONObject(id)?.optJSONArray("items") ?: continue
                attrs(o).put(Extra.ITEMS, items)
            } catch (e: Exception) { Log.w(TAG, "todo items: $e") }
        }
    }

    /** weather.get_forecasts (daily + hourly) for every weather entity a widget shows. Works for every weather integration. */
    private suspend fun attachForecasts(api: Api, byId: Map<String, JSONObject>, placed: List<Triple<Int, Catalog.Entry, WidgetSpec?>>, ctx: Context, snap: Snapshot) {
        val ids = kinds(placed, "weather", "sky").mapNotNull { (_, _, spec) -> Find.weather(Cfg(ctx, spec), snap)?.id }.toSet()
        for (id in ids) {
            val o = byId[id] ?: continue
            for ((type, key) in listOf("daily" to Extra.DAILY, "hourly" to Extra.HOURLY)) {
                try {
                    val resp = api.callServiceResponse("weather", "get_forecasts", JSONObject().put("entity_id", id).put("type", type)) ?: continue
                    resp.optJSONObject(id)?.optJSONArray("forecast")?.let { attrs(o).put(key, it) }
                } catch (e: Exception) { Log.i(TAG, "forecast $type $id: $e") }
            }
        }
    }

    /** Calendar events for the next two weeks (every 15 min; the previous fetch is reused in between). */
    private suspend fun attachCalendars(api: Api, byId: Map<String, JSONObject>, placed: List<Triple<Int, Catalog.Entry, WidgetSpec?>>, prev: Snapshot?, prefs: Prefs) {
        val ids = kinds(placed, "calendar", "bins").flatMap { (_, e, spec) ->
            spec?.list("items")?.filter { it.startsWith("calendar.") } ?: if (e.kind == "calendar") byId.keys.filter { it.startsWith("calendar.") }.take(6) else emptyList()
        }.toSet()
        if (ids.isEmpty()) return
        val due = System.currentTimeMillis() - prefs.lastCalendarAt > 15 * 60_000L
        for (id in ids) {
            val o = byId[id] ?: continue
            val old = prev?.get(id)?.attrs?.optJSONArray(Extra.EVENTS)
            if (!due && old != null) { attrs(o).put(Extra.EVENTS, old); continue }
            try {
                val now = Instant.now()
                api.calendarEvents(id, now.toString(), now.plusSeconds(14 * 86400L).toString())?.let { list ->
                    val evs = JSONArray()
                    for (i in 0 until minOf(list.length(), 20)) {
                        val ev = list.optJSONObject(i) ?: continue
                        val start = ev.optJSONObject("start"); val end = ev.optJSONObject("end")
                        val s = start?.optString("dateTime")?.takeIf { it.isNotEmpty() } ?: start?.optString("date") ?: continue
                        val en = end?.optString("dateTime")?.takeIf { it.isNotEmpty() } ?: end?.optString("date")
                        evs.put(JSONObject().put("summary", ev.optString("summary")).put("start", s).put("end", en).put("all_day", start.has("date") && !start.has("dateTime")))
                    }
                    attrs(o).put(Extra.EVENTS, evs)
                }
            } catch (e: Exception) { Log.i(TAG, "calendar $id: $e"); old?.let { attrs(o).put(Extra.EVENTS, it) } }
        }
        if (due) prefs.lastCalendarAt = System.currentTimeMillis()
    }

    /** 24 h of history for graphed sensors (every 15 min). */
    private suspend fun attachHistory(api: Api, byId: Map<String, JSONObject>, placed: List<Triple<Int, Catalog.Entry, WidgetSpec?>>, prev: Snapshot?, prefs: Prefs) {
        val ids = kinds(placed, "graph").mapNotNull { it.third?.first("sensor") }.filter { byId.containsKey(it) }.toSet()
        if (ids.isEmpty()) return
        val due = System.currentTimeMillis() - prefs.lastHistoryAt > 15 * 60_000L
        if (!due) {
            for (id in ids) prev?.get(id)?.attrs?.optJSONObject(Extra.HISTORY)?.let { attrs(byId[id]!!).put(Extra.HISTORY, it) }
            return
        }
        try {
            val hist = api.history(ids.toList(), Instant.now().minusSeconds(24 * 3600L).toString()) ?: return
            val series = Series.parseHistory(Json.parseOrNull(hist.toString())) { id -> byId[id]?.optJSONObject("attributes")?.optString("unit_of_measurement") ?: "" }
            for (s in series) byId[s.entityId]?.let { attrs(it).put(Extra.HISTORY, JSONObject(s.toJson().toString())) }
            prefs.lastHistoryAt = System.currentTimeMillis()
        } catch (e: Exception) { Log.i(TAG, "history: $e") }
    }

    /** Today's energy totals as a synthetic entity (WebSocket statistics, every 10 min). */
    private suspend fun attachEnergy(ctx: Context, arr: JSONArray, placed: List<Triple<Int, Catalog.Entry, WidgetSpec?>>, prev: Snapshot?, prefs: Prefs) {
        if (kinds(placed, "energy").isEmpty()) return
        val setup = HomeStore.home(ctx, Snapshot(emptyMap(), 0)).energy
        if (setup.todayStats.isEmpty()) return
        val old = prev?.get(Extra.ENERGY_ID)
        val fresh = System.currentTimeMillis() - prefs.lastEnergyAt < 10 * 60_000L
        val attrs = if (fresh && old != null) old.attrs else try {
            val ch = Scanner.energyToday(ctx, setup)
            prefs.lastEnergyAt = System.currentTimeMillis()
            fun sum(ids: List<String>) = ids.mapNotNull { ch[it] }.takeIf { it.isNotEmpty() }?.sum()
            JSONObject().put("friendly_name", "Energy today").apply {
                sum(setup.gridImport)?.let { put("grid_in", it) }; sum(setup.gridExport)?.let { put("grid_out", it) }; sum(setup.solarEnergy)?.let { put("solar", it) }
                sum(setup.batteryIn)?.let { put("battery_in", it) }; sum(setup.batteryOut)?.let { put("battery_out", it) }
            }
        } catch (e: Exception) { Log.i(TAG, "energy: $e"); old?.attrs ?: return }
        arr.put(JSONObject().put("entity_id", Extra.ENERGY_ID).put("state", "ok").put("attributes", attrs))
    }

    private fun attrs(o: JSONObject): JSONObject = o.optJSONObject("attributes") ?: JSONObject().also { o.put("attributes", it) }

    /** Artwork, camera snapshots and avatars are drawn from the disk cache, so pull them now. */
    private suspend fun fetchImages(ctx: Context, api: Api, snap: Snapshot, placed: List<Triple<Int, Catalog.Entry, WidgetSpec?>>) {
        for ((_, _, spec) in kinds(placed, "now")) NowWidget.artUrl(Cfg(ctx, spec), snap)?.let { api.image(it) }
        for ((_, _, spec) in kinds(placed, "camera")) {
            val id = spec?.first("camera") ?: snap.byDomain("camera").firstOrNull()?.id ?: continue
            snap[id]?.str("entity_picture")?.takeIf { it.isNotEmpty() }?.let { api.image(it, cacheKey = CameraWidget.CAMERA_KEY + id, fresh = true, keep = true) }
        }
        if (kinds(placed, "people").isNotEmpty()) for (p in snap.byDomain("person")) p.str("entity_picture").takeIf { it.isNotEmpty() }?.let { api.image(it, keep = true) }
        // the Mac screenshot is re-fetched on every refresh while a Mac screen widget exists (the camera URL never changes)
        if (placed.any { it.second.cls == MacScreenWidget::class.java })
            snap[Cfg(ctx)["mac_camera"]]?.str("entity_picture")?.takeIf { it.isNotEmpty() }?.let { api.image(it, cacheKey = MacScreenWidget.MAC_SCREEN_KEY, fresh = true) }
    }

    // ------------------------------------------------------------ scheduling

    fun enqueueNow(ctx: Context, delayMs: Long = 0) {
        val req = OneTimeWorkRequestBuilder<RefreshWorker>().apply { if (delayMs > 0) setInitialDelay(delayMs, TimeUnit.MILLISECONDS) }.build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("refresh-now", ExistingWorkPolicy.REPLACE, req)
    }

    fun schedule(ctx: Context) {
        val periodic = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("refresh-periodic", ExistingPeriodicWorkPolicy.UPDATE, periodic)
        armAlarm(ctx)
    }

    /**
     * The next refresh alarm. setAndAllowWhileIdle survives Doze (at most every ~9 min while idle), and as a
     * non-wakeup alarm an overdue one fires the moment the screen turns on, when the widgets are looked at.
     */
    fun armAlarm(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val interval = Prefs(ctx).refreshMinutes * 60_000L
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + interval, alarmIntent(ctx))
    }

    fun cancel(ctx: Context) {
        WorkManager.getInstance(ctx).cancelUniqueWork("refresh-periodic")
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(ctx))
    }

    private fun alarmIntent(ctx: Context): PendingIntent {
        val i = Intent(ActionReceiver.ACT_ALARM).setClass(ctx, ActionReceiver::class.java).setData(android.net.Uri.parse("hawidgets://alarm"))
        return PendingIntent.getBroadcast(ctx, 7, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Daily re-scan (registries, energy prefs) and hourly Favorites / Suggested, from the worker. */
    suspend fun housekeeping(ctx: Context) {
        val prefs = Prefs(ctx)
        if (!prefs.isLoggedIn) return
        val now = System.currentTimeMillis()
        try {
            if (now - prefs.lastScanAt > 24 * 3_600_000L) { Scanner.scan(ctx); updatePreviews(ctx) }
            else if (now - prefs.lastListsAt > 3_600_000L && placed(ctx).any { it.second.kind in setOf("favorites", "suggested") }) {
                Scanner.refreshLists(ctx); prefs.lastListsAt = now
            }
        } catch (e: Exception) { Log.i(TAG, "housekeeping: $e") }
    }

    /**
     * Android 15+: show the user's own home in the launcher's widget picker (setWidgetPreview). The system
     * rate-limits this to about two calls an hour, so rotate through a few core widgets.
     */
    fun updatePreviews(ctx: Context) {
        if (Build.VERSION.SDK_INT < 35) return
        val prefs = Prefs(ctx)
        if (System.currentTimeMillis() - prefs.lastPreviewAt < 30 * 60_000L) return
        val snap = Snapshot.load(ctx) ?: return
        val home = HomeStore.home(ctx, snap)
        val order = listOf("favorites", "room", "energy", "security", "devices", "suggested")
        val mgr = AppWidgetManager.getInstance(ctx)
        var done = 0
        for (k in order.drop(prefs.previewCursor % order.size) + order.take(prefs.previewCursor % order.size)) {
            if (done >= 2) break
            val entry = Catalog.byId(k) ?: continue
            val spec = Planner.fill(entry.kind, home, 8) ?: continue
            try {
                val rv = entry.widget.build(ctx, entry.w, entry.h, snap, spec)
                if (mgr.setWidgetPreview(ComponentName(ctx, entry.cls), AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, rv)) done++ else break
            } catch (e: Exception) { Log.i(TAG, "setWidgetPreview: $e"); break }
            prefs.previewCursor = prefs.previewCursor + 1
        }
        prefs.lastPreviewAt = System.currentTimeMillis()
    }
}

class RefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        if (Refresh.widgetCount(applicationContext) == 0) return Result.success()
        Refresh.housekeeping(applicationContext)
        Refresh.all(applicationContext, fetch = true)
        return Result.success()
    }
}

/** Re-arms the refresh after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Refresh.widgetCount(context) > 0) { Refresh.schedule(context); Refresh.enqueueNow(context, 5_000) }
    }
}
