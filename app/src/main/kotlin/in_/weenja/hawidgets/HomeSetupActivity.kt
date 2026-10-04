package in_.weenja.hawidgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import in_.weenja.hawidgets.core.Home
import in_.weenja.hawidgets.core.Planner
import in_.weenja.hawidgets.core.WidgetSpec
import in_.weenja.hawidgets.ha.Scanner
import in_.weenja.hawidgets.ha.Snapshot
import in_.weenja.hawidgets.ui.V
import in_.weenja.hawidgets.widgets.ActionReceiver
import in_.weenja.hawidgets.widgets.Catalog
import in_.weenja.hawidgets.widgets.Refresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "For your home": widgets auto-setup built from the scan (one per room, Energy, Security, Favorites…),
 * each with a live preview and an Add button. Android confirms every widget on its own; there is no
 * batch API. The plan rides in the pin callback and is saved when the launcher reports the new id.
 */
class HomeSetupActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var dash: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = V.page(this, "For your home", "Widgets built from your Home Assistant: your rooms, favorites, energy and more. Add the ones you like; Android asks once per widget.")
        status = V.text(this, "", 13.5f, 600, V.CYAN).apply { setPadding(0, V.dp(this@HomeSetupActivity, 10f), 0, 0) }
        col.addView(status)
        col.addView(V.row(this,
            V.button(this, "Scan again", V.Style.SECONDARY, in_.weenja.hawidgets.R.drawable.ic_refresh) { scan() },
            V.button(this, "Done", V.Style.PRIMARY) { finish() }))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list)
        col.addView(V.h2(this, "From your dashboards"))
        col.addView(V.muted(this, "Turns the cards of your Home Assistant dashboards into widgets: tiles and entity lists become grids, thermostats rooms, and so on."))
        dash = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(dash)
        col.addView(V.button(this, "Import from my dashboards", V.Style.SECONDARY) { importDashboards() }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = V.dp(this@HomeSetupActivity, 10f) }
        })
        if (!HomeStore.hasScan(this) && Prefs(this).isLoggedIn) scan() else show()
    }

    private fun home(): Pair<Snapshot, Home> { val s = Refresh.current(this); return s to HomeStore.home(this, s) }

    private fun scan() {
        status.text = "Scanning your home…"
        lifecycleScope.launch {
            try { Scanner.scan(this@HomeSetupActivity); Refresh.fetch(this@HomeSetupActivity); status.text = "" }
            catch (e: Exception) { status.setTextColor(V.PINK); status.text = "Scan failed: ${e.message}" }
            show()
        }
    }

    private fun show() {
        val (snap, home) = home()
        val plans = Planner.plan(home)
        list.removeAllViews()
        if (snap.demo) status.text = "Showing the demo home. Connect to see your own."
        if (plans.isEmpty()) list.addView(V.muted(this, "Nothing to suggest yet. Once Home Assistant has areas, favorites or devices, suggestions appear here."))
        render(list, plans, snap)
    }

    private fun render(into: LinearLayout, plans: List<WidgetSpec>, snap: Snapshot) {
        lifecycleScope.launch {
            val maxW = resources.displayMetrics.widthPixels / resources.displayMetrics.density - 64f
            for (plan in plans) {
                val entry = entryFor(plan.kind) ?: continue
                val w = minOf(entry.w, maxW); val h = entry.h * w / entry.w
                val bmp = withContext(Dispatchers.Default) { entry.widget.preview(this@HomeSetupActivity, w, h, snap, plan, scale = minOf(2f, resources.displayMetrics.density)).first }
                val c = V.card(this@HomeSetupActivity, into, 14f, 12f)
                c.addView(V.text(this@HomeSetupActivity, plan.title.ifBlank { entry.title }, 16f, 700))
                if (plan.reason.isNotBlank()) c.addView(V.muted(this@HomeSetupActivity, plan.reason))
                c.addView(V.preview(this@HomeSetupActivity, bmp, w, h))
                c.addView(V.button(this@HomeSetupActivity, addLabel(this@HomeSetupActivity, entry), V.Style.PRIMARY) { pin(this@HomeSetupActivity, entry, plan, snap) }.apply {
                    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = V.dp(this@HomeSetupActivity, 10f) }
                })
            }
        }
    }

    private fun importDashboards() {
        dash.removeAllViews()
        dash.addView(V.muted(this, "Reading your dashboards…"))
        lifecycleScope.launch {
            val (snap, home) = home()
            val plans = try { Scanner.dashboardPlans(this@HomeSetupActivity, home) } catch (e: Exception) {
                dash.removeAllViews(); dash.addView(V.text(this@HomeSetupActivity, "Could not read the dashboards: ${e.message}", 13f, 500, V.PINK)); return@launch
            }
            dash.removeAllViews()
            if (plans.isEmpty()) dash.addView(V.muted(this@HomeSetupActivity, "No cards that map onto a widget. Auto-generated dashboards have nothing to import."))
            render(dash, plans.take(20), snap)
        }
    }

    companion object {
        /** The provider that shows a kind best. */
        fun entryFor(kind: String): Catalog.Entry? = Catalog.byId(when (kind) { "now" -> "now_bar"; "remote" -> "tvremote"; "tile" -> "t_bulb"; else -> kind })

        /** Ask the launcher to add [entry] with [spec]; the callback stores the spec for the new id. */
        /** "Add to home screen", or the Pro unlock for widgets a free install does not draw. */
        fun addLabel(ctx: Context, entry: Catalog.Entry) = if (Pro.allowed(ctx, entry.id)) "Add to home screen" else "Unlock with Pro to add"

        fun pin(ctx: Context, entry: Catalog.Entry, spec: WidgetSpec?, snap: Snapshot? = null) {
            if (!Pro.allowed(ctx, entry.id)) { ctx.startActivity(Intent(ctx, ProActivity::class.java)); return }
            val mgr = AppWidgetManager.getInstance(ctx)
            if (!mgr.isRequestPinAppWidgetSupported) {
                Toast.makeText(ctx, "Your launcher cannot add widgets from apps. Long-press the home screen → Widgets → Homebase.", Toast.LENGTH_LONG).show(); return
            }
            if (entry.group != Catalog.Group.CORE && !Extras.enabled(ctx)) Extras.set(ctx, true)
            val cb = spec?.let {
                val i = Intent(ActionReceiver.ACT_PINNED).setClass(ctx, ActionReceiver::class.java).putExtra("spec", it.toString())
                    .setData(android.net.Uri.parse("hawidgets://pinned/${it.toString().hashCode()}"))
                PendingIntent.getBroadcast(ctx, it.toString().hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            }
            val extras = android.os.Bundle()
            try { extras.putParcelable(AppWidgetManager.EXTRA_APPWIDGET_PREVIEW, entry.widget.build(ctx, entry.w, entry.h, snap ?: Refresh.current(ctx), spec)) } catch (_: Exception) {}
            if (!mgr.requestPinAppWidget(ComponentName(ctx, entry.cls), extras, cb))
                Toast.makeText(ctx, "The launcher said no. Long-press the home screen → Widgets → Homebase.", Toast.LENGTH_LONG).show()
        }
    }
}
