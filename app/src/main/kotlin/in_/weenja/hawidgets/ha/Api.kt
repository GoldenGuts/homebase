package in_.weenja.hawidgets.ha

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import in_.weenja.hawidgets.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

class HaException(message: String, val code: Int = 0) : IOException(message)

/**
 * Small Home Assistant client: OAuth token handling + REST API, on HttpURLConnection so the app has
 * no HTTP dependency. Every call first tries the primary URL and then the LAN fallback.
 */
class Api(context: Context) {
    private val ctx = context.applicationContext
    val prefs = Prefs(ctx)

    class Response(val code: Int, val body: String) {
        val ok get() = code in 200..299
        fun json() = JSONObject(body)
        fun jsonArray() = JSONArray(body)
    }

    // ---------------------------------------------------------------- auth

    /** client_id for the OAuth flow: the HA host itself, so the redirect back is on the same host. */
    fun clientIdFor(base: String) = "$base/"
    fun redirectUriFor(base: String) = "$base/hawidgets/callback"
    fun authorizeUrl(base: String): String {
        val cid = URLEncoder.encode(clientIdFor(base), "UTF-8")
        val red = URLEncoder.encode(redirectUriFor(base), "UTF-8")
        return "$base/auth/authorize?response_type=code&client_id=$cid&redirect_uri=$red&state=hawidgets"
    }

    /** Exchange the authorization code from the login page for a refresh + access token. */
    suspend fun exchangeCode(base: String, code: String) = withContext(Dispatchers.IO) {
        val cid = clientIdFor(base)
        val r = raw(base, "POST", "/auth/token", form(mapOf("grant_type" to "authorization_code", "code" to code, "client_id" to cid)),
            "application/x-www-form-urlencoded", auth = false)
        if (!r.ok) throw HaException("Token exchange failed: ${r.code} ${r.body.take(200)}", r.code)
        val j = r.json()
        prefs.baseUrl = base
        prefs.clientId = cid
        prefs.refreshToken = j.getString("refresh_token")
        prefs.accessToken = j.getString("access_token")
        prefs.accessExpiresAt = System.currentTimeMillis() + j.optLong("expires_in", 1800) * 1000
        prefs.lastError = null
    }

