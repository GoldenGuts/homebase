package in_.weenja.hawidgets.ha

import android.content.Context
import in_.weenja.hawidgets.core.EntityState
import in_.weenja.hawidgets.core.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Entity(val id: String, val state: String, val attrs: JSONObject, val lastChanged: String = "") {
    val domain get() = id.substringBefore('.')
    val name: String get() = attrs.optString("friendly_name", id.substringAfter('.').replace('_', ' '))
    val isOn get() = state == "on"
    val available get() = state != "unavailable" && state != "unknown"
    fun num(key: String, def: Double = 0.0): Double = attrs.opt(key)?.toString()?.toDoubleOrNull() ?: def
    fun str(key: String, def: String = ""): String = attrs.opt(key)?.toString()?.takeIf { it != "null" } ?: def
    val stateNum: Double get() = state.toDoubleOrNull() ?: 0.0
    /** ON-ish states the dashboard's device_tile treats as active. */
    val active get() = state in ACTIVE

    /** The same entity in the shared model (rules, labels, icons). */
    val core: EntityState by lazy { EntityState(id, state, Json.parseOrNull(attrs.toString()) ?: Json.Obj(emptyMap()), lastChanged) }

    fun toJson(): JSONObject = JSONObject().put("entity_id", id).put("state", state).put("attributes", attrs).put("last_changed", lastChanged)

    companion object { val ACTIVE = setOf("on", "playing", "home", "Unlocked", "connected", "paused", "idle") }
}

/** All entity states of one fetch. Widgets only read from this; nothing renders from live network. */
class Snapshot(val entities: Map<String, Entity>, val fetchedAt: Long, val demo: Boolean = false, val badge: Boolean = true) {
    operator fun get(id: String): Entity? = entities[id]
    fun state(id: String, def: String = "unavailable") = entities[id]?.state ?: def
    fun num(id: String, def: Double = 0.0) = entities[id]?.stateNum ?: def
    fun on(id: String) = entities[id]?.isOn == true
    fun byDomain(domain: String) = entities.values.filter { it.domain == domain }.sortedBy { it.id }
    val isEmpty get() = entities.isEmpty()

    /** Shared-model view of one entity. */
    fun core(id: String?): EntityState? = id?.let { entities[it]?.core }

    /** Shared-model view of every entity. */
    val coreStates: List<EntityState> by lazy { entities.values.map { it.core } }

    /** Copy with one entity's state replaced (optimistic repaint). */
    fun withState(id: String, state: String): Snapshot {
        val e = entities[id] ?: return this
        val m = HashMap(entities); m[id] = Entity(id, state, e.attrs, e.lastChanged)
        return Snapshot(m, fetchedAt, demo, badge)
    }

    /** The same demo data without the "Demo · tap to connect" badge (picker previews, store screenshots). */
    fun clean(): Snapshot = Snapshot(entities, fetchedAt, demo, badge = false)

    companion object {
        fun parse(arr: JSONArray, fetchedAt: Long = System.currentTimeMillis(), demo: Boolean = false): Snapshot {
            val m = HashMap<String, Entity>(arr.length() * 2)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("entity_id"); if (id.isEmpty()) continue
                m[id] = Entity(id, o.optString("state"), o.optJSONObject("attributes") ?: JSONObject(), o.optString("last_changed"))
            }
            return Snapshot(m, fetchedAt, demo)
        }

        private fun file(ctx: Context) = File(ctx.cacheDir, "states.json")

        fun save(ctx: Context, arr: JSONArray, at: Long) {
            val tmp = File(ctx.cacheDir, "states.json.tmp")
            tmp.writeText(JSONObject().put("at", at).put("states", arr).toString())
            tmp.renameTo(file(ctx))
            cache = null
        }

        @Volatile private var cache: Pair<Long, Snapshot>? = null

        /** Last saved snapshot, or null when the app never fetched. Cached while the file is unchanged. */
        fun load(ctx: Context): Snapshot? = try {
            val f = file(ctx)
            if (!f.exists()) null else {
                val c = cache
                if (c != null && c.first == f.lastModified()) c.second
                else {
                    val j = JSONObject(f.readText())
                    parse(j.getJSONArray("states"), j.optLong("at")).also { cache = f.lastModified() to it }
                }
            }
        } catch (e: Exception) { null }

        fun clear(ctx: Context) { file(ctx).delete(); cache = null }
    }
}
