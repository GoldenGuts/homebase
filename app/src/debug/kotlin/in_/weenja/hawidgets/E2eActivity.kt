package in_.weenja.hawidgets

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import in_.weenja.hawidgets.core.EntityPicker
import in_.weenja.hawidgets.core.Kinds
import in_.weenja.hawidgets.core.Planner
import in_.weenja.hawidgets.ha.Api
import in_.weenja.hawidgets.ha.Scanner
import in_.weenja.hawidgets.ha.Snapshot
import in_.weenja.hawidgets.ui.Painter
import in_.weenja.hawidgets.widgets.Catalog
import in_.weenja.hawidgets.widgets.Refresh
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Debug builds only: CI's end-to-end run against a real Home Assistant. Logs in with an OAuth code (or a
 * long-lived token), fetches, scans, plans, draws every suggested widget and the whole catalog with the
 * live data, toggles a light, and writes Android/data/<pkg>/files/e2e/report.json plus the PNGs.
 *
 * adb shell am start -n in.weenja.hawidgets/in_.weenja.hawidgets.E2eActivity --es url http://10.0.2.2:8123 --es code … --es token …
 */
class E2eActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val out = TextView(this).apply { setTextColor(Color.WHITE); textSize = 11f; setPadding(24, 96, 24, 24); text = "E2E running…" }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(out) })
        val url = intent.getStringExtra("url") ?: return
        val code = intent.getStringExtra("code")
        val token = intent.getStringExtra("token")
        val dir = File(getExternalFilesDir(null) ?: filesDir, "e2e").apply { deleteRecursively(); mkdirs() }
        Thread {
            val report = JSONObject()
            val ctx = this
            fun step(name: String, block: suspend () -> Any?) {
                val v = try { runBlocking { block() } } catch (e: Throwable) { Log.e(TAG, name, e); "FAIL: $e" }
                report.put(name, v ?: JSONObject.NULL)
                Log.i(TAG, "$name: $v")
            }
            val prefs = Prefs(ctx)
            prefs.baseUrl = url
            step("login") {
                if (code != null) { Api(ctx).exchangeCode(url, code); "oauth" }
                else { prefs.longLivedToken = token; "token" }
            }
            if (prefs.refreshToken == null && token != null) prefs.longLivedToken = token
            var snap: Snapshot? = null
            step("fetch") { Refresh.fetch(ctx).also { snap = it }?.entities?.size ?: "FAIL: ${prefs.lastError}" }
            step("scan") {
                val h = Scanner.scan(ctx)
                JSONObject().put("location", h.locationName).put("areas", h.registry.areas.size).put("floors", h.registry.floors.size)
                    .put("devices", h.registry.devices.size).put("entities", h.registry.entities.size).put("favorites", h.favorites.size)
                    .put("suggested", h.suggested.size).put("energy_stats", h.energy.todayStats.size).put("states", h.states.size)
            }
            val live = snap ?: Refresh.current(ctx)
            val home = HomeStore.home(ctx, live)
            step("plans") {
                val plans = Planner.plan(home)
                val arr = JSONArray()
                for ((i, p) in plans.withIndex()) {
                    val entry = HomeSetupActivity.entryFor(p.kind)
                    if (entry == null) { arr.put("${p.kind}: no widget"); continue }
                    val drawn = ArrayList<String>()
                    Painter.trace = drawn
                    val (bmp, spots) = try { entry.widget.preview(ctx, entry.w, entry.h, live, p) } finally { Painter.trace = null }
                    save(bmp, File(dir, "plan_%02d_%s.png".format(i, p.kind)))
                    entry.widget.build(ctx, entry.w, entry.h, live, p)
                    arr.put("${p.kind} \"${p.title}\" (${p.reason}) · ${p.slots.values.sumOf { it.size }} entities · ${spots.size} taps · ink ${ink(bmp)}% · " +
                        drawn.joinToString(" | "))
                }
                arr
            }
            step("catalog") {
                val failed = JSONArray()
                val drawn = JSONObject()
                for (e in Catalog.all) try {
                    val texts = ArrayList<String>()
                    Painter.trace = texts
                    val (bmp, _) = try { e.widget.preview(ctx, e.w, e.h, live) } finally { Painter.trace = null }
                    save(bmp, File(dir, "widget_${e.id}.png"))
                    drawn.put(e.id, texts.joinToString(" | "))
                } catch (t: Throwable) { Log.e(TAG, e.id, t); failed.put("${e.id}: $t") }
                if (failed.length() == 0) drawn else failed
            }
            step("picker") {
                val slot = Kinds.DEVICES.slots.first()
                val items = EntityPicker.items(home, slot, "", System.currentTimeMillis())
                "${items.size} rows; " + items.take(8).joinToString(" | ") { listOf(it.title, it.subtitle, it.stateLabel).filter { s -> s.isNotEmpty() }.joinToString(" · ") }
            }
            val api = Api(ctx)
            val now = java.time.OffsetDateTime.now()
            step("toggle") {
                val light = live.entities.values.firstOrNull { it.domain == "light" && it.available } ?: return@step "no light"
                val before = light.state
                api.callService("light", "toggle", JSONObject().put("entity_id", light.id))
                delay(1500)
                val after = api.state(light.id)?.optString("state")
                "${light.id}: $before → $after" + if (before == after) " (FAIL: unchanged)" else ""
            }
            step("forecast") {
                val w = live.entities.keys.firstOrNull { it.startsWith("weather.") } ?: return@step "no weather"
                val r = api.callServiceResponse("weather", "get_forecasts", JSONObject().put("entity_id", w).put("type", "daily"))
                "$w: ${r?.optJSONObject(w)?.optJSONArray("forecast")?.length() ?: "FAIL: $r"} days"
            }
            step("history") {
                val id = live.entities.values.firstOrNull { it.attrs.optString("device_class") == "temperature" }?.id ?: return@step "no sensor"
                val arr = api.history(listOf(id), now.minusHours(24).toString())
                "$id: ${arr?.optJSONArray(0)?.length() ?: "FAIL"} points"
            }
            step("calendar") {
                val id = live.entities.keys.firstOrNull { it.startsWith("calendar.") } ?: return@step "no calendar"
                val arr = api.calendarEvents(id, now.toString(), now.plusDays(14).toString())
                "$id: ${arr?.length() ?: "FAIL"} events" + (arr?.optJSONObject(0)?.let { " · " + it.optString("summary") } ?: "")
            }
            step("energy") { Scanner.energyToday(ctx, home.energy).entries.joinToString { "${it.key}=${"%.2f".format(it.value)}" }.ifEmpty { "no statistics" } }
            step("dashboards") { Scanner.dashboardPlans(ctx, home).joinToString { it.kind + ":" + it.title }.ifEmpty { "none" } }
            step("refresh_all") { Refresh.all(ctx); prefs.lastError ?: "ok" }
            File(dir, "report.json").writeText(report.toString(2))
            // also in private storage: CI reads it with `run-as`, which does not lag like /sdcard can
            File(filesDir, "e2e-report.json").writeText(report.toString(2))
            Log.i(TAG, "E2E-DONE")
            runOnUiThread { out.text = report.toString(2); finish() }
        }.start()
    }

    /** Share of pixels that differ from the card background, to catch blank widgets. */
    private fun ink(bmp: Bitmap): Int {
        val bg = bmp.getPixel(bmp.width / 2, 2)
        var n = 0; var total = 0
        for (y in 0 until bmp.height step 4) for (x in 0 until bmp.width step 4) { total++; if (bmp.getPixel(x, y) != bg) n++ }
        return if (total == 0) 0 else n * 100 / total
    }

    private fun save(bmp: Bitmap, f: File) = f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

    companion object { private const val TAG = "HomebaseE2E" }
}
