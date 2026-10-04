package in_.weenja.hawidgets

import android.content.Context
import android.content.SharedPreferences
import in_.weenja.hawidgets.core.EntityState
import in_.weenja.hawidgets.core.Home
import in_.weenja.hawidgets.core.Json
import in_.weenja.hawidgets.core.WidgetSpec
import in_.weenja.hawidgets.ha.Snapshot
import java.io.File

/**
 * Per-widget configuration, keyed by appWidgetId. The global [Config] keys stay the fallback defaults.
 * Lives in its own preferences file so Android backup can carry it to a new phone ([remap] handles the
 * new ids there).
 */
object WidgetStore {
    private fun sp(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences("widgets", Context.MODE_PRIVATE)

    fun get(ctx: Context, id: Int): WidgetSpec? = WidgetSpec.parse(sp(ctx).getString("w_$id", null))

    fun put(ctx: Context, id: Int, spec: WidgetSpec) { sp(ctx).edit().putString("w_$id", spec.toString()).apply() }

    fun remove(ctx: Context, ids: IntArray) { val e = sp(ctx).edit(); ids.forEach { e.remove("w_$it") }; e.apply() }

    /** Every stored config by widget id. */
    fun all(ctx: Context): Map<Int, WidgetSpec> = sp(ctx).all.mapNotNull { (k, v) ->
        if (!k.startsWith("w_") || v !is String) null else k.removePrefix("w_").toIntOrNull()?.let { id -> WidgetSpec.parse(v)?.let { id to it } }
    }.toMap()

    /** After a restore the launcher hands out new ids: move each config over. */
    fun remap(ctx: Context, oldIds: IntArray, newIds: IntArray) {
        val sp = sp(ctx)
        val moved = oldIds.indices.mapNotNull { i -> sp.getString("w_${oldIds[i]}", null)?.let { newIds.getOrNull(i)?.to(it) } }
        val e = sp.edit()
        oldIds.forEach { e.remove("w_$it") }
        moved.forEach { (id, v) -> e.putString("w_$id", v) }
        e.apply()
    }

    // ------------------------------------------------------------ restored-from-file configs waiting for a widget

    /** Configs imported from a backup file, per provider, used by the next widgets of that kind without their own. */
    fun addPending(ctx: Context, provider: String, spec: WidgetSpec) {
        val sp = sp(ctx)
        val list = Json.parseOrNull(sp.getString("pending_$provider", null))?.list?.toMutableList() ?: mutableListOf()
        list.add(spec.toJson())
        sp.edit().putString("pending_$provider", Json.Arr(list).toString()).apply()
    }

    fun takePending(ctx: Context, provider: String): WidgetSpec? {
        val sp = sp(ctx)
        val list = Json.parseOrNull(sp.getString("pending_$provider", null))?.list ?: return null
        if (list.isEmpty()) return null
        sp.edit().putString("pending_$provider", Json.Arr(list.drop(1)).toString()).apply()
        return WidgetSpec.fromJson(list.first())
    }

    fun clearPending(ctx: Context) { val e = sp(ctx).edit(); sp(ctx).all.keys.filter { it.startsWith("pending_") }.forEach { e.remove(it) }; e.apply() }

    /** Every entity any widget instance is configured with (joins the fetch filter). */
    fun entityIds(ctx: Context): Set<String> = all(ctx).values.flatMap { it.entityIds }.toSet()
}

/**
 * The last auto-setup scan (registries, favorites, suggestions, energy prefs) on disk. Combined with the
 * current states it gives a [Home] for the planner, the picker and the widgets.
 */
object HomeStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "home.json")

    @Volatile private var cached: Pair<Long, Json?>? = null

    fun save(ctx: Context, home: Home) {
        val j = home.toJson()
        file(ctx).writeText(j.toString())
        cached = file(ctx).lastModified() to j
    }

    private fun json(ctx: Context): Json? {
        val f = file(ctx)
        if (!f.exists()) return null
        val c = cached
        if (c != null && c.first == f.lastModified()) return c.second
        val j = try { Json.parseOrNull(f.readText()) } catch (e: Exception) { null }
        cached = f.lastModified() to j
        return j
    }

    fun scannedAt(ctx: Context): Long = json(ctx)?.num("scanned_at")?.toLong() ?: 0L

    fun hasScan(ctx: Context) = file(ctx).exists()

    /** The scan joined with [snap]'s states; demo home when there is no scan and the snapshot is the demo. */
    fun home(ctx: Context, snap: Snapshot): Home {
        if (snap.demo) return in_.weenja.hawidgets.ha.Demo.home()
        return Home.fromJson(json(ctx), snap.core)
    }

    fun clear(ctx: Context) { file(ctx).delete(); cached = null }
}

/** Convenience: the core states of a snapshot. */
val Snapshot.core: List<EntityState> get() = coreStates
