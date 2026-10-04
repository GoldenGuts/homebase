package in_.weenja.hawidgets

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import in_.weenja.hawidgets.widgets.Refresh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Homebase Pro: three widgets are free (Favorites, Tile, Weather · small); one purchase through Google
 * Play unlocks every widget, for good. The demo home shows everything, unlocked, so anyone (and the
 * store's reviewers) can see what they get.
 */
object Pro {
    /** Play Console → Monetize → In-app products: a one-time product with this id. */
    const val PRODUCT = "homebase_pro"
    val FREE = setOf("favorites", "t_bulb", "weather_s")
    private const val TAG = "HomebasePro"

    /** Bought through Google Play, unlocked by a code link, or a beta build (every widget while the beta runs, nothing kept). */
    fun unlocked(ctx: Context): Boolean = BuildConfig.BETA || ctx.getSharedPreferences("pro", Context.MODE_PRIVATE).let { it.getBoolean("unlocked", false) || it.getBoolean("redeemed", false) }

    private fun setUnlocked(ctx: Context, on: Boolean) {
        val was = unlocked(ctx)
        ctx.getSharedPreferences("pro", Context.MODE_PRIVATE).edit().putBoolean("unlocked", on).apply()
        if (was != unlocked(ctx)) CoroutineScope(Dispatchers.Default).launch { Refresh.all(ctx.applicationContext, fetch = false) }
    }

    /** Whether a catalog widget draws live content (true) or the Pro card. */
    fun allowed(ctx: Context, widgetId: String?): Boolean =
        widgetId == null || widgetId in FREE || unlocked(ctx) || !Prefs(ctx).isLoggedIn

    // ------------------------------------------------------------------ code links

    /**
     * Public half of the key that signs unlock links (`homebase://unlock?c=…`), X.509 DER in base64.
     * The developer's build fills this in; the private half never leaves the developer's computer.
     * Empty = links do nothing.
     */
    const val CODE_KEY = ""

    /**
     * Unlock with a code from a link: 8 random bytes (the code's id) + a P-256 signature of them.
     * Checked on the phone, no server: a forwarded link also unlocks the next phone, so links are for
     * friends. Strangers get Google Play promo codes (redeemed in the Play Store, seen as a purchase).
     */
    fun redeem(ctx: Context, code: String, key: String = CODE_KEY): Boolean {
        if (key.isEmpty()) return false
        return try {
            val raw = android.util.Base64.decode(code.trim(), android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
            if (raw.size != 72) return false
            val id = raw.copyOfRange(0, 8); val sig = raw.copyOfRange(8, 72)
            val pub = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(android.util.Base64.decode(key, android.util.Base64.DEFAULT)))
            val v = java.security.Signature.getInstance("SHA256withECDSA")
            v.initVerify(pub); v.update("homebase-pro:".toByteArray() + id)
            if (!v.verify(der(sig))) return false
            val sp = ctx.getSharedPreferences("pro", Context.MODE_PRIVATE)
            val idHex = id.joinToString("") { "%02x".format(it) }
            val used = sp.getStringSet("codes", emptySet()) ?: emptySet()
            if (idHex in used) return unlocked(ctx)
            val was = unlocked(ctx)
            sp.edit().putStringSet("codes", used + idHex).putBoolean("redeemed", true).apply()
            if (!was) CoroutineScope(Dispatchers.Default).launch { Refresh.all(ctx.applicationContext, fetch = false) }
            true
        } catch (e: Exception) { Log.w(TAG, "code: $e"); false }
    }

    /** Raw r‖s (32 + 32 bytes) → the DER sequence java.security expects. */
    private fun der(rs: ByteArray): ByteArray {
        fun int(b: ByteArray): ByteArray {
            var i = 0
            while (i < b.size - 1 && b[i] == 0.toByte()) i++
            val v = b.copyOfRange(i, b.size)
            val body = if (v[0] < 0) byteArrayOf(0) + v else v
            return byteArrayOf(0x02, body.size.toByte()) + body
        }
        val seq = int(rs.copyOfRange(0, 32)) + int(rs.copyOfRange(32, 64))
        return byteArrayOf(0x30, seq.size.toByte()) + seq
    }

    // ------------------------------------------------------------------ Google Play Billing

    private var client: BillingClient? = null
    private var details: ProductDetails? = null
    /** Called on the main thread when a purchase finished (true) or failed / was cancelled (false, message). */
    var onResult: ((Boolean, String?) -> Unit)? = null

    /** Null when Google Play's billing service is missing or broken on this device. */
    private fun client(ctx: Context): BillingClient? = client ?: try { BillingClient.newBuilder(ctx.applicationContext)
        .setListener { result, purchases ->
            val app = ctx.applicationContext
            when (result.responseCode) {
                BillingClient.BillingResponseCode.OK -> purchases?.forEach { handle(app, it) }
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> CoroutineScope(Dispatchers.Main).launch { restore(app); onResult?.invoke(unlocked(app), null) }
                BillingClient.BillingResponseCode.USER_CANCELED -> onResult?.invoke(false, null)
                else -> onResult?.invoke(false, result.debugMessage.ifBlank { "Google Play said no (${result.responseCode})" })
            }
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build().also { client = it } } catch (e: Throwable) { Log.w(TAG, "Play Billing unavailable", e); null }

    private suspend fun ready(ctx: Context): BillingClient? {
        val c = client(ctx) ?: return null
        if (c.isReady) return c
        return suspendCancellableCoroutine { cont ->
            try { c.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (cont.isActive) cont.resume(if (result.responseCode == BillingClient.BillingResponseCode.OK) c else null)
                }
                override fun onBillingServiceDisconnected() { client = null; if (cont.isActive) cont.resume(null) }
            }) } catch (e: Throwable) { Log.w(TAG, "billing connection", e); if (cont.isActive) cont.resume(null) }
        }
    }

    private suspend fun product(ctx: Context): ProductDetails? {
        details?.let { return it }
        val c = ready(ctx) ?: return null
        val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT).setProductType(BillingClient.ProductType.INAPP).build())).build()
        return suspendCancellableCoroutine { cont ->
            c.queryProductDetailsAsync(params) { _, result ->
                details = result.productDetailsList.firstOrNull()
                if (cont.isActive) cont.resume(details)
            }
        }
    }

    /** The localized price from Google Play ("$8.99", "8,99 €"), or null when Play is not reachable. */
    suspend fun price(ctx: Context): String? = product(ctx)?.oneTimePurchaseOfferDetails?.formattedPrice

    /** Open Google Play's purchase sheet. False when the product could not be loaded. */
    suspend fun buy(activity: Activity): Boolean {
        val pd = product(activity) ?: return false
        val c = ready(activity) ?: return false
        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(pd).build())).build()
        return c.launchBillingFlow(activity, flow).responseCode == BillingClient.BillingResponseCode.OK
    }

    /**
     * Ask Google Play what this account owns. [explicit] = the user tapped Restore: trust the answer.
     * At app start Play sometimes answers OK with an empty list, so a background check re-locks only
     * after two launches in a row found nothing (that still catches refunds).
     */
    suspend fun restore(ctx: Context, explicit: Boolean = false): Boolean {
        val c = ready(ctx) ?: return unlocked(ctx)
        val purchases: List<Purchase> = suspendCancellableCoroutine<List<Purchase>?> { cont ->
            c.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result, list ->
                if (cont.isActive) cont.resume(if (result.responseCode == BillingClient.BillingResponseCode.OK) list else null)
            }
        } ?: return unlocked(ctx)
        val owned = purchases.any { PRODUCT in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
        purchases.forEach { handle(ctx, it, notify = false) }
        val sp = ctx.getSharedPreferences("pro", Context.MODE_PRIVATE)
        if (owned || explicit || !unlocked(ctx)) { sp.edit().putInt("misses", 0).apply(); setUnlocked(ctx, owned) }
        else {
            val misses = sp.getInt("misses", 0) + 1
            sp.edit().putInt("misses", misses).apply()
            if (misses >= 2) setUnlocked(ctx, false)
        }
        return unlocked(ctx)
    }

    private fun handle(ctx: Context, p: Purchase, notify: Boolean = true) {
        if (PRODUCT !in p.products) return
        when (p.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                setUnlocked(ctx, true)
                if (notify) CoroutineScope(Dispatchers.Main).launch { onResult?.invoke(true, null) }
                // Google refunds purchases that are not acknowledged within three days
                if (!p.isAcknowledged) client?.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { r ->
                    if (r.responseCode != BillingClient.BillingResponseCode.OK) Log.w(TAG, "acknowledge: ${r.debugMessage}")
                }
            }
            Purchase.PurchaseState.PENDING -> if (notify) CoroutineScope(Dispatchers.Main).launch { onResult?.invoke(false, "Payment pending. Homebase unlocks as soon as Google Play confirms it.") }
            else -> {}
        }
    }
}
