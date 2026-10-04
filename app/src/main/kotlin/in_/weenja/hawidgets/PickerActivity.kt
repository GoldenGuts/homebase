package in_.weenja.hawidgets

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import in_.weenja.hawidgets.core.Accent
import in_.weenja.hawidgets.core.EntityState
import in_.weenja.hawidgets.core.Home
import in_.weenja.hawidgets.core.Json
import in_.weenja.hawidgets.core.EntityPicker
import in_.weenja.hawidgets.core.SlotSpec
import in_.weenja.hawidgets.ha.Api
import in_.weenja.hawidgets.ui.Icons
import in_.weenja.hawidgets.ui.V
import in_.weenja.hawidgets.widgets.Refresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The entity picker: every entity a slot accepts, grouped by floor, area and device, with its live
 * state ("Kitchen ceiling · on · 60%") and a search box. One tap picks (single slots); multi slots keep
 * the tap order, which is the order the widget shows them in.
 */
class PickerActivity : AppCompatActivity() {
    private lateinit var slot: SlotSpec
    private var home: Home? = null
    private val selected = ArrayList<String>()
    private var items: List<EntityPicker.Item> = emptyList()
    private var query = ""
    private lateinit var adapter: Adapter
    private lateinit var summary: TextView
    private lateinit var done: TextView
    private var includeAll = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        slot = SlotSpec(i.getStringExtra(EXTRA_SLOT) ?: "entity", i.getStringExtra(EXTRA_TITLE) ?: "Entity",
            i.getStringArrayExtra(EXTRA_DOMAINS)?.toSet() ?: emptySet(), i.getStringArrayExtra(EXTRA_CLASSES)?.toSet() ?: emptySet(),
            i.getBooleanExtra(EXTRA_MULTIPLE, false), i.getIntExtra(EXTRA_MAX, 1))
        selected.addAll(i.getStringArrayExtra(EXTRA_SELECTED) ?: emptyArray())

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(V.BG); fitsSystemWindows = true }
        setContentView(root)
        window.statusBarColor = V.BG; window.navigationBarColor = V.BG
        val head = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(V.dp(this@PickerActivity, 16f), V.dp(this@PickerActivity, 14f), V.dp(this@PickerActivity, 16f), 0) }
        root.addView(head)
        head.addView(V.text(this, slot.label, 22f, 800))
        head.addView(V.muted(this, if (slot.multiple) "Tap in the order you want them shown. Up to ${slot.max}." else "Tap one."))
        val search = V.field(this, "Search name, room, device or state")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; rebuild() }
        })
        head.addView(search)
        summary = V.muted(this, "").apply { setPadding(0, V.dp(this@PickerActivity, 8f), 0, V.dp(this@PickerActivity, 4f)) }
        head.addView(summary)

        val list = ListView(this).apply { divider = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT); dividerHeight = V.dp(this@PickerActivity, 6f)
            setBackgroundColor(V.BG); isVerticalScrollBarEnabled = true; clipToPadding = false
            setPadding(V.dp(this@PickerActivity, 10f), 0, V.dp(this@PickerActivity, 10f), V.dp(this@PickerActivity, 12f)) }
        adapter = Adapter(this)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> items.getOrNull(pos)?.entityId?.let { tap(it) } }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(V.dp(this@PickerActivity, 16f), V.dp(this@PickerActivity, 8f), V.dp(this@PickerActivity, 16f), V.dp(this@PickerActivity, 12f)) }
        val all = V.button(this, "Show all entities", V.Style.GHOST) { v -> includeAll = !includeAll; (v as TextView).text = if (includeAll) "Only matching" else "Show all entities"; rebuild() }
        val clear = V.button(this, "Clear", V.Style.SECONDARY) { selected.clear(); rebuild() }
        done = V.button(this, "Done", V.Style.PRIMARY) { finishWith() }
        bar.addView(all, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f))
        bar.addView(clear, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = V.dp(this@PickerActivity, 8f) })
        bar.addView(done, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = V.dp(this@PickerActivity, 8f) })
        root.addView(bar)

        // cached first, then the full live list (the widget cache is trimmed to what widgets read)
        val snap = Refresh.current(this)
        home = HomeStore.home(this, snap)
        rebuild()
        if (!snap.demo && Prefs(this).isLoggedIn) lifecycleScope.launch {
            val full = try { withContext(Dispatchers.IO) { EntityState.parseList(Json.parse(Api(this@PickerActivity).states().toString())) } } catch (e: Exception) { null }
            if (full != null) {
                val h = home!!
                home = Home(full.associateBy { it.id }, h.registry, h.favorites, h.suggested, h.energy, h.locationName, h.scannedAt)
                rebuild()
            }
        }
    }

    private fun tap(id: String) {
        if (!slot.multiple) { selected.clear(); selected.add(id); finishWith(); return }
        if (!selected.remove(id)) { if (selected.size >= slot.max) selected.removeAt(0); selected.add(id) }
        rebuild()
    }

    private fun rebuild() {
        val h = home ?: return
        val filter = if (includeAll) SlotSpec(slot.key, slot.label, emptySet()) else slot
        items = EntityPicker.items(h, filter, query, System.currentTimeMillis(), includeNoise = includeAll)
        adapter.notifyDataSetChanged()
        summary.text = when {
            selected.isEmpty() -> if (slot.multiple) "Nothing picked: the widget fills itself from Home Assistant." else ""
            else -> "Picked: " + selected.mapIndexed { i, id -> "${i + 1}. ${h[id]?.name ?: id}" }.joinToString("  ")
        }
        done.visibility = if (slot.multiple) View.VISIBLE else View.GONE
    }

    private fun finishWith() {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SLOT, slot.key).putExtra(EXTRA_SELECTED, selected.toTypedArray()))
        finish()
    }

    private inner class Adapter(val ctx: Context) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (items[position].kind == EntityPicker.Kind.ENTITY) 1 else 0
        override fun isEnabled(position: Int) = items[position].kind == EntityPicker.Kind.ENTITY

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val it = items[position]
            return if (it.kind == EntityPicker.Kind.ENTITY) entityRow(it, convertView as? LinearLayout) else header(it, convertView as? TextView)
        }

        private fun header(it: EntityPicker.Item, reuse: TextView?): View {
            val tv = reuse ?: V.text(ctx, "", 12f, 700, V.MUTED)
            val floor = it.kind == EntityPicker.Kind.FLOOR
            tv.text = if (floor) it.title.uppercase() else if (it.kind == EntityPicker.Kind.DEVICE) "${it.title}${if (it.subtitle.isNotEmpty()) "  ·  ${it.subtitle}" else ""}" else it.title
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (floor) 11.5f else if (it.kind == EntityPicker.Kind.AREA) 15f else 12.5f)
            tv.setTextColor(if (it.kind == EntityPicker.Kind.AREA) V.INK else V.MUTED)
            tv.letterSpacing = if (floor) .1f else 0f
            tv.setPadding(V.dp(ctx, 6f + it.depth * 10f), V.dp(ctx, if (floor) 18f else 12f), V.dp(ctx, 6f), V.dp(ctx, 4f))
            return tv
        }

        private fun entityRow(it: EntityPicker.Item, reuse: LinearLayout?): View {
            val row = reuse ?: LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = V.rounded(V.CARD, V.dp(ctx, 16f).toFloat())
                addView(ImageView(ctx), LinearLayout.LayoutParams(V.dp(ctx, 36f), V.dp(ctx, 36f)))
                val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                col.addView(V.text(ctx, "", 14.5f, 600).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                col.addView(V.muted(ctx, "", 12f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = V.dp(ctx, 12f) })
                addView(V.text(ctx, "", 13f, 700, V.CYAN).apply { gravity = Gravity.END; setPadding(V.dp(ctx, 8f), 0, 0, 0) })
            }
            row.layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            row.setPadding(V.dp(ctx, 10f + it.depth * 8f), V.dp(ctx, 9f), V.dp(ctx, 12f), V.dp(ctx, 9f))
            val color = when (it.accent) { Accent.ORANGE -> V.ORANGE; Accent.CYAN -> V.CYAN; Accent.PINK -> V.PINK; Accent.LIME -> V.LIME; Accent.PURPLE -> V.PURPLE; Accent.MUTED -> V.MUTED }
            val icon = row.getChildAt(0) as ImageView
            icon.setImageResource(Icons.of(it.icon))
            icon.imageTintList = ColorStateList.valueOf(if (it.active) color else V.MUTED)
            icon.background = V.rounded(if (it.active) (color and 0x00ffffff) or 0x2a000000 else V.SURFACE, V.dp(ctx, 12f).toFloat())
            icon.setPadding(V.dp(ctx, 7f), V.dp(ctx, 7f), V.dp(ctx, 7f), V.dp(ctx, 7f))
            val col = row.getChildAt(1) as LinearLayout
            (col.getChildAt(0) as TextView).text = "${it.title} · ${it.stateLabel}"
            (col.getChildAt(1) as TextView).text = it.subtitle
            val idx = selected.indexOf(it.entityId)
            val mark = row.getChildAt(2) as TextView
            mark.text = if (idx < 0) "" else if (slot.multiple) "✓ ${idx + 1}" else "✓"
            row.background = V.rounded(if (idx >= 0) 0xff1f2a3a.toInt() else V.CARD, V.dp(ctx, 16f).toFloat(), if (idx >= 0) V.CYAN else 0, if (idx >= 0) V.dp(ctx, 1f) else 0)
            return row
        }
    }

    companion object {
        const val EXTRA_SLOT = "slot"
        const val EXTRA_TITLE = "title"
        const val EXTRA_DOMAINS = "domains"
        const val EXTRA_CLASSES = "classes"
        const val EXTRA_MULTIPLE = "multiple"
        const val EXTRA_MAX = "max"
        const val EXTRA_SELECTED = "selected"

        fun intent(ctx: Context, slot: SlotSpec, selected: List<String>): Intent = Intent(ctx, PickerActivity::class.java)
            .putExtra(EXTRA_SLOT, slot.key).putExtra(EXTRA_TITLE, slot.label).putExtra(EXTRA_DOMAINS, slot.domains.toTypedArray())
            .putExtra(EXTRA_CLASSES, slot.deviceClasses.toTypedArray()).putExtra(EXTRA_MULTIPLE, slot.multiple).putExtra(EXTRA_MAX, slot.max)
            .putExtra(EXTRA_SELECTED, selected.toTypedArray())
    }
}
