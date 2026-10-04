package in_.weenja.hawidgets

import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import in_.weenja.hawidgets.core.Home
import in_.weenja.hawidgets.core.Kinds
import in_.weenja.hawidgets.core.Planner
import in_.weenja.hawidgets.core.SlotSpec
import in_.weenja.hawidgets.core.WidgetSpec
import in_.weenja.hawidgets.ha.Snapshot
import in_.weenja.hawidgets.ui.V
import in_.weenja.hawidgets.widgets.Catalog
import in_.weenja.hawidgets.widgets.Refresh

/**
 * One widget's settings, opened from the widget ("reconfigure" on long-press, or "tap to fix"), from
 * "Your widgets" in the app, or by the launcher. The real widget bitmap sits on top and redraws after
 * every pick. Saving stores the config under the widget's id; the global defaults stay the fallback.
 */
class ConfigureActivity : AppCompatActivity() {
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var entry: Catalog.Entry
    private lateinit var spec: WidgetSpec
    private lateinit var snap: Snapshot
    private lateinit var home: Home
    private lateinit var preview: ImageView
    private lateinit var slotsBox: LinearLayout
    private var wDp = 320f
    private var hDp = 190f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        val mgr = AppWidgetManager.getInstance(this)
        val info = if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) mgr.getAppWidgetInfo(widgetId) else null
        entry = info?.provider?.className?.let { Catalog.byClassName(it) } ?: intent.getStringExtra("entry")?.let { Catalog.byId(it) } ?: run { finish(); return }
        snap = Refresh.current(this)
        home = HomeStore.home(this, snap)
        spec = WidgetStore.get(this, widgetId) ?: entry.widget.specFor(this, widgetId, snap) ?: WidgetSpec(entry.kind)
        // the widget's current size, else its default
        mgr.getAppWidgetOptions(widgetId)?.let { o ->
            val w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0); val h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)
            if (w > 40 && h > 40) { wDp = w.toFloat(); hDp = h.toFloat() } else { wDp = entry.w; hDp = entry.h }
        } ?: run { wDp = entry.w; hDp = entry.h }
        val maxW = resources.displayMetrics.widthPixels / resources.displayMetrics.density - 32f
        if (wDp > maxW) { hDp = hDp * maxW / wDp; wDp = maxW }

        val col = V.page(this, entry.title, "This widget's settings. Anything left open follows Home Assistant or the app's defaults.")
        preview = ImageView(this)
        col.addView(preview, LinearLayout.LayoutParams(V.dp(this, wDp), V.dp(this, hDp)).apply { topMargin = V.dp(this@ConfigureActivity, 16f); gravity = android.view.Gravity.CENTER_HORIZONTAL })

        val title = V.field(this, "Title (optional)", spec.title)
        title.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { spec = spec.with(title = s?.toString()?.trim() ?: "", auto = false); redraw() }
        })
        col.addView(V.label(this, "Title"))
        col.addView(title)

        slotsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(slotsBox)
        buildSlots()

        col.addView(V.row(this,
            V.button(this, "Save", V.Style.PRIMARY) { save() },
            V.button(this, "Fill from Home Assistant", V.Style.SECONDARY) {
                (Planner.fill(entry.kind, home, 12, emptySet()) ?: WidgetSpec(entry.kind)).let { spec = it; buildSlots(); title.setText(it.title) }
            }, top = 22f))
        col.addView(V.button(this, "Reset to automatic", V.Style.GHOST) { spec = WidgetSpec(entry.kind, auto = true); title.setText(""); buildSlots() }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = V.dp(this@ConfigureActivity, 8f) }
        })
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) col.addView(V.muted(this, "Preview only: add the widget to your home screen to keep these settings."))
        redraw()
    }

    private fun redraw() {
        val (bmp, _) = entry.widget.preview(this, wDp, hDp, snap, spec, widgetId)
        preview.setImageBitmap(bmp)
    }

    private fun names(ids: List<String>): String = ids.joinToString(", ") { home[it]?.name ?: it }

    /** The slot rows of this kind, the Room chooser, Favorites' source and the per-widget overrides. */
    private fun buildSlots() {
        slotsBox.removeAllViews()
        val kind = Kinds.get(entry.kind)
        if (entry.kind == "room") {
            val area = spec.option("area")?.let { home.registry.area(it) }
            slotsBox.addView(V.label(this, "Room"))
            val c = V.card(this, slotsBox, 14f, 6f)
            c.addView(V.body(this, area?.name ?: "Not chosen"))
            c.addView(V.button(this, "Choose a room", V.Style.SECONDARY) { chooseRoom() }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = V.dp(this@ConfigureActivity, 8f) } })
            if (home.registry.areas.isEmpty()) c.addView(V.muted(this, "Rooms come from Home Assistant's areas. Scan your home in the app first."))
        }
        if (entry.kind == "favorites") {
            val follows = spec.option("source") != "custom" && spec.list("items").isEmpty()
            slotsBox.addView(V.label(this, "Source"))
            val c = V.card(this, slotsBox, 14f, 6f)
            c.addView(V.body(this, if (follows) "Follows the favorites you star in Home Assistant (${home.favorites.size} now)." else "Your own list below."))
            c.addView(V.button(this, if (follows) "Pick my own entities" else "Follow Home Assistant favorites", V.Style.SECONDARY) {
                spec = if (follows) spec.withOption("source", "custom").withSlot("items", home.favorites) else spec.withSlot("items", emptyList()).withOption("source", "ha")
                buildSlots()
            }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = V.dp(this@ConfigureActivity, 8f) } })
        }
        kind?.slots?.forEachIndexed { i, slot ->
            if (entry.kind == "favorites" && spec.option("source") != "custom" && spec.list("items").isEmpty()) return@forEachIndexed
            val chosen = spec.list(slot.key)
            slotsBox.addView(V.label(this, slot.label))
            val c = V.card(this, slotsBox, 14f, 6f)
            val auto = autoFor(slot)
            c.addView(V.body(this, if (chosen.isNotEmpty()) names(chosen) else if (auto.isNotEmpty()) "Automatic: ${names(auto)}" else "Automatic"))
            if (slot.hint.isNotEmpty()) c.addView(V.muted(this, slot.hint))
            val missing = chosen.filter { home[it] == null && !snap.demo }
            if (missing.isNotEmpty()) c.addView(V.text(this, "Not in Home Assistant any more: ${missing.joinToString()}", 12.5f, 600, V.PINK))
            c.addView(V.row(this,
                V.button(this, if (chosen.isEmpty()) "Choose" else "Change", V.Style.SECONDARY) { startActivityForResult(PickerActivity.intent(this, slot, chosen), REQ_SLOT + i) },
                V.button(this, "Automatic", V.Style.GHOST) { spec = spec.withSlot(slot.key, emptyList()); buildSlots() }))
        }
        // global keys this widget reads, overridable for this widget only
        val keys = entry.configGroups.mapNotNull { Config.group(it) }.flatMap { it.keys }
        if (keys.isNotEmpty()) {
            slotsBox.addView(V.h2(this, "Advanced"))
            slotsBox.addView(V.muted(this, "Defaults come from Settings → Defaults. Anything set here is for this widget only."))
            for (k in keys) {
                slotsBox.addView(V.label(this, k.label))
                val cur = spec.option(k.id) ?: ""
                if (k.type == Config.Type.ENTITY || k.type == Config.Type.ENTITIES) {
                    val c = V.card(this, slotsBox, 12f, 6f)
                    val global = Config.get(this, k.id)
                    c.addView(V.body(this, if (cur.isNotEmpty()) names(cur.split(',').map { it.trim() }) else if (global.isNotEmpty()) "Default: ${names(global.split(',').map { it.trim() })}" else "Automatic"))
                    val slot = SlotSpec(k.id, k.label, k.domains, multiple = k.type == Config.Type.ENTITIES, max = 12)
                    c.addView(V.row(this,
                        V.button(this, "Choose", V.Style.SECONDARY) { startActivityForResult(PickerActivity.intent(this, slot, cur.split(',').map { it.trim() }.filter { it.isNotEmpty() }), REQ_KEY + keys.indexOf(k)) },
                        V.button(this, "Default", V.Style.GHOST) { spec = spec.withOption(k.id, null); buildSlots() }))
                } else {
                    val f = V.field(this, Config.get(this, k.id).ifEmpty { k.hint.ifEmpty { "optional" } }, cur)
                    f.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(s: Editable?) { spec = spec.withOption(k.id, s?.toString()?.trim()); redraw() }
                    })
                    slotsBox.addView(f)
                    if (k.hint.isNotEmpty()) slotsBox.addView(V.muted(this, k.hint, 11.5f))
                }
            }
        }
        redraw()
    }

    /** What the widget would show in a slot if it is left automatic. */
    private fun autoFor(slot: SlotSpec): List<String> = (Planner.fill(entry.kind, home, 8, emptySet())?.list(slot.key) ?: emptyList()).let {
        if (entry.kind == "favorites") home.favorites else it
    }

    private fun chooseRoom() {
        val rooms = Planner.rooms(home)
        val areas = home.registry.areas.sortedBy { it.name.lowercase() }
        if (areas.isEmpty()) return
        AlertDialog.Builder(this).setTitle("Room").setItems(areas.map { it.name }.toTypedArray()) { _, which ->
            val a = areas[which]
            spec = rooms.firstOrNull { it.option("area") == a.id }?.with(auto = false) ?: WidgetSpec("room", a.name, emptyMap(), mapOf("area" to a.id))
            buildSlots()
        }.show()
    }

    @Deprecated("platform API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        val ids = data.getStringArrayExtra(PickerActivity.EXTRA_SELECTED)?.toList() ?: return
        val key = data.getStringExtra(PickerActivity.EXTRA_SLOT) ?: return
        spec = if (requestCode >= REQ_KEY) spec.withOption(key, ids.joinToString(",").ifEmpty { null }) else spec.withSlot(key, ids)
        if (entry.kind == "favorites" && key == "items" && ids.isNotEmpty()) spec = spec.withOption("source", "custom")
        buildSlots()
    }

    private fun save() {
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            WidgetStore.put(this, widgetId, spec.with(auto = false))
            Refresh.drawOne(this, widgetId)
            Refresh.enqueueNow(this, 500)
        }
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }

    companion object {
        private const val REQ_SLOT = 100
        private const val REQ_KEY = 500
    }
}
