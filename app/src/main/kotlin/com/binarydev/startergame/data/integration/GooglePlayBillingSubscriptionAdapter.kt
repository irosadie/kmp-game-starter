package com.binarydev.startergame.data.integration

import android.app.Activity
import android.content.Context
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
import com.binarydev.startergame.core.contracts.BillingActionResult
import com.binarydev.startergame.core.contracts.ProgressRepository
import com.binarydev.startergame.core.contracts.SubscriptionBillingRepository
import com.binarydev.startergame.core.contracts.SubscriptionEntitlementState
import com.binarydev.startergame.core.contracts.SubscriptionVerificationRequestDto
import com.binarydev.startergame.data.preferences.DataStoreDevicePreferencesRepository
import com.binarydev.startergame.data.remote.GoBackendApiClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Real Google Play Billing Library v7 (`BillingClient`) implementation for "Remove Ads" Subscription
 * connected to Google Play / Google Pay and Backend Go verification (`POST /v1/games/{gameId}/subscriptions/verify`).
 */
class GooglePlayBillingSubscriptionAdapter(
    context: Context,
    private val activityProvider: () -> Activity?,
    private val gameId: String,
    private val subscriptionProductId: String,
    private val basePlanId: String,
    private val preferencesRepository: DataStoreDevicePreferencesRepository,
    private val progressRepositoryProvider: () -> ProgressRepository,
    private val backendApiClientProvider: () -> GoBackendApiClient,
    private val sessionTokenProvider: () -> String? = { null }
) : SubscriptionBillingRepository, PurchasesUpdatedListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _entitlementState = MutableStateFlow(
        SubscriptionEntitlementState(
            isAdFreeSubscribed = false,
            productId = subscriptionProductId,
            basePlanId = basePlanId
        )
    )
    override val entitlementFlow: Flow<SubscriptionEntitlementState> = _entitlementState.asStateFlow()

    private var cachedProductDetails: ProductDetails? = null
    private var pendingPurchaseDeferred: CompletableDeferred<BillingActionResult>? = null

    private val billingClient: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .build()
        )
        .build()

    init {
        scope.launch {
            val saved = preferencesRepository.getSavedSubscriptionEntitlement()
            if (saved != null) {
                _entitlementState.value = saved.copy(
                    productId = subscriptionProductId,
                    basePlanId = basePlanId
                )
            }
        }
    }

    override suspend fun getEntitlement(): SubscriptionEntitlementState {
        val saved = preferencesRepository.getSavedSubscriptionEntitlement()
        if (saved != null && saved != _entitlementState.value) {
            _entitlementState.value = saved
        }
        return _entitlementState.value
    }

    suspend fun connectAndRefreshProductIfAvailable(): Boolean = withContext(Dispatchers.Main) {
        if (billingClient.isReady) {
            querySubscriptionProductDetailsSuspend()
            return@withContext true
        }
        val connected = suspendCancellableCoroutine<Boolean> { continuation ->
            runCatching {
                billingClient.startConnection(object : BillingClientStateListener {
                    override fun onBillingSetupFinished(billingResult: BillingResult) {
                        val isOk = billingResult.responseCode == BillingClient.BillingResponseCode.OK
                        _entitlementState.value = _entitlementState.value.copy(
                            isPlayBillingConnected = isOk
                        )
                        if (continuation.isActive) {
                            continuation.resume(isOk)
                        }
                    }

                    override fun onBillingServiceDisconnected() {
                        _entitlementState.value = _entitlementState.value.copy(
                            isPlayBillingConnected = false
                        )
                    }
                })
            }.onFailure {
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }
        if (connected) {
            querySubscriptionProductDetailsSuspend()
        }
        connected
    }

    private suspend fun querySubscriptionProductDetailsSuspend(): ProductDetails? {
        if (!billingClient.isReady) return null
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(subscriptionProductId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        return suspendCancellableCoroutine { continuation ->
            billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
                val details = if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    productDetailsList.firstOrNull()
                } else {
                    null
                }
                if (details != null) {
                    cachedProductDetails = details
                    val price = details.subscriptionOfferDetails
                        ?.firstOrNull()
                        ?.pricingPhases
                        ?.pricingPhaseList
                        ?.firstOrNull()
                        ?.formattedPrice
                    if (!price.isNullOrBlank()) {
                        _entitlementState.value = _entitlementState.value.copy(
                            formattedPrice = price
                        )
                    }
                }
                if (continuation.isActive) {
                    continuation.resume(details)
                }
            }
        }
    }

    override suspend fun launchRemoveAdsSubscriptionFlow(): BillingActionResult = withContext(Dispatchers.Main) {
        val activity = activityProvider()
            ?: return@withContext BillingActionResult.Error("Activity belum siap untuk membuka Google Play Billing.")

        val connected = connectAndRefreshProductIfAvailable()
        if (!connected) {
            return@withContext BillingActionResult.Error(
                "Google Play Billing belum terhubung di perangkat/emulator ini. Pastikan akun Google login di Play Store (atau gunakan tombol Uji Sandbox di bawah untuk simulasi)."
            )
        }

        val details = cachedProductDetails ?: querySubscriptionProductDetailsSuspend()
            ?: return@withContext BillingActionResult.Error(
                "Produk langganan '$subscriptionProductId' ($basePlanId) belum terdaftar di Google Play Console untuk package ID ini. Gunakan tombol 'Uji Sandbox Langganan' di bawah selama pengembangan lokal."
            )

        val selectedOffer = details.subscriptionOfferDetails?.firstOrNull {
            it.basePlanId == basePlanId
        } ?: details.subscriptionOfferDetails?.firstOrNull()
            ?: return@withContext BillingActionResult.Error(
                "Offer/Base Plan '$basePlanId' tidak ditemukan pada produk '$subscriptionProductId'."
            )

        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(selectedOffer.offerToken)
            .build()

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()

        val deferred = CompletableDeferred<BillingActionResult>()
        pendingPurchaseDeferred = deferred

        val launchResult = billingClient.launchBillingFlow(activity, flowParams)
        if (launchResult.responseCode != BillingClient.BillingResponseCode.OK) {
            pendingPurchaseDeferred = null
            return@withContext BillingActionResult.Error(
                "Gagal membuka sheet Google Play (${launchResult.responseCode}): ${launchResult.debugMessage}"
            )
        }

        deferred.await()
    }

    override fun onPurchasesUpdated(
        billingResult: BillingResult,
        purchases: MutableList<Purchase>?
    ) {
        scope.launch {
            val deferred = pendingPurchaseDeferred
            pendingPurchaseDeferred = null
            when (billingResult.responseCode) {
                BillingClient.BillingResponseCode.OK -> {
                    val subPurchase = purchases?.firstOrNull { purchase ->
                        purchase.products.contains(subscriptionProductId) || purchase.products.isNotEmpty()
                    }
                    if (subPurchase != null) {
                        val result = handleVerifiedPurchase(subPurchase, isRestore = false)
                        deferred?.complete(result)
                    } else {
                        deferred?.complete(BillingActionResult.Error("Data pembelian langganan kosong."))
                    }
                }
                BillingClient.BillingResponseCode.USER_CANCELED -> {
                    val msg = "Langganan Google Play dibatalkan. Iklan tetap aktif."
                    _entitlementState.value = _entitlementState.value.copy(statusMessage = msg)
                    deferred?.complete(BillingActionResult.Cancelled(msg))
                }
                else -> {
                    val msg = "Transaksi Google Play gagal (code=${billingResult.responseCode}): ${billingResult.debugMessage}"
                    _entitlementState.value = _entitlementState.value.copy(statusMessage = msg)
                    deferred?.complete(BillingActionResult.Error(msg))
                }
            }
        }
    }

    override suspend fun restorePurchases(): BillingActionResult = withContext(Dispatchers.Main) {
        val connected = connectAndRefreshProductIfAvailable()
        if (!connected) {
            val saved = preferencesRepository.getSavedSubscriptionEntitlement()
            if (saved?.isAdFreeSubscribed == true) {
                _entitlementState.value = saved
                return@withContext BillingActionResult.Restored(
                    entitlement = saved,
                    message = "Langganan aktif dipulihkan dari penyimpanan perangkat."
                )
            }
            return@withContext BillingActionResult.Error(
                "Tidak dapat menghubungi Google Play Store untuk memulihkan langganan."
            )
        }

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        val activePurchase = suspendCancellableCoroutine<Purchase?> { continuation ->
            billingClient.queryPurchasesAsync(params) { result, purchasesList ->
                val found = if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    purchasesList.firstOrNull {
                        it.purchaseState == Purchase.PurchaseState.PURCHASED
                    }
                } else {
                    null
                }
                if (continuation.isActive) {
                    continuation.resume(found)
                }
            }
        }

        if (activePurchase != null) {
            return@withContext handleVerifiedPurchase(activePurchase, isRestore = true)
        }

        val saved = preferencesRepository.getSavedSubscriptionEntitlement()
        if (saved?.isAdFreeSubscribed == true) {
            _entitlementState.value = saved
            return@withContext BillingActionResult.Restored(
                entitlement = saved,
                message = "Langganan Hapus Iklan aktif dipulihkan."
            )
        }

        BillingActionResult.Error("Tidak ditemukan langganan Hapus Iklan aktif di akun Google Play ini.")
    }

    override suspend fun simulateSandboxSubscription(activate: Boolean): BillingActionResult {
        if (!activate) {
            val cancelledState = SubscriptionEntitlementState(
                isAdFreeSubscribed = false,
                productId = subscriptionProductId,
                basePlanId = basePlanId,
                purchaseToken = null,
                orderId = null,
                expiresAtEpochMs = null,
                autoRenewing = false,
                isPlayBillingConnected = billingClient.isReady,
                statusMessage = "Langganan Hapus Iklan dinonaktifkan. Iklan kembali tampil."
            )
            preferencesRepository.saveSubscriptionEntitlement(cancelledState)
            _entitlementState.value = cancelledState
            return BillingActionResult.Cancelled(cancelledState.statusMessage)
        }

        val sandboxToken = "gplay-sub-token-${System.currentTimeMillis()}"
        val sandboxOrderId = "GPA.3391-8821-2026"
        return verifyWithBackendAndActivate(
            purchaseToken = sandboxToken,
            orderId = sandboxOrderId,
            autoRenewing = true,
            isRestore = false
        )
    }

    private suspend fun handleVerifiedPurchase(
        purchase: Purchase,
        isRestore: Boolean
    ): BillingActionResult {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            return BillingActionResult.Error("Status pembayaran Google Play masih PENDING.")
        }

        if (!purchase.isAcknowledged && billingClient.isReady) {
            val ackParams = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            suspendCancellableCoroutine<Unit> { continuation ->
                billingClient.acknowledgePurchase(ackParams) {
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    }
                }
            }
        }

        return verifyWithBackendAndActivate(
            purchaseToken = purchase.purchaseToken,
            orderId = purchase.orderId,
            autoRenewing = purchase.isAutoRenewing,
            isRestore = isRestore
        )
    }

    private suspend fun verifyWithBackendAndActivate(
        purchaseToken: String,
        orderId: String?,
        autoRenewing: Boolean,
        isRestore: Boolean
    ): BillingActionResult {
        val playerId = progressRepositoryProvider().getProfileId()
        val api = backendApiClientProvider()
        val verifyResult = api.verifySubscription(
            gameId = gameId,
            sessionToken = sessionTokenProvider(),
            request = SubscriptionVerificationRequestDto(
                playerId = playerId,
                productId = subscriptionProductId,
                basePlanId = basePlanId,
                purchaseToken = purchaseToken,
                orderId = orderId
            )
        )

        val thirtyDaysMs = 30L * 24L * 3600L * 1000L
        val newState = verifyResult.fold(
            onSuccess = { resp ->
                SubscriptionEntitlementState(
                    isAdFreeSubscribed = resp.isAdFreeActive,
                    productId = resp.productId,
                    basePlanId = basePlanId,
                    purchaseToken = purchaseToken,
                    orderId = orderId,
                    expiresAtEpochMs = resp.expiresAtEpochMs,
                    autoRenewing = resp.autoRenewing,
                    formattedPrice = _entitlementState.value.formattedPrice,
                    isPlayBillingConnected = billingClient.isReady,
                    statusMessage = resp.message
                )
            },
            onFailure = {
                // Even if Go backend is unreachable, honor local Google Play entitlement so subscriber gets ad-free immediately
                SubscriptionEntitlementState(
                    isAdFreeSubscribed = true,
                    productId = subscriptionProductId,
                    basePlanId = basePlanId,
                    purchaseToken = purchaseToken,
                    orderId = orderId,
                    expiresAtEpochMs = System.currentTimeMillis() + thirtyDaysMs,
                    autoRenewing = autoRenewing,
                    formattedPrice = _entitlementState.value.formattedPrice,
                    isPlayBillingConnected = billingClient.isReady,
                    statusMessage = "Langganan Hapus Iklan aktif di perangkat (verifikasi server akan disinkronkan saat BE online)."
                )
            }
        )

        preferencesRepository.saveSubscriptionEntitlement(newState)
        _entitlementState.value = newState

        return if (isRestore) {
            BillingActionResult.Restored(newState, newState.statusMessage)
        } else {
            BillingActionResult.Subscribed(newState, newState.statusMessage)
        }
    }
}
