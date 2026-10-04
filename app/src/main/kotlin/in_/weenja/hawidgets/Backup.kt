package in_.weenja.hawidgets

import android.content.Context
import in_.weenja.hawidgets.core.Json
import in_.weenja.hawidgets.core.WidgetSpec
import in_.weenja.hawidgets.widgets.Refresh

/**
 * Widget configurations to a file and back, for moving to a new phone (Android's own backup also
 * carries them; this works across launchers and without Google). Tokens are never written.
 */
object Backup {
    const val FORMAT = 1

    fun export(ctx: Context): String {
        val prefs = Prefs(ctx)
        val widgets = Refresh.placed(ctx).mapNotNull { (_, entry, spec) -> spec?.let { Json.obj("provider" to entry.id, "spec" to it.toJson()) } }
        return Json.obj(
            "app" to "homebase", "format" to FORMAT, "exported_at" to java.time.Instant.now().toString(),
            "server" to Json.obj("base_url" to prefs.baseUrl, "alt_url" to prefs.altUrl),
            "settings" to Json.obj("refresh_min" to prefs.refreshMinutes, "extras" to Extras.enabled(ctx)),
            "config" to Config.overrides(ctx),
            "widgets" to widgets,
        ).toString()
    }

    class Result(val config: Int, val applied: Int, val waiting: Int)

    /** Apply a backup. Configs go to placed widgets of the same kind that have none of their own; the rest wait for new ones. */
    fun import(ctx: Context, text: String): Result {
        val j = Json.parseOrNull(text) ?: throw IllegalArgumentException("Not a Homebase backup")
        if (j.str("app") != "homebase") throw IllegalArgumentException("Not a Homebase backup")
        val prefs = Prefs(ctx)
        j["server"]?.let { s -> if (prefs.baseUrl.isEmpty()) { s.str("base_url")?.let { prefs.baseUrl = it }; s.str("alt_url")?.let { prefs.altUrl = it } } }
        j["settings"]?.num("refresh_min")?.let { prefs.refreshMinutes = it.toInt() }
        if (j["settings"]?.flag("extras") == true) Extras.set(ctx, true)
        var cfg = 0
        j["config"]?.obj?.forEach { (k, v) -> if (Config.keys.containsKey(k)) { v.string?.let { Config.set(ctx, k, it); cfg++ } } }
        WidgetStore.clearPending(ctx)
        val free = Refresh.placed(ctx).filter { it.third == null || it.third!!.auto }.groupBy { it.second.id }.mapValues { it.value.map { t -> t.first }.toMutableList() }
        var applied = 0; var waiting = 0
        for (w in j.arr("widgets")) {
            val provider = w.str("provider") ?: continue
            val spec = WidgetSpec.fromJson(w["spec"]) ?: continue
            val id = free[provider]?.removeFirstOrNull()
            if (id != null) { WidgetStore.put(ctx, id, spec); applied++ } else { WidgetStore.addPending(ctx, provider, spec); waiting++ }
        }
        return Result(cfg, applied, waiting)
    }
}
