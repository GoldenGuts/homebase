package in_.weenja.hawidgets

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import in_.weenja.hawidgets.core.Kinds
import in_.weenja.hawidgets.core.Planner
import in_.weenja.hawidgets.core.Time
import in_.weenja.hawidgets.ha.Demo
import in_.weenja.hawidgets.ha.Scanner
import in_.weenja.hawidgets.ui.V
import in_.weenja.hawidgets.widgets.Catalog
import in_.weenja.hawidgets.widgets.Hotspot
import in_.weenja.hawidgets.widgets.Refresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Home of the app: connection status, "For your home", the widgets on the home screen (each with its
 * settings), a live gallery to add more, and the Extras. While it is open it follows Home Assistant's
 * registries, so renamed or removed entities are flagged at once.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var col: LinearLayout
    private var watchJob: Job? = null
    private var renderJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Config.migrate(this)
        Extras.decide(this)
        col = V.page(this, "Homebase", "Widgets for Home Assistant")
        if (intent?.getBooleanExtra("dump", false) == true) dumpPreviews(intent.getStringExtra("sizes"), intent.getStringExtra("only"))
        else if (savedInstanceState == null && !prefs.onboarded) startActivity(Intent(this, ConnectActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        render()
        if (prefs.isLoggedIn && System.currentTimeMillis() - prefs.lastFetchAt > 60_000) refresh()
    }

    override fun onStart() {
        super.onStart()
        if (!prefs.isLoggedIn) return
        // follow registry / dashboard changes while the app is open; rescan after a burst of events
        watchJob = lifecycleScope.launch {
            var last = 0L
            try {
                Scanner.watch(this@MainActivity) {
                    if (System.currentTimeMillis() - last < 10_000) return@watch
                    last = System.currentTimeMillis()
                    delay(3_000)
                    try { Scanner.scan(this@MainActivity); Refresh.all(this@MainActivity, fetch = true) } catch (_: Exception) {}
                    withContext(Dispatchers.Main) { render() }
                }
            } catch (_: Exception) {}
        }
        // a daily scan if the worker has not done one
        if (System.currentTimeMillis() - prefs.lastScanAt > 24 * 3_600_000L) lifecycleScope.launch {
            try { Scanner.scan(this@MainActivity); Refresh.updatePreviews(this@MainActivity); render() } catch (_: Exception) {}
        }
    }

    override fun onStop() { watchJob?.cancel(); watchJob = null; super.onStop() }

    private fun refresh() {
        lifecycleScope.launch { Refresh.all(this@MainActivity, fetch = true); render() }
    }

    private fun render() {
        renderJob?.cancel()
        while (col.childCount > 2) col.removeViewAt(2)
        val snap = Refresh.current(this)
        val now = System.currentTimeMillis()

        // ---- connection
        val c = V.card(this, col, 16f, 16f)
        if (!prefs.isLoggedIn) {
            c.addView(V.text(this, "Connect to your Home Assistant", 17f, 700))
            c.addView(V.muted(this, "Homebase finds it on your network, you log in once, and it suggests widgets for your rooms, favorites, energy and security. The previews below show a demo home until then."))
            c.addView(V.button(this, "Connect", V.Style.PRIMARY) { startActivity(Intent(this, ConnectActivity::class.java).putExtra(ConnectActivity.SKIP_WELCOME, true)) }.full(12f))
        } else {
            val err = prefs.lastError
            c.addView(V.text(this, prefs.serverName.ifBlank { prefs.baseUrl.removePrefix("https://").removePrefix("http://") }, 17f, 700))
            c.addView(V.muted(this, "${snap.entities.size} entities · updated ${if (snap.fetchedAt > 0) Time.ago(snap.fetchedAt, now) else "never"}" +
                (if (prefs.lastScanAt > 0) " · scanned ${Time.ago(prefs.lastScanAt, now)}" else "")))
            if (err != null) c.addView(V.text(this, "Last update failed: $err", 13f, 600, V.PINK))
            c.addView(V.row(this,
                V.button(this, "Refresh", V.Style.SECONDARY, R.drawable.ic_refresh) { refresh() },
                V.button(this, "For your home", V.Style.PRIMARY, R.drawable.ic_auto_fix) { startActivity(Intent(this, HomeSetupActivity::class.java)) }))
        }

        // ---- the widgets on the home screen
        val placed = Refresh.placed(this)
        col.addView(V.h2(this, "On your home screen"))
        if (placed.isEmpty()) col.addView(V.muted(this, "No Homebase widgets yet. Add one below, or open For your home."))
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(listBox)
        for ((id, entry, spec) in placed) {
            val row = V.card(this, listBox, 12f, 8f)
            val missing = spec?.entityIds?.filter { snap[it] == null && !snap.demo } ?: emptyList()
            row.addView(V.text(this, spec?.title?.takeIf { it.isNotBlank() }?.let { "${entry.title} · $it" } ?: entry.title, 15f, 700))
            row.addView(V.muted(this, when {
                missing.isNotEmpty() -> ""
                spec == null -> "Uses the defaults"
                spec.auto -> "Filled automatically from Home Assistant"
                else -> spec.entityIds.take(4).joinToString(", ") { snap[it]?.name ?: it }.ifEmpty { "Your settings" }
            }))
            if (missing.isNotEmpty()) row.addView(V.text(this, "Not in Home Assistant any more: ${missing.joinToString()}", 12.5f, 600, V.PINK))
            row.addView(V.button(this, "Edit", V.Style.SECONDARY, R.drawable.ic_pencil) {
                startActivity(Intent(this, ConfigureActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            }.full(8f))
        }

        // ---- Homebase Pro
        if (prefs.isLoggedIn && !Pro.unlocked(this)) {
            val pro = V.card(this, col, 16f, 18f)
            pro.addView(V.text(this, "Favorites, Tile and Weather · small are free", 15.5f, 700))
            pro.addView(V.muted(this, "Unlock every widget and size with one purchase: rooms, energy, security, now playing and the rest.", 13f).apply { setPadding(0, V.dp(this@MainActivity, 4f), 0, 0) })
            pro.addView(V.button(this, "Homebase Pro", V.Style.PRIMARY, R.drawable.ic_star_four_points) { startActivity(Intent(this, ProActivity::class.java)) }.full(12f))
        }

        // ---- gallery
        col.addView(V.h2(this, "Add a widget"))
        col.addView(V.muted(this, "Every widget adapts to its size and fills itself from Home Assistant. Tap a preview: it works like the real widget."))
        val gallery = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(gallery)

        // ---- extras
        col.addView(V.h2(this, "Extras"))
        col.addView(V.muted(this, "Widgets that need your own Home Assistant setup: Mac and Claude Code, a gaming PC, money, fuel, screen time and planes overhead. Each comes with a guide and a YAML package."))
        val on = Extras.enabled(this)
        col.addView(V.button(this, if (on) "Hide Extras" else "Show Extras", if (on) V.Style.GHOST else V.Style.SECONDARY) { toggleExtras() }.full(10f))
        val extras = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(extras)
        if (on) {
            val guides = Catalog.guides.map { (k, t) -> V.button(this, t, V.Style.SECONDARY) { startActivity(Intent(this, GuideActivity::class.java).putExtra("kind", k)) } }
            guides.chunked(2).forEach { pair -> extras.addView(V.row(this, *pair.toTypedArray(), top = 8f)) }
        }

        // ---- footer
        Support.feedbackCard(this, col)
        Support.card(this, col)
        col.addView(V.button(this, "Settings", V.Style.SECONDARY, R.drawable.ic_cog) { startActivity(Intent(this, SettingsActivity::class.java)) }.full(26f))
        col.addView(V.muted(this, "Homebase ${BuildConfig.VERSION_NAME} · ${Refresh.widgetCount(this)} widget${if (Refresh.widgetCount(this) == 1) "" else "s"} on the home screen").apply {
            setPadding(0, V.dp(this@MainActivity, 12f), 0, 0)
        })

        // previews render one by one, off the main thread
        val entries = Catalog.core.filter { !it.isTile || it.id == "t_bulb" } + (if (on) Catalog.extras + Catalog.legacy else emptyList())
        renderJob = lifecycleScope.launch {
            val home = HomeStore.home(this@MainActivity, snap)
            val maxW = resources.displayMetrics.widthPixels / resources.displayMetrics.density - 32f
            for (e in entries) {
                val spec = if (e.group == Catalog.Group.CORE) Planner.fill(e.kind, home, 12) else null
                val w = minOf(e.w, maxW); val h = e.h * w / e.w
                val (bmp, spots) = withContext(Dispatchers.Default) { e.widget.preview(this@MainActivity, w, h, snap, spec, scale = minOf(2f, resources.displayMetrics.density)) }
                val target = if (e.group == Catalog.Group.CORE) gallery else extras
                val card = V.card(this@MainActivity, target, 14f, 10f)
                card.addView(V.text(this@MainActivity, e.title, 15f, 700))
                (Kinds.get(e.kind)?.blurb)?.let { card.addView(V.muted(this@MainActivity, it)) }
                val iv = V.preview(this@MainActivity, bmp, w, h)
                attachTaps(iv, spots)
                card.addView(iv)
                card.addView(V.button(this@MainActivity, HomeSetupActivity.addLabel(this@MainActivity, e), V.Style.PRIMARY, R.drawable.ic_plus) { HomeSetupActivity.pin(this@MainActivity, e, spec, snap) }.full(10f))
            }
        }
    }

    private fun toggleExtras() {
        if (!Extras.enabled(this)) { Extras.set(this, true); render(); return }
        val n = Extras.placed(this)
        if (n == 0) { Extras.set(this, false); render(); return }
        AlertDialog.Builder(this).setTitle("Hide Extras?")
            .setMessage("$n Extras widget${if (n == 1) " is" else "s are"} on your home screen. Hiding the Extras removes ${if (n == 1) "it" else "them"}.")
            .setPositiveButton("Hide") { _, _ -> Extras.set(this, false); render() }.setNegativeButton("Cancel", null).show()
    }

    /** Route a tap on a preview to the same PendingIntent the widget zone would fire. */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachTaps(iv: ImageView, spots: List<Hotspot>) {
        iv.setOnTouchListener { v, ev ->
            if (ev.action != MotionEvent.ACTION_UP) return@setOnTouchListener true
            val d = resources.displayMetrics.density
            val x = ev.x / d; val y = ev.y / d
            val hit = spots.lastOrNull { it.rect.contains(x, y) } ?: return@setOnTouchListener true
            if (!prefs.isLoggedIn) { Toast.makeText(this, "Connect first: previews then control your home", Toast.LENGTH_SHORT).show(); return@setOnTouchListener true }
            try { hit.intent.send() } catch (_: PendingIntent.CanceledException) {}
            lifecycleScope.launch { delay(2500); render() }
            true
        }
    }

    private fun View.full(top: Float): View = apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = V.dp(this@MainActivity, top) } }

    /**
     * Dev helper: `adb shell am start -n in.weenja.hawidgets/in_.weenja.hawidgets.MainActivity --ez dump true [--es sizes 340x200,340x420] [--es only room,energy]`
     * writes PNGs of every widget with demo data to Android/data/<pkg>/files/previews. Without sizes: the widget-picker previews (copy them to res/drawable-nodpi).
     */
    private fun dumpPreviews(sizeSpec: String?, only: String?) {
        val root = getExternalFilesDir(null) ?: filesDir
        val dir = java.io.File(root, "previews").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val pick = only?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
        val extra = sizeSpec?.split(',')?.mapNotNull { s -> s.trim().split('x').takeIf { it.size == 2 }?.let { it[0].toFloatOrNull()?.let { w -> it[1].toFloatOrNull()?.let { h -> w to h } } } } ?: emptyList()
        val snap = Demo.snapshot().clean()
        for (e in Catalog.all) {
            if (pick != null && e.id !in pick) continue
            val list = if (extra.isEmpty()) listOf(e.w to e.h) else extra.filter { (ew, _) -> (ew < 200f) == (e.w < 200f) }
            for ((w, h) in list) {
                val (bmp, _) = e.widget.preview(this, w, h, snap)
                val file = (if (extra.isEmpty()) "preview_${e.id}" else "preview_${e.id}_${w.toInt()}x${h.toInt()}") + ".png"
                java.io.File(dir, file).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        Toast.makeText(this, "previews written", Toast.LENGTH_SHORT).show()
    }
}
