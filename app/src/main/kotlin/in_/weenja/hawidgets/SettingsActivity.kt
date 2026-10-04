package in_.weenja.hawidgets

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import in_.weenja.hawidgets.core.SlotSpec
import in_.weenja.hawidgets.ha.Snapshot
import in_.weenja.hawidgets.ui.V
import in_.weenja.hawidgets.widgets.Refresh
import kotlinx.coroutines.launch

/** Connection, refresh, the global defaults (with the entity picker), backup and restore, about. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var prefs: Prefs
    private lateinit var defaults: LinearLayout
    private var pendingKey: Config.Key? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val col = V.page(this, "Settings")

        col.addView(V.h2(this, "Home Assistant"))
        val c = V.card(this, col, 14f, 4f)
        c.addView(V.body(this, if (prefs.isLoggedIn) "Connected to ${prefs.serverName.ifBlank { prefs.baseUrl }}" else "Not connected"))
        if (prefs.baseUrl.isNotEmpty()) c.addView(V.muted(this, listOf(prefs.baseUrl, prefs.altUrl).filter { it.isNotEmpty() }.joinToString("  ·  ") +
            if (prefs.longLivedToken != null) "  ·  long-lived token" else ""))
        c.addView(V.row(this,
            V.button(this, if (prefs.isLoggedIn) "Change server" else "Connect", V.Style.SECONDARY) { startActivity(Intent(this, ConnectActivity::class.java)) },
            V.button(this, "Log out", V.Style.DANGER) {
                AlertDialog.Builder(this).setTitle("Log out?").setMessage("The widgets keep their settings and show demo data until you log in again.")
                    .setPositiveButton("Log out") { _, _ -> prefs.logout(); Snapshot.clear(this); HomeStore.clear(this); lifecycleScope.launch { Refresh.all(this@SettingsActivity, fetch = false) }; recreate() }
                    .setNegativeButton("Cancel", null).show()
            }))

        col.addView(V.h2(this, "Refresh"))
        col.addView(V.muted(this, "How often widgets update in the background. Android lets an app refresh at most about every 9 minutes while the phone sleeps; an overdue refresh runs as soon as the screen turns on. Every widget shows how old its data is."))
        val opts = listOf(5, 10, 15, 30, 60)
        val chips = opts.map { m -> V.button(this, "$m min", if (prefs.refreshMinutes == m) V.Style.PRIMARY else V.Style.SECONDARY) { prefs.refreshMinutes = m; Refresh.schedule(this); recreate() } }
        col.addView(V.row(this, *chips.toTypedArray(), gap = 6f))
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val exempt = pm.isIgnoringBatteryOptimizations(packageName)
        col.addView(V.button(this, if (exempt) "Battery: unrestricted ✓" else "Allow background refresh (battery)", if (exempt) V.Style.GHOST else V.Style.SECONDARY) { askBattery() }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = V.dp(this@SettingsActivity, 10f) }
        })

        col.addView(V.h2(this, "Defaults"))
        col.addView(V.muted(this, "What widgets use when their own settings leave something open. Empty = found automatically in Home Assistant."))
        defaults = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(defaults)
        buildDefaults()
        col.addView(V.button(this, "Reset all defaults", V.Style.GHOST) { Config.reset(this); buildDefaults(); redraw() }.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = V.dp(this@SettingsActivity, 12f) }
        })

        col.addView(V.h2(this, "Backup"))
        col.addView(V.muted(this, "Saves every widget's settings and your defaults to a file (never your login). Restore it on a new phone, then add the widgets: each picks up its old settings."))
        col.addView(V.row(this,
            V.button(this, "Save to a file", V.Style.SECONDARY) {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json")
                    .putExtra(Intent.EXTRA_TITLE, "homebase-backup.json"), REQ_EXPORT)
            },
            V.button(this, "Restore", V.Style.SECONDARY) {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT)
            }))

        col.addView(V.h2(this, "Homebase Pro"))
        col.addView(V.muted(this, if (BuildConfig.BETA) "Beta: every widget is unlocked while you test. After the beta, Favorites, Tile and Weather · small stay free."
            else if (Pro.unlocked(this)) "Unlocked: every widget is yours. Thank you!" else "Favorites, Tile and Weather · small are free. One purchase unlocks every widget, for good."))
        col.addView(V.button(this, if (Pro.unlocked(this)) "Homebase Pro ✓" else "Unlock everything", if (Pro.unlocked(this)) V.Style.GHOST else V.Style.PRIMARY) {
            startActivity(Intent(this, ProActivity::class.java))
        }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = V.dp(this@SettingsActivity, 10f) } })

        col.addView(V.h2(this, "About"))
        col.addView(V.muted(this, "Homebase ${BuildConfig.VERSION_NAME}. Talks to your own Home Assistant only: no account, no analytics, no ads. " +
            "Inter font under the SIL Open Font License; icons from Material Design Icons (Apache 2.0)."))
        col.addView(V.button(this, "Privacy", V.Style.GHOST) {
            AlertDialog.Builder(this).setTitle("Privacy").setMessage(PRIVACY).setPositiveButton("OK", null).show()
        }.apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = V.dp(this@SettingsActivity, 8f) } })
        Support.feedbackCard(this, col)
        Support.card(this, col)
    }

    private fun buildDefaults() {
        defaults.removeAllViews()
        val snap = Refresh.current(this)
        val home = HomeStore.home(this, snap)
        for (g in Config.groups) {
            if (g.extras && !Extras.enabled(this)) continue
            defaults.addView(V.label(this, g.title))
            val card = V.card(this, defaults, 12f, 6f)
            card.addView(V.muted(this, g.blurb))
            for (k in g.keys) {
                val v = Config.get(this, k.id)
                card.addView(V.text(this, k.label, 13.5f, 600).apply { setPadding(0, V.dp(this@SettingsActivity, 10f), 0, 0) })
                if (k.type == Config.Type.ENTITY || k.type == Config.Type.ENTITIES) {
                    val names = v.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ") { id -> home[id]?.name?.let { "$it" } ?: id }
                    val known = v.isEmpty() || snap.demo || v.split(',').all { id -> home[id.trim()] != null }
                    card.addView(V.text(this, names.ifEmpty { "Automatic" }, 13f, 400, if (known) V.MUTED else V.PINK).apply { if (!known) text = "$names · not in Home Assistant" })
                    card.addView(V.row(this,
                        V.button(this, "Choose", V.Style.SECONDARY) {
                            pendingKey = k
                            startActivityForResult(PickerActivity.intent(this, SlotSpec(k.id, k.label, k.domains, multiple = k.type == Config.Type.ENTITIES, max = 12),
                                v.split(',').map { it.trim() }.filter { it.isNotEmpty() }), REQ_PICK)
                        },
                        V.button(this, "Default", V.Style.GHOST) { Config.set(this, k.id, ""); buildDefaults(); redraw() }, top = 6f))
                } else {
                    val f = V.field(this, k.def.ifEmpty { k.hint.ifEmpty { "optional" } }, if (Config.isOverridden(this, k.id)) v else "")
                    f.addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                        override fun afterTextChanged(s: Editable?) { Config.set(this@SettingsActivity, k.id, s?.toString() ?: "") }
                    })
                    card.addView(f)
                    if (k.hint.isNotEmpty() && k.def.isNotEmpty()) card.addView(V.muted(this, k.hint, 11.5f))
                }
            }
        }
    }

    override fun onPause() { super.onPause(); redraw() }

    private fun redraw() { lifecycleScope.launch { Refresh.all(this@SettingsActivity, fetch = false) } }

    @Deprecated("platform API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_PICK -> pendingKey?.let { k ->
                Config.set(this, k.id, (data.getStringArrayExtra(PickerActivity.EXTRA_SELECTED) ?: emptyArray()).joinToString(","))
                buildDefaults(); redraw()
            }
            REQ_EXPORT -> data.data?.let { uri ->
                try { contentResolver.openOutputStream(uri, "wt")?.use { it.write(Backup.export(this).toByteArray()) }; toast("Backup saved") }
                catch (e: Exception) { toast("Could not save: ${e.message}") }
            }
            REQ_IMPORT -> data.data?.let { uri ->
                try {
                    val text = contentResolver.openInputStream(uri)?.use { String(it.readBytes()) } ?: return
                    val r = Backup.import(this, text)
                    toast("Restored ${r.config} defaults and ${r.applied} widget${if (r.applied == 1) "" else "s"}" + if (r.waiting > 0) "; ${r.waiting} wait for you to add them" else "")
                    buildDefaults(); redraw()
                } catch (e: Exception) { toast("Could not restore: ${e.message}") }
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun askBattery() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) { toast("Background refresh is already allowed"); return }
        // App info → App battery usage → Unrestricted. (The direct exemption prompt needs a permission
        // Google Play reserves for other kinds of apps.)
        toast("Tap App battery usage → Unrestricted")
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        catch (e: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    companion object {
        private const val REQ_PICK = 1
        private const val REQ_EXPORT = 2
        private const val REQ_IMPORT = 3
        const val PRIVACY = "Homebase talks to your own Home Assistant only. There is no account, no analytics, no crash reporting and no third-party service.\n\n" +
            "Your Home Assistant address, the login token and your widget settings stay in the app's private storage. Widget settings (not the token) are included in Android's backup so they move to a new phone.\n\n" +
            "Entity states, forecasts, calendar events, camera snapshots and artwork are cached on the phone so widgets can draw without network.\n\n" +
            "Finding Home Assistant on your network uses mDNS on the local Wi-Fi only. Logging out deletes the tokens; uninstalling deletes everything."
    }
}
