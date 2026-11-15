package com.torchons.app.data.billing

import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryPurchasesParams
import com.torchons.app.data.api.ApiService
import com.torchons.app.data.local.TokenManager
import com.torchons.app.data.model.BillingVerifyRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Service Hilt singleton autour de Google Play BillingClient (v0.30.x).
 *
 * STATUT : plomberie. Aucun paywall actif a ce stade. Le service initialise
 * la connexion au cold start et expose deux helpers :
 *  - [restoreEntitlementsSilently] : appele au login pour synchroniser le
 *    state premium si l'user a deja achete sur ce device Google Play.
 *  - [onPurchaseUpdated] : callback declenche par BillingClient quand un
 *    achat se termine. Renvoie le purchase_token au backend via
 *    POST /api/billing/verify.
 *
 * Le vrai flow d'achat (`launchBillingFlow` + selection de produit dans
 * Play Console + UI paywall) sera ajoute en v2.
 *
 * Important : les operations BillingClient sont async. On utilise un
 * CoroutineScope singleton interne plutot que viewModelScope pour ne pas
 * etre lie au cycle de vie d'un ecran.
 */
@Singleton
class BillingService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: ApiService,
    private val tokenManager: TokenManager,
) : PurchasesUpdatedListener, BillingClientStateListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()

    @Volatile
    private var isReady: Boolean = false

    /**
     * Demarre la connexion au service Google Play. A appeler une fois au
     * boot de l'app (depuis MainActivity). Idempotent.
     */
    fun start() {
        if (billingClient.isReady) {
            isReady = true
            return
        }
        billingClient.startConnection(this)
    }

    override fun onBillingSetupFinished(billingResult: BillingResult) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
            isReady = true
            Log.i(TAG, "BillingClient connected")
            // Au cold start on tente de restaurer les achats existants en
            // best-effort. Si l'user n'a aucun token, on ne fait rien.
            scope.launch { restoreEntitlementsSilently() }
        } else {
            Log.w(
                TAG,
                "BillingClient setup failed code=${billingResult.responseCode} " +
                    "msg=${billingResult.debugMessage}",
            )
        }
    }

    override fun onBillingServiceDisconnected() {
        isReady = false
        Log.w(TAG, "BillingClient disconnected, will retry on next call")
    }

    /**
     * Interroge Play Store pour les achats actifs (subscriptions et
     * non-consumables) du compte Google Play du device. Si on en trouve
     * un, on l'envoie au backend pour qu'il flippe `foyer.is_premium`.
     *
     * Cas d'usage : reinstall de l'app, changement de device avec meme
     * compte Google Play, etc.
     */
    suspend fun restoreEntitlementsSilently() {
        if (!isReady) return
        if (tokenManager.tokenFlow.first().isNullOrEmpty()) {
            // Pas de JWT -> pas la peine de notifier le backend (qui
            // refuserait avec 401 de toute facon).
            return
        }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        val (result, purchases) = queryPurchasesSuspend(params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            return
        }
        purchases.forEach { purchase ->
            if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                notifyBackend(purchase)
            }
        }
    }

    /**
     * Callback BillingClient apres un achat lance via launchBillingFlow.
     * Pas encore appele aujourd'hui faute de UI paywall, mais cable pour
     * la v2.
     */
    override fun onPurchasesUpdated(
        billingResult: BillingResult,
        purchases: MutableList<Purchase>?,
    ) {
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            return
        }
        purchases?.forEach { purchase ->
            if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                scope.launch { notifyBackend(purchase) }
            }
        }
    }

    private suspend fun notifyBackend(purchase: Purchase) {
        runCatching {
            val deviceId = tokenManager.getOrCreateDeviceId()
            val productId = purchase.products.firstOrNull() ?: return
            apiService.billingVerify(
                BillingVerifyRequest(
                    purchaseToken = purchase.purchaseToken,
                    productId = productId,
                    deviceId = deviceId,
                )
            )
        }.onFailure {
            Log.w(TAG, "billingVerify failed", it)
        }
    }

    private suspend fun queryPurchasesSuspend(
        params: QueryPurchasesParams,
    ): Pair<BillingResult, List<Purchase>> = suspendCancellableCoroutine { cont ->
        billingClient.queryPurchasesAsync(params) { result, purchases ->
            cont.resume(result to purchases)
        }
    }

    companion object {
        private const val TAG = "BillingService"
    }
}
