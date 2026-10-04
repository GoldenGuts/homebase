package in_.weenja.hawidgets.ha

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executor

/**
 * Finds Home Assistant servers on the local network: NSD (mDNS) discovery of `_home-assistant._tcp`,
 * holding a MulticastLock while it runs. The TXT records carry location_name, internal_url,
 * external_url and base_url.
 */
class Discovery(context: Context, private val onFound: (Server) -> Unit) {
    class Server(val name: String, val urls: List<String>, val version: String, val uuid: String) {
        /** The URL to try first: the LAN one, else the external one. */
        val url: String get() = urls.first()
    }

    private val ctx = context.applicationContext
    private val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private var lock: WifiManager.MulticastLock? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val seen = HashSet<String>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    fun start() {
        if (listener != null) return
        lock = (ctx.getSystemService(Context.WIFI_SERVICE) as WifiManager).createMulticastLock("homebase-discovery").apply { setReferenceCounted(false); acquire() }
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(Api.TAG, "nsd start failed $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { main.post { enqueue(info) } }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        listener = l
        try { nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, l) } catch (e: Exception) { Log.w(Api.TAG, "nsd: $e") }
    }

    fun stop() {
        listener?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        listener = null
        try { lock?.release() } catch (_: Exception) {}
        lock = null
    }

    private fun enqueue(info: NsdServiceInfo) {
        if (!seen.add(info.serviceName)) return
        queue.addLast(info)
        next()
    }

    /** Older Android resolves one service at a time. */
    @Suppress("DEPRECATION")
    private fun next() {
        if (resolving) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        if (Build.VERSION.SDK_INT >= 34) {
            val exec = Executor { main.post(it) }
            val cb = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) { resolving = false; next() }
                override fun onServiceUpdated(si: NsdServiceInfo) {
                    publish(si)
                    try { nsd.unregisterServiceInfoCallback(this) } catch (_: Exception) {}
                }
                override fun onServiceLost() {}
                override fun onServiceInfoCallbackUnregistered() { resolving = false; next() }
            }
            try { nsd.registerServiceInfoCallback(info, exec, cb) } catch (e: Exception) { resolving = false; next() }
        } else {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) { main.post { resolving = false; next() } }
                override fun onServiceResolved(si: NsdServiceInfo) { main.post { publish(si); resolving = false; next() } }
            })
        }
    }

    @Suppress("DEPRECATION")
    private fun publish(si: NsdServiceInfo) {
        val attrs = si.attributes.mapValues { (_, v) -> v?.let { String(it) } ?: "" }
        val host = if (Build.VERSION.SDK_INT >= 34) si.hostAddresses.firstOrNull()?.hostAddress else si.host?.hostAddress
        val direct = host?.let { h -> "http://${if (h.contains(':')) "[$h]" else h}:${si.port}" }
        val urls = listOfNotNull(attrs["internal_url"], attrs["base_url"], attrs["external_url"], direct)
            .map { it.trim().trimEnd('/') }.filter { it.startsWith("http") }.distinct()
        if (urls.isEmpty()) return
        onFound(Server(attrs["location_name"]?.takeIf { it.isNotBlank() } ?: si.serviceName, urls, attrs["version"] ?: "", attrs["uuid"] ?: ""))
    }

    companion object { const val TYPE = "_home-assistant._tcp" }
}
