package in_.weenja.hawidgets

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import in_.weenja.hawidgets.core.Planner
import in_.weenja.hawidgets.ha.Api
import in_.weenja.hawidgets.ha.Discovery
import in_.weenja.hawidgets.ha.Scanner
import in_.weenja.hawidgets.ui.V
import in_.weenja.hawidgets.widgets.Refresh
import kotlinx.coroutines.launch

/**
 * Onboarding, one step at a time: Welcome → Connect (servers found on the network, or the address,
 * or a long-lived token) → Home Assistant's own login page → Setting up (a live checklist of what the
 * scan found) → For your home. `skipWelcome` opens straight at Connect (the app's Connect button).
 */
class ConnectActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var col: LinearLayout
    private var found: LinearLayout? = null
    private var searching: TextView? = null
    private var discovery: Discovery? = null
    private val seen = HashSet<String>()
    private var step = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(V.BG); fitsSystemWindows = true; isFillViewport = true }
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22f), dp(20f), dp(22f), dp(28f)) }
        scroll.addView(col)
        setContentView(scroll)
        window.statusBarColor = V.BG; window.navigationBarColor = V.BG
        if (intent.getBooleanExtra(SKIP_WELCOME, false) || prefs.onboarded) connectStep() else welcomeStep()
        // back (button or gesture) steps back through onboarding before leaving it
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (step == 1 && !prefs.onboarded && !intent.getBooleanExtra(SKIP_WELCOME, false)) welcomeStep()
                else if (step == 2) connectStep()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
    }

    override fun onStart() {
        super.onStart()
        discovery = Discovery(this) { s -> runOnUiThread { addServer(s) } }.also { it.start() }
        col.postDelayed({ if (seen.isEmpty()) searching?.text = "Nothing found yet. Is this phone on the same Wi-Fi as Home Assistant? You can type its address below." }, 8000)
    }

    override fun onStop() { discovery?.stop(); discovery = null; super.onStop() }


    private fun dp(v: Float) = V.dp(this, v)

    private fun reset(n: Int) {
        step = n
        col.removeAllViews()
        found = null; searching = null
        if (n > 0) col.addView(dots(n))
    }

    /** Three pills at the top: the current step is long and cyan. */
    private fun dots(n: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (i in 1..3) addView(View(this@ConnectActivity).apply {
            background = V.rounded(if (i <= n) V.CYAN else V.SURFACE, dp(3f).toFloat())
            alpha = if (i < n) .45f else 1f
        }, LinearLayout.LayoutParams(dp(if (i == n) 28f else 10f), dp(6f)).apply { marginEnd = dp(6f) })
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(22f) }
    }

    private fun title(s: String, sub: String) {
        col.addView(V.text(this, s, 28f, 800))
        col.addView(V.text(this, sub, 14.5f, 400, V.MUTED).apply { setPadding(0, dp(8f), 0, dp(6f)) })
    }

    private fun full(v: View, top: Float = 12f): View = v.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) } }

    // ------------------------------------------------------------------ 0 · welcome

    private fun welcomeStep() {
        reset(0)
        col.addView(V.spacer(this, 28f))
        col.addView(ImageView(this).apply { setImageResource(R.mipmap.ic_launcher) }, LinearLayout.LayoutParams(dp(76f), dp(76f)))
        col.addView(V.spacer(this, 22f))
        title("Your home,\non your home screen", "Homebase turns your Home Assistant into widgets: your rooms, favorites, energy and doors, set up for you.")
        col.addView(V.spacer(this, 14f))
        feature(R.drawable.ic_lan_connect, V.CYAN, "Finds Home Assistant", "on your Wi-Fi. You log in once, on its own page.")
        feature(R.drawable.ic_auto_fix, V.LIME, "Builds your widgets", "from your areas, favorites and energy dashboard.")
        feature(R.drawable.ic_shield_check, V.PINK, "Stays private", "It talks to your server only. No account, no cloud, no tracking.")
        col.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        col.addView(full(V.button(this, "Get started", V.Style.PRIMARY) { connectStep() }, 28f))
        col.addView(full(V.button(this, "Look around with a demo home", V.Style.GHOST) {
            prefs.onboarded = true
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish()
        }, 10f))
    }

    private fun feature(icon: Int, color: Int, head: String, line: String) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10f), 0, dp(10f)) }
        val badge = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = V.rounded((color and 0x00ffffff) or 0x26000000, dp(14f).toFloat())
            addView(V.icon(this@ConnectActivity, icon, color, 22f))
        }
        row.addView(badge, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14f), 0, 0, 0) }
        t.addView(V.text(this, head, 15.5f, 700))
        t.addView(V.text(this, line, 13f, 400, V.MUTED))
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(row)
    }

    // ------------------------------------------------------------------ 1 · connect

    private fun connectStep() {
        reset(1)
        title("Connect", "Pick your Home Assistant. You log in on its own page; Homebase never sees your password.")
        col.addView(V.label(this, "On this Wi-Fi"))
        val search = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10f), 0, dp(4f)) }
        search.addView(V.spinner(this), LinearLayout.LayoutParams(dp(18f), dp(18f)))
        searching = V.muted(this, "Looking for Home Assistant…", 13f).apply { setPadding(dp(10f), 0, 0, 0) }
        search.addView(searching, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(search)
        found = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(found)
        for (s in servers) addServer(s, force = true)

        col.addView(V.label(this, "Or type its address"))
        val url = V.field(this, "192.168.1.10:8123 or home.example.com", prefs.baseUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        col.addView(url)
        col.addView(full(V.button(this, "Continue", V.Style.PRIMARY) {
            val base = normalize(url.text.toString())
            if (base.isEmpty()) { url.error = "Enter the address you open Home Assistant with"; return@button }
            prefs.baseUrl = base
            login(base)
        }, 10f))

        // the token path is for people who cannot use the login page (a proxy in front, SSO)
        val more = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        col.addView(full(V.button(this, "Other ways to connect", V.Style.GHOST, R.drawable.ic_key_variant) { v ->
            more.visibility = View.VISIBLE; v.visibility = View.GONE
        }, 18f))
        col.addView(more)
        more.addView(V.label(this, "Second address (optional)"))
        more.addView(V.muted(this, "Used when the first one does not answer, e.g. your LAN address next to a remote one."))
        val alt = V.field(this, "http://192.168.1.10:8123", prefs.altUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        more.addView(alt)
        more.addView(V.label(this, "Long-lived token"))
        more.addView(V.muted(this, "In Home Assistant: your profile → Security → Long-lived access tokens → Create token. Scan its QR code with the camera app and copy it, then paste it here with the address above."))
        val token = V.field(this, "Paste the token", prefs.longLivedToken ?: "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        more.addView(token)
        more.addView(V.row(this,
            V.button(this, "Paste", V.Style.SECONDARY) {
                val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
                if (clip.isNullOrEmpty()) Toast.makeText(this, "The clipboard is empty", Toast.LENGTH_SHORT).show() else token.setText(clip)
            },
            V.button(this, "Use token", V.Style.PRIMARY) {
                val base = normalize(url.text.toString())
                val t = token.text.toString().trim()
                if (base.isEmpty()) { url.error = "Enter the address too"; return@button }
                if (t.count { it == '.' } < 2) { token.error = "That does not look like a token"; return@button }
                prefs.baseUrl = base; prefs.altUrl = normalize(alt.text.toString()); prefs.longLivedToken = t
                setupStep()
            }))
        more.addView(V.spacer(this, 8f))
    }

    private val servers = ArrayList<Discovery.Server>()

    private fun addServer(s: Discovery.Server, force: Boolean = false) {
        if (!force) { if (!seen.add(s.uuid.ifEmpty { s.url })) return; servers.add(s) }
        val list = found ?: return
        searching?.text = if (servers.size == 1) "Found on your Wi-Fi" else "Found ${servers.size} on your Wi-Fi"
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = V.rounded(V.CARD, dp(18f).toFloat(), V.BORDER, dp(1f))
            setPadding(dp(14f), dp(14f), dp(14f), dp(14f))
            isClickable = true; isFocusable = true
            setOnClickListener {
                prefs.baseUrl = s.urls.first(); prefs.altUrl = s.urls.getOrNull(1) ?: ""; prefs.serverName = s.name
                login(s.urls.first())
            }
        }
        val badge = LinearLayout(this).apply { gravity = Gravity.CENTER; background = V.rounded(0x2640e3ff, dp(12f).toFloat()); addView(V.icon(this@ConnectActivity, R.drawable.ic_home_outline, V.CYAN, 20f)) }
        card.addView(badge, LinearLayout.LayoutParams(dp(40f), dp(40f)))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, dp(8f), 0) }
        t.addView(V.text(this, s.name, 15.5f, 700))
        t.addView(V.text(this, s.urls.first().removePrefix("http://").removePrefix("https://") + (if (s.version.isNotEmpty()) "  ·  ${s.version}" else ""), 12f, 400, V.MUTED).apply { maxLines = 1 })
        card.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(V.icon(this, R.drawable.ic_chevron_right, V.MUTED, 22f))
        list.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8f) })
    }

    private fun normalize(s: String): String {
        var u = s.trim().trimEnd('/')
        if (u.isEmpty()) return ""
        if (!u.startsWith("http")) u = (if (Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+").containsMatchIn(u) || u.contains(".local")) "http://" else "https://") + u
        return u
    }

    private fun login(base: String) {
        startActivityForResult(Intent(this, LoginActivity::class.java).putExtra("base", base), REQ_LOGIN)
    }

    @Deprecated("platform API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_LOGIN && resultCode == RESULT_OK) setupStep()
    }

    // ------------------------------------------------------------------ 2 · setting up

    private class Item(val row: View, val icon: ImageView, val spinner: View, val detail: TextView)

    private fun item(head: String): Item {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(12f), 0, dp(12f)) }
        val slot = android.widget.FrameLayout(this)
        val sp = V.spinner(this)
        val ic = V.icon(this, R.drawable.ic_check_circle, V.LIME, 24f).apply { visibility = View.GONE }
        slot.addView(sp, android.widget.FrameLayout.LayoutParams(dp(22f), dp(22f), Gravity.CENTER))
        slot.addView(ic, android.widget.FrameLayout.LayoutParams(dp(24f), dp(24f), Gravity.CENTER))
        row.addView(slot, LinearLayout.LayoutParams(dp(28f), dp(28f)))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14f), 0, 0, 0) }
        t.addView(V.text(this, head, 15f, 600))
        val d = V.text(this, "", 12.5f, 400, V.MUTED).apply { visibility = View.GONE }
        t.addView(d)
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        row.alpha = .35f
        col.addView(row)
        return Item(row, ic, sp, d)
    }

    private fun Item.start() { row.alpha = 1f }
    private fun Item.done(text: String, ok: Boolean = true) {
        row.alpha = 1f; spinner.visibility = View.GONE; icon.visibility = View.VISIBLE
        if (!ok) { icon.setImageResource(R.drawable.ic_information_outline); icon.setColorFilter(V.ORANGE) }
        detail.text = text; detail.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun setupStep() {
        reset(2)
        title("Setting up", "Reading your home. This takes a few seconds.")
        col.addView(V.spacer(this, 8f))
        val conn = item("Connecting to Home Assistant")
        val rooms = item("Rooms and devices")
        val favs = item("Favorites and suggestions")
        val energy = item("Energy dashboard")
        val result = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(result)
        conn.start()
        lifecycleScope.launch {
            if (Api(this@ConnectActivity).bases.isEmpty()) { conn.done("No address entered", ok = false); fail(result, "Enter the address first"); return@launch }
            val snap = Refresh.fetch(this@ConnectActivity)
            if (snap == null) { conn.done(prefs.lastError ?: "No answer", ok = false); fail(result, "Could not load your home: ${prefs.lastError ?: "unknown error"}"); return@launch }
            conn.done("${prefs.serverName.ifBlank { prefs.baseUrl.removePrefix("https://").removePrefix("http://") }} · ${snap.entities.size} entities")
            rooms.start()
            val home = try { Scanner.scan(this@ConnectActivity).also { if (it.locationName.isNotBlank()) prefs.serverName = it.locationName } } catch (e: Exception) { null }
            prefs.onboarded = true
            Refresh.schedule(this@ConnectActivity)
            Refresh.all(this@ConnectActivity, fetch = false)
            if (home == null) {
                rooms.done("The scan did not finish; widgets still work, and it tries again later.", ok = false)
                favs.done("", ok = false); energy.done("", ok = false)
            } else {
                val r = home.registry
                rooms.done(listOf(plural(r.areas.size, "area"), plural(r.floors.size, "floor"), plural(r.devices.size, "device")).filter { !it.startsWith("0 ") }.joinToString(" · ").ifEmpty { "No areas yet: widgets group by device type instead" }, ok = r.areas.isNotEmpty())
                favs.start(); favs.done(listOf(plural(home.favorites.size, "favorite"), plural(home.suggested.size, "suggestion")).joinToString(" · "))
                energy.start()
                val e = home.energy
                energy.done(if (e.todayStats.isEmpty()) "Not set up (optional)" else listOfNotNull("grid".takeIf { e.gridImport.isNotEmpty() }, "solar".takeIf { e.solarEnergy.isNotEmpty() }, "battery".takeIf { e.batteryIn.isNotEmpty() }).joinToString(" · "), ok = e.todayStats.isNotEmpty())
            }
            val n = home?.let { Planner.plan(it).size } ?: 0
            val card = V.card(this@ConnectActivity, result, 18f, 22f)
            card.addView(V.text(this@ConnectActivity, if (n > 0) "$n widgets ready for your home" else "You're connected", 18f, 800))
            card.addView(V.muted(this@ConnectActivity, if (n > 0) "Pick the ones you like next. You can change any widget later: touch and hold it → Reconfigure." else "Add widgets from your launcher's widget list; they fill themselves from your home.", 13f).apply { setPadding(0, dp(6f), 0, 0) })
            card.addView(full(V.button(this@ConnectActivity, if (n > 0) "Show my widgets" else "Done", V.Style.PRIMARY, R.drawable.ic_arrow_right) {
                if (n > 0) startActivity(Intent(this@ConnectActivity, HomeSetupActivity::class.java))
                setResult(RESULT_OK); finish()
            }, 14f))
        }
    }

    private fun fail(parent: LinearLayout, message: String) {
        val card = V.card(this, parent, 16f, 20f)
        card.addView(V.text(this, message, 14f, 600, V.PINK))
        card.addView(full(V.button(this, "Back to Connect", V.Style.SECONDARY) { connectStep() }, 12f))
    }

    private fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

    companion object {
        private const val REQ_LOGIN = 1
        const val SKIP_WELCOME = "skip_welcome"
    }
}
