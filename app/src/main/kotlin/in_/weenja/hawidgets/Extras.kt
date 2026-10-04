package in_.weenja.hawidgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import in_.weenja.hawidgets.widgets.Catalog

/**
 * The Extras (Mac, Claude Code, gaming PC, money, fuel, screen time, overhead) need the author's own
 * Home Assistant packages, so a fresh install hides them, from the app and from the launcher's widget
 * list (their providers are disabled). Anyone who already has one placed keeps them.
 */
object Extras {
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("hawidgets", Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = sp(ctx).getBoolean("extras_enabled", false)

    /** First start of this version: keep Extras for anyone who already uses one, hide them otherwise. */
    fun decide(ctx: Context) {
        val sp = sp(ctx)
        if (sp.getBoolean("extras_decided", false)) return
        val mgr = AppWidgetManager.getInstance(ctx)
        val inUse = (Catalog.extras + Catalog.legacy).any { mgr.getAppWidgetIds(ComponentName(ctx, it.cls)).isNotEmpty() }
        sp.edit().putBoolean("extras_decided", true).apply()
        set(ctx, inUse)
    }

    /** Show or hide the Extras. Hiding asks the launcher to drop placed Extras widgets, so the UI warns first. */
    fun set(ctx: Context, on: Boolean) {
        sp(ctx).edit().putBoolean("extras_enabled", on).apply()
        val pm = ctx.packageManager
        val state = if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        for (e in Catalog.extras + Catalog.legacy) {
            try { pm.setComponentEnabledSetting(ComponentName(ctx, e.cls), state, PackageManager.DONT_KILL_APP) } catch (_: Exception) {}
        }
    }

    /** Placed Extras widgets (for the warning before hiding them). */
    fun placed(ctx: Context): Int {
        val mgr = AppWidgetManager.getInstance(ctx)
        return (Catalog.extras + Catalog.legacy).sumOf { mgr.getAppWidgetIds(ComponentName(ctx, it.cls)).size }
    }
}
