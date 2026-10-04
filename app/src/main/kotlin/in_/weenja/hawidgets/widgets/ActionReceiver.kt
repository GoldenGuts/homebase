package in_.weenja.hawidgets.widgets

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import in_.weenja.hawidgets.Prefs
import in_.weenja.hawidgets.WidgetStore
import in_.weenja.hawidgets.core.WidgetSpec
import in_.weenja.hawidgets.ha.Api
import in_.weenja.hawidgets.ha.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Runs widget taps: HA service calls (with an optimistic redraw first), armed chips, refreshes, the
 * refresh alarm and the "widget pinned" callback. A broadcast gets about ten seconds, so anything that
 * may take longer goes to WorkManager.
 */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handle(ctx, intent)
            } catch (e: Exception) {
                Log.w(Api.TAG, "action failed: $e")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACT_SERVICE -> {
                val optId = intent.getStringExtra("opt_id")
                if (optId != null) paintOptimistic(ctx, optId, intent.getStringExtra("opt_state") ?: "")
                withTimeoutOrNull(7_000) { call(ctx, intent) }
                Refresh.enqueueNow(ctx, 1_200)
            }
            ACT_ARMED -> {
                val key = intent.getStringExtra("key") ?: return
                val prefs = Prefs(ctx)
                if (Actions.isArmed(ctx, key)) {
                    prefs.arm(key, 0L)
                    withTimeoutOrNull(7_000) { call(ctx, intent) }
                    Refresh.enqueueNow(ctx, 1_200)
                } else {
                    prefs.arm(key, System.currentTimeMillis())
                    Refresh.all(ctx, fetch = false)
                    // disarm visually after the window passes; the worker repaints
                    Refresh.enqueueNow(ctx, Actions.ARM_MS + 500)
                }
            }
            ACT_REFRESH -> refreshNow(ctx)
            ACT_ALARM -> {
                Refresh.armAlarm(ctx)
                if (Refresh.widgetCount(ctx) > 0) refreshNow(ctx)
            }
            ACT_PINNED -> {
                // requestPinAppWidget's success callback: the launcher added the widget; store the plan for its id
                val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0)
                val spec = WidgetSpec.parse(intent.getStringExtra("spec"))
                if (id != 0 && spec != null) {
                    WidgetStore.put(ctx, id, spec)
                    Refresh.drawOne(ctx, id)
                    Refresh.enqueueNow(ctx, 1_500)
                }
            }
        }
    }

    /** Fetch inline when Home Assistant answers quickly, else let WorkManager finish it. */
    private suspend fun refreshNow(ctx: Context) {
        val done = withTimeoutOrNull(8_000) { Refresh.all(ctx, fetch = true) }
        if (done == null) { Refresh.all(ctx, fetch = false); Refresh.enqueueNow(ctx) }
    }

    private suspend fun call(ctx: Context, intent: Intent) {
        val domain = intent.getStringExtra("domain") ?: return
        val service = intent.getStringExtra("service") ?: return
        val data = try { JSONObject(intent.getStringExtra("data") ?: "{}") } catch (e: Exception) { JSONObject() }
        val ok = Api(ctx).callService(domain, service, data)
        if (!ok) Prefs(ctx).lastError = "$domain.$service failed"
    }

    /** Repaint at once with the expected state so a toggle feels instant. */
    private fun paintOptimistic(ctx: Context, id: String, state: String) {
        val snap = Snapshot.load(ctx) ?: return
        if (snap[id] == null) return
        Refresh.drawAll(ctx, snap.withState(id, state))
    }

    companion object {
        const val ACT_SERVICE = "in_.weenja.hawidgets.SERVICE"
        const val ACT_ARMED = "in_.weenja.hawidgets.ARMED"
        const val ACT_REFRESH = "in_.weenja.hawidgets.REFRESH"
        const val ACT_ALARM = "in_.weenja.hawidgets.ALARM"
        const val ACT_PINNED = "in_.weenja.hawidgets.PINNED"
    }
}
