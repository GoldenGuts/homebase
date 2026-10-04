package in_.weenja.hawidgets.ha

import android.content.Context
import android.util.Log
import in_.weenja.hawidgets.HomeStore
import in_.weenja.hawidgets.Prefs
import in_.weenja.hawidgets.core.EnergySetup
import in_.weenja.hawidgets.core.EntityState
import in_.weenja.hawidgets.core.Home
import in_.weenja.hawidgets.core.Json
import in_.weenja.hawidgets.core.Lovelace
import in_.weenja.hawidgets.core.Registry
import in_.weenja.hawidgets.core.WidgetSpec
import in_.weenja.hawidgets.core.Ws
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** One authenticated WebSocket connection; commands run one after another. */
class HaSession private constructor(private val ws: WsClient, val haVersion: String?) {
    private var nextId = 1
    /** Events that arrived while waiting for a result (subscriptions). */
    val events = ArrayList<Json>()

    /** Send a command and wait for its result. Throws on error answers. */
    fun call(cmd: Ws.Command): Json? {
        val id = nextId++
        ws.send(Ws.commandOf(id, cmd))
        while (true) {
            val text = ws.receive() ?: throw IOException("Home Assistant closed the connection")
            for (m in Ws.parse(text)) {
                if (m.isEvent) { m.event?.let { events.add(it) }; continue }
                if (m.isResult && m.id == id) {
                    if (!m.success) throw HaException("${cmd.type}: ${m.errorCode ?: ""} ${m.errorMessage ?: ""}".trim())
                    return m.result
                }
            }
        }
    }

    /** Like [call] but null on an error answer (a command this Home Assistant does not know, for example). */
    fun tryCall(cmd: Ws.Command): Json? = try { call(cmd) } catch (e: HaException) { Log.i(Api.TAG, "ws ${cmd.type}: ${e.message}"); null }

    /** Block for the next event (subscriptions). Null when the connection closed. */
    fun nextEvent(): Json? {
        if (events.isNotEmpty()) return events.removeAt(0)
        while (true) {
            val text = ws.receive() ?: return null
            for (m in Ws.parse(text)) if (m.isEvent) m.event?.let { events.add(it) }
            if (events.isNotEmpty()) return events.removeAt(0)
        }
    }

    fun close() = ws.close()

    companion object {
        /** Connect (primary URL, then the fallback), authenticate, run [block], close. */
        suspend fun <T> open(ctx: Context, readTimeoutMs: Int = 30000, block: suspend HaSession.() -> T): T = withContext(Dispatchers.IO) {
            val api = Api(ctx)
            var last: Exception? = null
            for (base in api.bases) {
                val session = try { connect(api, base, readTimeoutMs) } catch (e: HaException) { throw e } catch (e: Exception) { last = e; Log.w(Api.TAG, "ws $base: $e"); continue }
                try { return@withContext session.block() } finally { session.close() }
            }
            throw last ?: IOException("No Home Assistant URL configured")
        }

        private suspend fun connect(api: Api, base: String, readTimeoutMs: Int): HaSession {
            val ws = WsClient.connect(base, readTimeoutMs = readTimeoutMs)
            try {
                var version: String? = null
                while (true) {
                    val msgs = Ws.parse(ws.receive() ?: throw IOException("closed during auth"))
                    val m = msgs.firstOrNull() ?: continue
                    when {
                        m.isAuthRequired -> { version = m.haVersion; ws.send(Ws.auth(api.accessToken(base))) }
                        m.isAuthOk -> return HaSession(ws, m.haVersion ?: version)
                        m.isAuthInvalid -> throw HaException("Home Assistant rejected the login: ${m.errorMessage ?: ""}", 401)
                    }
                }
            } catch (e: Exception) { ws.close(); throw e }
        }
    }
}

/**
 * Auto-setup's scan: registries, favorites, common_control suggestions and energy prefs over the WebSocket
 * API (none of it needs an admin user). The result is saved by [HomeStore].
 */
object Scanner {
    suspend fun scan(ctx: Context): Home {
        var currency: String? = null
        val home = HaSession.open(ctx) {
            val r = HashMap<String, Json?>()
            for ((key, cmd) in Ws.SCAN) r[key] = tryCall(cmd)
            currency = r["config"]?.str("currency")
            val states = r["states"]?.let { EntityState.parseList(it) } ?: emptyList()
            Home(states.associateBy { it.id },
                Registry.parse(r["areas"], r["floors"], r["devices"], r["entities"], r["labels"]),
                Ws.favoritesOf(r["favorites"]), Ws.suggestedOf(r["suggested"]), EnergySetup.parse(r["energy"]),
                r["config"]?.str("location_name") ?: "", System.currentTimeMillis())
        }
        HomeStore.save(ctx, home)
        Prefs(ctx).lastScanAt = home.scannedAt
        currency?.let { Prefs(ctx).haCurrency = it }
        return home
    }

    /** Today's change of every energy statistic (kWh), for the Energy widget. */
    suspend fun energyToday(ctx: Context, setup: EnergySetup): Map<String, Double> = HaSession.open(ctx) {
        setup.todayStats.mapNotNull { id -> Ws.changeOf(tryCall(Ws.statisticToday(id)))?.let { id to it } }.toMap()
    }

    /** Just the two lists that follow Home Assistant (for the hourly refresh of Favorites / Suggested). */
    suspend fun refreshLists(ctx: Context) {
        val cached = HomeStore.home(ctx, Snapshot.load(ctx) ?: return)
        val (favs, sugg) = HaSession.open(ctx) {
            Ws.favoritesOf(tryCall(Ws.SCAN.first { it.first == "favorites" }.second)) to Ws.suggestedOf(tryCall(Ws.SCAN.first { it.first == "suggested" }.second))
        }
        HomeStore.save(ctx, Home(cached.states, cached.registry, favs, sugg, cached.energy, cached.locationName, cached.scannedAt))
    }

    /** Widget plans from every dashboard's cards ("Later: import cards from the user's Lovelace dashboard"). */
    suspend fun dashboardPlans(ctx: Context, home: Home): List<WidgetSpec> = HaSession.open(ctx) {
        val dashboards = Lovelace.dashboards(tryCall(Ws.lovelaceDashboards()))
        dashboards.flatMap { d -> Lovelace.plans(tryCall(Ws.lovelaceConfig(d.urlPath)), home, d.title) }
    }

    /**
     * While the app is open: follow registry and dashboard changes. Returns when the connection drops;
     * [onChange] runs (on the IO thread) after each burst of events.
     */
    suspend fun watch(ctx: Context, onChange: suspend () -> Unit) = HaSession.open(ctx, readTimeoutMs = 0) {
        for (t in Ws.SYNC_EVENTS) tryCall(Ws.subscribe(t))
        while (true) {
            nextEvent() ?: break
            onChange()
        }
    }
}
