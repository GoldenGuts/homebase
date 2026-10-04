package in_.weenja.hawidgets.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.RectF
import android.net.Uri
import in_.weenja.hawidgets.Cfg
import in_.weenja.hawidgets.ConfigureActivity
import in_.weenja.hawidgets.MainActivity
import in_.weenja.hawidgets.Prefs
import in_.weenja.hawidgets.core.EntityState
import in_.weenja.hawidgets.core.Rules
import in_.weenja.hawidgets.core.WidgetSpec
import org.json.JSONObject

/** One tap zone on a widget bitmap: a rect in dp, what happens on tap, and a label for TalkBack. */
class Hotspot(val rect: RectF, val intent: PendingIntent, val label: String? = null)

/** Builds the PendingIntents the widgets attach to their tap zones. */
class Actions(private val ctx: Context, val widgetId: Int = 0, val spec: WidgetSpec? = null) {
    private val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /** This widget's configuration, falling back to the global defaults. */
    val cfg: Cfg by lazy { Cfg(ctx, spec) }

    private fun broadcast(i: Intent): PendingIntent {
        // extras do not take part in Intent equality, so every distinct action gets its own data URI
        i.setClass(ctx, ActionReceiver::class.java)
        i.data = Uri.parse("hawidgets://action/" + (i.extras?.let { b -> b.keySet().sorted().joinToString("&") { k -> "$k=${b.get(k)}" } } ?: "").hashCode())
        return PendingIntent.getBroadcast(ctx, i.data.hashCode(), i, flags)
    }

    /** Call an HA service. [optimistic] = entity_id -> expected state, painted before the round trip. */
    fun service(domain: String, service: String, data: JSONObject? = null, optimistic: Pair<String, String>? = null): PendingIntent =
        broadcast(Intent(ActionReceiver.ACT_SERVICE).apply {
            putExtra("domain", domain); putExtra("service", service); putExtra("data", (data ?: JSONObject()).toString())
            optimistic?.let { putExtra("opt_id", it.first); putExtra("opt_state", it.second) }
        })

    fun toggle(entityId: String, currentlyOn: Boolean): PendingIntent {
        val domain = entityId.substringBefore('.')
        val d = if (domain in setOf("light", "switch", "fan", "media_player", "input_boolean", "climate", "humidifier", "remote")) domain else "homeassistant"
        return service(d, "toggle", JSONObject().put("entity_id", entityId), entityId to if (currentlyOn) "off" else "on")
    }

    /**
     * The one-tap action the shared rules pick for an entity (toggle, open / close, lock, run a scene…).
     * Security-sensitive ones (unlock, open the garage) need a second tap within a few seconds.
     * Entities without an action open Home Assistant.
     */
    fun tap(e: EntityState): PendingIntent {
        val call = Rules.tapAction(e) ?: return open()
        val data = JSONObject(call.data.toString())
        return if (call.confirm) armed("tap:${e.id}:${call.service}", call.domain, call.service, data)
        else service(call.domain, call.service, data, call.optimistic?.let { e.id to it })
    }

    /** Two-tap confirmation: the first tap arms the chip for a few seconds, the second one fires. */
    fun armed(key: String, domain: String, service: String, data: JSONObject? = null): PendingIntent =
        broadcast(Intent(ActionReceiver.ACT_ARMED).apply {
            putExtra("key", key); putExtra("domain", domain); putExtra("service", service); putExtra("data", (data ?: JSONObject()).toString())
        })

    fun isArmed(key: String) = Actions.isArmed(ctx, key)

    fun refresh(): PendingIntent = broadcast(Intent(ActionReceiver.ACT_REFRESH))

    /** wake_on_lan.send_magic_packet, or the app's setup screen when no MAC address is configured. */
    fun wake(mac: String, broadcast: String = ""): PendingIntent {
        if (mac.isBlank()) return app()
        val d = JSONObject().put("mac", mac.trim())
        if (broadcast.isNotBlank()) d.put("broadcast_address", broadcast.trim())
        return service("wake_on_lan", "send_magic_packet", d)
    }

    fun script(name: String, data: JSONObject? = null): PendingIntent = if (name.isBlank()) configure() else service("script", name.removePrefix("script."), data)

    /** Open a dashboard path in the HA companion app, or the browser when the app is missing. */
    fun open(path: String = cfg["path_home"]): PendingIntent {
        val p = path.ifBlank { "/" }
        val deep = Intent(Intent.ACTION_VIEW, Uri.parse("homeassistant://navigate$p")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val base = Prefs(ctx).baseUrl
        val target = if (deep.resolveActivity(ctx.packageManager) != null) deep
            else if (base.isNotEmpty()) Intent(Intent.ACTION_VIEW, Uri.parse(base + p)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            else Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, ("open$p").hashCode(), target, flags)
    }

    /** Home Assistant's more-info dialog of one entity. */
    fun moreInfo(entityId: String): PendingIntent = open("${cfg["path_home"].ifBlank { "/" }}?more-info-entity-id=$entityId")

    /** Open any https link (a Claude session, for example) in the browser or the app that owns it. */
    fun url(link: String): PendingIntent =
        PendingIntent.getActivity(ctx, link.hashCode(), Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)

    fun app(): PendingIntent =
        PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), flags)

    /** This widget's settings (the app when the widget has no id, e.g. in a preview). */
    fun configure(): PendingIntent {
        if (widgetId == 0) return app()
        val i = Intent(ctx, ConfigureActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .setData(Uri.parse("hawidgets://configure/$widgetId")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(ctx, 1000 + widgetId, i, flags)
    }

    companion object {
        /** Simple per-key armed state (survives process death via prefs). */
        fun isArmed(ctx: Context, key: String) = System.currentTimeMillis() - Prefs(ctx).armedAt(key) < ARM_MS
        const val ARM_MS = 6000L
    }
}
