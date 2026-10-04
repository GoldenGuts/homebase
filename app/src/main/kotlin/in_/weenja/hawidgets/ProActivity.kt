package in_.weenja.hawidgets

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import in_.weenja.hawidgets.ui.V
import kotlinx.coroutines.launch

/** Homebase Pro: what is free, what the one-time purchase unlocks, buy / restore, and the custom-setup offer. */
class ProActivity : AppCompatActivity() {
    private lateinit var buy: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = V.page(this, "Homebase Pro", "Every widget, for good. One purchase, no subscription.")
        status = V.text(this, "", 13.5f, 600, V.CYAN).apply { setPadding(0, V.dp(this@ProActivity, 10f), 0, 0) }
        col.addView(status)

        val card = V.card(this, col, 18f, 16f)
        card.addView(V.label(this, "Included"))
        for ((head, line) in listOf(
            "Every widget" to "Rooms, Energy, Security, Weather, Now playing, Suggested, Sensor graph, Calendar, Who's home and the rest",
            "Every size" to "From a single tile to a full-page dashboard",
            "Everything that comes next" to "New widgets arrive as updates, at no extra cost",
        )) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, V.dp(this@ProActivity, 10f), 0, 0) }
            row.addView(V.icon(this, R.drawable.ic_check_circle, V.LIME, 20f))
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(V.dp(this@ProActivity, 12f), 0, 0, 0) }
            t.addView(V.text(this, head, 15f, 700)); t.addView(V.muted(this, line, 13f))
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(row)
        }
        card.addView(V.label(this, "Free forever"))
        card.addView(V.muted(this, "Favorites, Tile and Weather · small, plus the demo home with every widget.", 13f).apply { setPadding(0, V.dp(this@ProActivity, 4f), 0, 0) })

        buy = V.button(this, "Unlock everything", V.Style.PRIMARY, R.drawable.ic_star_four_points) { purchase() }
        col.addView(buy, LinearLayout.LayoutParams(-1, -2).apply { topMargin = V.dp(this@ProActivity, 18f) })
        col.addView(V.button(this, "Restore purchase", V.Style.GHOST) { restore() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = V.dp(this@ProActivity, 10f) })
        col.addView(V.muted(this, "Paid once through Google Play and tied to your Google account: it carries over to new phones.", 12f).apply {
            gravity = Gravity.CENTER; setPadding(0, V.dp(this@ProActivity, 10f), 0, 0)
        })
        Support.card(this, col)

        Pro.onResult = { ok, message -> show(ok, message) }
        refresh()
    }

    override fun onDestroy() { Pro.onResult = null; super.onDestroy() }

    private fun refresh() {
        if (Pro.unlocked(this)) { show(true, null); return }
        lifecycleScope.launch {
            val price = Pro.price(this@ProActivity)
            if (Pro.unlocked(this@ProActivity)) show(true, null)
            else buy.text = if (price != null) "Unlock everything · $price" else "Unlock everything"
        }
    }

    private fun purchase() {
        if (Pro.unlocked(this)) { finish(); return }
        lifecycleScope.launch {
            if (!Pro.buy(this@ProActivity)) status.text = "Google Play is not available right now. Try again in a moment."
        }
    }

    private fun restore() {
        status.text = "Checking with Google Play…"
        lifecycleScope.launch {
            val owned = Pro.restore(this@ProActivity, explicit = true)
            status.text = if (owned) "" else "No purchase found for this Google account."
            if (owned) show(true, null)
        }
    }

    private fun show(ok: Boolean, message: String?) {
        if (ok) {
            status.text = if (BuildConfig.BETA) "Beta: every widget is unlocked while you test. After the beta, Favorites, Tile and Weather · small stay free."
                else "Unlocked. Thank you! Every widget is yours."
            buy.text = "Done"
        } else if (message != null) status.text = message
    }
}

/**
 * "Tell me what you want" (feedback by e-mail, at the bottom of the app and in Settings) and "Want
 * something custom?" (the offer to set things up, also in Pro). Both open the user's own mail app; the
 * app itself sends nothing.
 */
object Support {
    const val EMAIL = "support@weenja.in"
    /** Where the "Get in touch" button leads. */
    const val CONTACT = "mailto:$EMAIL?subject=Homebase%3A%20custom%20setup"

    private fun mail(a: android.app.Activity, intent: Intent) {
        try { a.startActivity(intent) }
        catch (_: Exception) { android.widget.Toast.makeText(a, "Write to $EMAIL", android.widget.Toast.LENGTH_LONG).show() }
    }

    /** An e-mail with three short questions and the app and phone versions under them. */
    fun sendFeedback(a: android.app.Activity) {
        val subject = if (BuildConfig.BETA) "Homebase beta feedback" else "Homebase feedback"
        val body = "What do you like?\n\n\nWhat is missing (a widget, a device, a setting)?\n\n\nWhat does not work?\n\n\n" +
            "--\nHomebase ${BuildConfig.VERSION_NAME} · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        // some mail apps read the mailto query, others only the extras: give both
        mail(a, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$EMAIL?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL)).putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body))
    }

    fun feedbackCard(a: android.app.Activity, col: LinearLayout) {
        val c = V.card(a, col, 16f, 26f)
        c.addView(V.text(a, if (BuildConfig.BETA) "You are testing the beta" else "Tell me what you want", 15.5f, 700))
        c.addView(V.muted(a, if (BuildConfig.BETA) "Every widget is unlocked while you test. Tell me what is missing, what is confusing and what does not work. I read every e-mail."
            else "A widget you miss, a device it should show, something that does not work. I read every e-mail.", 13f).apply {
            setPadding(0, V.dp(a, 4f), 0, 0)
        })
        c.addView(V.button(a, "Send feedback", V.Style.SECONDARY, R.drawable.ic_message_text_outline) { sendFeedback(a) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = V.dp(a, 12f) })
    }

    fun card(a: android.app.Activity, col: LinearLayout) {
        val c = V.card(a, col, 16f, 26f)
        c.addView(V.text(a, "Want something custom?", 15.5f, 700))
        c.addView(V.muted(a, "A widget for your setup, an automation, a dashboard, a sensor that needs YAML: I'll set it up for you.", 13f).apply {
            setPadding(0, V.dp(a, 4f), 0, 0)
        })
        c.addView(V.button(a, "Get in touch", V.Style.SECONDARY, R.drawable.ic_arrow_right) { mail(a, Intent(Intent.ACTION_SENDTO, Uri.parse(CONTACT))) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = V.dp(a, 12f) })
    }
}
