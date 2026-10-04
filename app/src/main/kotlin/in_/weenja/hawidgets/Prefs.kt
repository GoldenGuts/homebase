package in_.weenja.hawidgets

import android.content.Context
import android.content.SharedPreferences

/** App settings + Home Assistant tokens. One private preferences file. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.applicationContext.getSharedPreferences("hawidgets", Context.MODE_PRIVATE)

    /** Primary Home Assistant URL (https://ha.example.com or http://192.168.1.10:8123). Empty until set up. */
    var baseUrl: String
        get() = sp.getString("base_url", DEFAULT_BASE) ?: DEFAULT_BASE
        set(v) = sp.edit().putString("base_url", v.trim().trimEnd('/')).apply()

    /** Optional fallback URL, tried when the primary one does not answer (a LAN IP, for example). */
    var altUrl: String
        get() = sp.getString("alt_url", DEFAULT_ALT) ?: DEFAULT_ALT
        set(v) = sp.edit().putString("alt_url", v.trim().trimEnd('/')).apply()

    /** Optional long-lived access token pasted by the user. Wins over the OAuth tokens when set. */
    var longLivedToken: String?
        get() = sp.getString("llat", null)?.takeIf { it.isNotBlank() }
        set(v) = sp.edit().putString("llat", v?.trim()).apply()

    var accessToken: String?
        get() = sp.getString("access", null)
        set(v) = sp.edit().putString("access", v).apply()

    var accessExpiresAt: Long
        get() = sp.getLong("access_exp", 0L)
        set(v) = sp.edit().putLong("access_exp", v).apply()

    var refreshToken: String?
        get() = sp.getString("refresh", null)
        set(v) = sp.edit().putString("refresh", v).apply()

    /** client_id the refresh token was issued for. HA checks it on every refresh. */
    var clientId: String?
        get() = sp.getString("client_id", null)
        set(v) = sp.edit().putString("client_id", v).apply()

    var lastError: String?
        get() = sp.getString("last_error", null)
        set(v) = sp.edit().putString("last_error", v).apply()

    var lastFetchAt: Long
        get() = sp.getLong("last_fetch", 0L)
        set(v) = sp.edit().putLong("last_fetch", v).apply()

    /** The welcome screens were seen (connected, or chose the demo home). */
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false) || isLoggedIn
        set(v) = sp.edit().putBoolean("onboarded", v).apply()
    var lastScanAt: Long
        get() = sp.getLong("last_scan", 0L)
        set(v) = sp.edit().putLong("last_scan", v).apply()
    var lastListsAt: Long
        get() = sp.getLong("last_lists", 0L)
        set(v) = sp.edit().putLong("last_lists", v).apply()
    var lastCalendarAt: Long
        get() = sp.getLong("last_calendar", 0L)
        set(v) = sp.edit().putLong("last_calendar", v).apply()
    var lastHistoryAt: Long
        get() = sp.getLong("last_history", 0L)
        set(v) = sp.edit().putLong("last_history", v).apply()
    var lastEnergyAt: Long
        get() = sp.getLong("last_energy", 0L)
        set(v) = sp.edit().putLong("last_energy", v).apply()
    var lastPreviewAt: Long
        get() = sp.getLong("last_preview", 0L)
        set(v) = sp.edit().putLong("last_preview", v).apply()
    var previewCursor: Int
        get() = sp.getInt("preview_cursor", 0)
        set(v) = sp.edit().putInt("preview_cursor", v).apply()
    /** Name of the Home Assistant from discovery or get_config, for the status line. */
    var serverName: String
        get() = sp.getString("server_name", "") ?: ""
        set(v) = sp.edit().putString("server_name", v).apply()
    /** Home Assistant's currency (ISO code from get_config), the default for the money widgets. */
    var haCurrency: String
        get() = sp.getString("ha_currency", "") ?: ""
        set(v) = sp.edit().putString("ha_currency", v).apply()

    /** Refresh interval in minutes for the background alarm (5..60; Doze allows one about every 9 min anyway). */
    var refreshMinutes: Int
        get() = sp.getInt("refresh_min", 10).coerceIn(5, 60)
        set(v) = sp.edit().putInt("refresh_min", v.coerceIn(5, 60)).apply()

    /** Two-tap confirmation for destructive chips: epoch ms of the first tap, keyed by action id. */
    fun armedAt(key: String): Long = sp.getLong("armed_$key", 0L)
    fun arm(key: String, at: Long) = sp.edit().putLong("armed_$key", at).apply()

    val isLoggedIn: Boolean get() = baseUrl.isNotEmpty() && (longLivedToken != null || refreshToken != null)

    fun logout() {
        sp.edit().remove("access").remove("access_exp").remove("refresh").remove("client_id").remove("llat").remove("last_scan").apply()
    }

    companion object {
        const val DEFAULT_BASE = ""
        const val DEFAULT_ALT = ""
    }
}