    /** Current bearer token: the pasted long-lived token, else a fresh OAuth access token. */
    private suspend fun bearer(base: String, force: Boolean = false): String {
        prefs.longLivedToken?.let { return it }
        val access = prefs.accessToken
        if (!force && access != null && System.currentTimeMillis() < prefs.accessExpiresAt - 60_000) return access
        val refresh = prefs.refreshToken ?: throw HaException("Not logged in", 401)
        val cid = prefs.clientId ?: clientIdFor(prefs.baseUrl)
        val r = raw(base, "POST", "/auth/token", form(mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to cid)),
            "application/x-www-form-urlencoded", auth = false)
        if (r.code == 400 || r.code == 401) {
            // HA revoked the refresh token (user removed it, or the HA instance was reset): the user must log in again.
            prefs.logout()
            throw HaException("Login expired, open the app to log in again", r.code)
        }
        if (!r.ok) throw HaException("Token refresh failed: ${r.code}", r.code)
        val j = r.json()
        prefs.accessToken = j.getString("access_token")
        prefs.accessExpiresAt = System.currentTimeMillis() + j.optLong("expires_in", 1800) * 1000
        return prefs.accessToken!!
    }

    /** A valid access token for [base] (the WebSocket API authenticates with it). */
    suspend fun accessToken(base: String): String = withContext(Dispatchers.IO) { bearer(base) }

    /** The URLs to try, primary first. */
    val bases: List<String> get() = listOf(prefs.baseUrl, prefs.altUrl).filter { it.isNotBlank() }.distinct()

    // ---------------------------------------------------------------- REST

    suspend fun states(): JSONArray = withContext(Dispatchers.IO) {
        val r = call("GET", "/api/states", null)
        if (!r.ok) throw HaException("states: ${r.code}", r.code)
        r.jsonArray()
    }

    suspend fun state(entityId: String): JSONObject? = withContext(Dispatchers.IO) {
        val r = call("GET", "/api/states/$entityId", null)
        if (r.ok) r.json() else null
    }

    suspend fun callService(domain: String, service: String, data: JSONObject?): Boolean = withContext(Dispatchers.IO) {
        val r = call("POST", "/api/services/$domain/$service", (data ?: JSONObject()).toString())
        if (!r.ok) Log.w(TAG, "service $domain.$service -> ${r.code} ${r.body.take(200)}")
        r.ok
    }

    /** Services that answer with data (todo.get_items): `?return_response` -> service_response. */
    suspend fun callServiceResponse(domain: String, service: String, data: JSONObject?): JSONObject? = withContext(Dispatchers.IO) {
        val r = call("POST", "/api/services/$domain/$service?return_response", (data ?: JSONObject()).toString())
        if (!r.ok) { Log.w(TAG, "service $domain.$service -> ${r.code}"); null } else r.json().optJSONObject("service_response")
    }

    /** Events of one calendar between two ISO times (GET /api/calendars/<id>). */
    suspend fun calendarEvents(entityId: String, startIso: String, endIso: String): JSONArray? = withContext(Dispatchers.IO) {
        val r = call("GET", "/api/calendars/$entityId?start=${URLEncoder.encode(startIso, "UTF-8")}&end=${URLEncoder.encode(endIso, "UTF-8")}", null)
        if (r.ok) r.jsonArray() else { Log.w(TAG, "calendar $entityId -> ${r.code}"); null }
    }

    /** State history of [ids] since [startIso], minimal rows without attributes (GET /api/history/period). */
    suspend fun history(ids: List<String>, startIso: String): JSONArray? = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext null
        val r = call("GET", "/api/history/period/${URLEncoder.encode(startIso, "UTF-8")}?filter_entity_id=${ids.joinToString(",")}&minimal_response&no_attributes", null)
        if (r.ok) r.jsonArray() else { Log.w(TAG, "history -> ${r.code}"); null }
    }

    /** Who am I: needs no token, but a working URL. */
    suspend fun ping(base: String): Boolean = withContext(Dispatchers.IO) {
        try { raw(base, "GET", "/api/", null, null, auth = false).code in listOf(200, 401) } catch (e: Exception) { false }
    }

    /** Media artwork (entity_picture) with the bearer token, cached on disk by URL hash. */
    suspend fun image(pathOrUrl: String, cacheKey: String? = null, fresh: Boolean = false, keep: Boolean = false): Bitmap? = withContext(Dispatchers.IO) {
        val key = cacheKey?.let { sha1(it) } ?: sha1(pathOrUrl)
        // camera snapshots and avatars are kept apart from album art, which is pruned
        val f = File(ctx.cacheDir, (if (keep) "keep_" else "art_") + key)
        if (!fresh && f.exists()) BitmapFactory.decodeFile(f.path)?.let { return@withContext it }
        try {
            val bytes = withBase { base ->
                val url = if (pathOrUrl.startsWith("http")) pathOrUrl else base + pathOrUrl
                val c = open(url, "GET", null, null)
                val token = if (pathOrUrl.startsWith("http") && !pathOrUrl.startsWith(base)) null else bearer(base)
                token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
                c.connect()
                if (c.responseCode !in 200..299) throw HaException("image ${c.responseCode}", c.responseCode)
                c.inputStream.use { it.readBytes() }
            }
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
            // keep the cache small: ~ one image per track, purge older ones
            ctx.cacheDir.listFiles { x -> x.name.startsWith("art_") && x.name != "art_" + sha1(MAC_SCREEN) }?.sortedByDescending { it.lastModified() }?.drop(12)?.forEach { it.delete() }
            f.writeBytes(bytes)
            bmp
        } catch (e: Exception) {
            Log.w(TAG, "image failed: $e"); null
        }
    }

    // ---------------------------------------------------------------- plumbing

    private suspend fun call(method: String, path: String, body: String?): Response = withBase { base ->
        var r = raw(base, method, path, body, "application/json", auth = true)
        if (r.code == 401 && prefs.longLivedToken == null) {
            bearer(base, force = true)
            r = raw(base, method, path, body, "application/json", auth = true)
        }
        r
    }

    /** Runs [block] against the primary URL, then the fallback if the primary is unreachable. */
    private suspend fun <T> withBase(block: suspend (String) -> T): T {
        val bases = listOf(prefs.baseUrl, prefs.altUrl).filter { it.isNotBlank() }.distinct()
        var last: Exception? = null
        for (b in bases) {
            try { return block(b) } catch (e: HaException) { throw e } catch (e: Exception) { last = e; Log.w(TAG, "$b unreachable: $e") }
        }
        throw last ?: IOException("No HA URL configured")
    }

    private suspend fun raw(base: String, method: String, path: String, body: String?, contentType: String?, auth: Boolean): Response {
        val c = open(base + path, method, body, contentType)
        if (auth) c.setRequestProperty("Authorization", "Bearer ${bearer(base)}")
        try {
            body?.let { c.outputStream.use { os -> os.write(it.toByteArray()) } }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { String(it.readBytes()) } ?: ""
            return Response(code, text)
        } finally { c.disconnect() }
    }

    private fun open(url: String, method: String, body: String?, contentType: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5000
        c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/json, image/*")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", contentType ?: "application/json") }
        return c
    }

    private fun form(m: Map<String, String>) = m.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object { const val TAG = "hawidgets"; const val MAC_SCREEN = "mac_screen" }
}
