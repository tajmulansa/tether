package com.onlyneeded.ozmora

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams

/**
 * One-time "Remove ads" purchase (product id [PRODUCT_ID], create it in Play Console as a managed in-app product).
 * The game reaches it through `window.AndroidShop` and hears back through `window.tetherShopEvent(kind, value)`:
 *   ("owned", "1"|"0")   the purchase state changed (also sent once after start-up)
 *   ("price", "$2.99")   the localized price is known
 *   ("result", "cancelled"|"failed"|"pending")
 * The owned flag is also cached on the device so ads stay off when the phone is offline.
 */
class BillingManager(
    private val activity: MainActivity,
    private val web: WebView,
    private val onOwned: (Boolean) -> Unit,
) : PurchasesUpdatedListener {

    companion object { const val PRODUCT_ID = "remove_ads" }

    private val main = Handler(Looper.getMainLooper())
    private val prefs = activity.getSharedPreferences("ozmora", Context.MODE_PRIVATE)
    private var details: ProductDetails? = null
    private var price = ""
    private var started = false

    @Volatile var owned: Boolean = prefs.getBoolean("noads", false)
        private set

    private val client: BillingClient = BillingClient.newBuilder(activity)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    val bridge = Bridge()

    inner class Bridge {
        @JavascriptInterface fun isNoAds(): Boolean = owned
        @JavascriptInterface fun price(): String = price
        @JavascriptInterface fun buy() { main.post { launch() } }
        @JavascriptInterface fun restore() { main.post { if (client.isReady) refresh() else connect() } }
    }

    fun start() {
        if (started) return
        started = true
        send("owned", if (owned) "1" else "0")
        connect()
    }

    fun end() { try { client.endConnection() } catch (_: Exception) {} }

    private fun connect() {
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(r: BillingResult) {
                if (r.responseCode != BillingClient.BillingResponseCode.OK) { Log.d("OzmoraBilling", "setup ${r.responseCode}"); return }
                loadDetails(); refresh()
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    private fun loadDetails() {
        val p = QueryProductDetailsParams.newBuilder().setProductList(listOf(
            QueryProductDetailsParams.Product.newBuilder().setProductId(PRODUCT_ID).setProductType(BillingClient.ProductType.INAPP).build()
        )).build()
        client.queryProductDetailsAsync(p) { r, list ->
            if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                details = list.firstOrNull()
                price = details?.oneTimePurchaseOfferDetails?.formattedPrice ?: ""
                if (price.isNotEmpty()) send("price", price)
            }
        }
    }

    /** Asks Google Play what this account owns (restores the purchase on a new phone). */
    private fun refresh() {
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { r, list ->
            if (r.responseCode == BillingClient.BillingResponseCode.OK) {
                val has = list.any { it.products.contains(PRODUCT_ID) && it.purchaseState == Purchase.PurchaseState.PURCHASED }
                list.forEach { handle(it) }
                if (!has) setOwned(false)
            }
        }
    }

    private fun launch() {
        val d = details
        if (d == null || !client.isReady) { send("result", "failed"); if (!client.isReady) connect(); return }
        val params = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()
        )).build()
        val r = client.launchBillingFlow(activity, params)
        if (r.responseCode != BillingClient.BillingResponseCode.OK) send("result", "failed")
    }

    override fun onPurchasesUpdated(r: BillingResult, list: MutableList<Purchase>?) {
        when (r.responseCode) {
            BillingClient.BillingResponseCode.OK -> list?.forEach { handle(it) }
            BillingClient.BillingResponseCode.USER_CANCELED -> send("result", "cancelled")
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            else -> send("result", "failed")
        }
    }

    private fun handle(p: Purchase) {
        if (!p.products.contains(PRODUCT_ID)) return
        when (p.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                if (!p.isAcknowledged) {
                    client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { }
                }
                setOwned(true)
            }
            Purchase.PurchaseState.PENDING -> send("result", "pending")
        }
    }

    private fun setOwned(v: Boolean) {
        main.post {
            val changed = v != owned
            owned = v
            prefs.edit().putBoolean("noads", v).apply()
            onOwned(v)
            if (changed || v) send("owned", if (v) "1" else "0")
        }
    }

    private fun send(kind: String, value: String) {
        val safe = value.replace("\\", " ").replace("'", " ")
        main.post { web.evaluateJavascript("window.tetherShopEvent&&window.tetherShopEvent('$kind','$safe')", null) }
    }
}
