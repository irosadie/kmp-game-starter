package com.binarydev.startergame.data.integration

import android.app.Activity
import android.content.Context
import com.binarydev.startergame.core.contracts.AdService
import com.binarydev.startergame.core.contracts.RawAdShowResult
import com.binarydev.startergame.core.contracts.RemoteGameConfigDto
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Real Google Mobile Ads (AdMob) implementation of AdService using official Google test rewarded ad unit IDs
 * during development (or custom ID from `.env`).
 * Supports preloading for instant playback as well as on-demand load-and-show.
 */
class AdMobRewardedAdAdapter(
    private val context: Context,
    private val activityProvider: () -> Activity?,
    private val rewardedAdUnitId: String = TEST_REWARDED_AD_UNIT_ID
) : AdService {

    private var preloadedAd: RewardedAd? = null
    private var isPreloading: Boolean = false

    fun preloadAd() {
        if (preloadedAd != null || isPreloading) return
        val loadContext = activityProvider() ?: context
        isPreloading = true
        val adRequest = AdRequest.Builder().build()
        RewardedAd.load(
            loadContext,
            rewardedAdUnitId,
            adRequest,
            object : RewardedAdLoadCallback() {
                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    isPreloading = false
                    preloadedAd = null
                }

                override fun onAdLoaded(rewardedAd: RewardedAd) {
                    isPreloading = false
                    preloadedAd = rewardedAd
                }
            }
        )
    }

    override suspend fun showRewardedAd(claimId: String): RawAdShowResult = withContext(Dispatchers.Main) {
        val activity = activityProvider()
            ?: return@withContext mapLifecycleToRawAdResult(
                rewardEarned = false,
                loadError = null,
                showError = null,
                isActivityAvailable = false,
                claimId = claimId
            )

        val readyAd = preloadedAd
        if (readyAd != null) {
            preloadedAd = null
            return@withContext showLoadedAd(activity, readyAd, claimId)
        }

        val loadedResult = loadRewardedAdSuspend(activity)
        loadedResult.fold(
            onSuccess = { ad -> showLoadedAd(activity, ad, claimId) },
            onFailure = { err ->
                mapLifecycleToRawAdResult(
                    rewardEarned = false,
                    loadError = err.message ?: "Gagal memuat iklan AdMob",
                    showError = null,
                    isActivityAvailable = true,
                    claimId = claimId
                )
            }
        )
    }

    private suspend fun loadRewardedAdSuspend(activity: Activity): Result<RewardedAd> =
        suspendCancellableCoroutine { continuation ->
            val adRequest = AdRequest.Builder().build()
            RewardedAd.load(
                activity,
                rewardedAdUnitId,
                adRequest,
                object : RewardedAdLoadCallback() {
                    override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                        if (continuation.isActive) {
                            continuation.resume(
                                Result.failure(IllegalStateException("${loadAdError.message} (code=${loadAdError.code})"))
                            )
                        }
                    }

                    override fun onAdLoaded(rewardedAd: RewardedAd) {
                        if (continuation.isActive) {
                            continuation.resume(Result.success(rewardedAd))
                        }
                    }
                }
            )
        }

    private suspend fun showLoadedAd(
        activity: Activity,
        rewardedAd: RewardedAd,
        claimId: String
    ): RawAdShowResult = suspendCancellableCoroutine { continuation ->
        var earned = false
        rewardedAd.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                preloadAd()
                if (continuation.isActive) {
                    continuation.resume(
                        mapLifecycleToRawAdResult(
                            rewardEarned = false,
                            loadError = null,
                            showError = "${adError.message} (code=${adError.code})",
                            isActivityAvailable = true,
                            claimId = claimId
                        )
                    )
                }
            }

            override fun onAdDismissedFullScreenContent() {
                preloadAd()
                if (continuation.isActive) {
                    continuation.resume(
                        mapLifecycleToRawAdResult(
                            rewardEarned = earned,
                            loadError = null,
                            showError = null,
                            isActivityAvailable = true,
                            claimId = claimId
                        )
                    )
                }
            }
        }

        rewardedAd.show(activity) {
            earned = true
        }
    }

    override fun isInterstitialEnabled(config: RemoteGameConfigDto): Boolean =
        config.interstitialAd.enabled

    override fun isBannerEnabled(config: RemoteGameConfigDto): Boolean =
        config.bannerAd.enabled

    companion object {
        const val TEST_REWARDED_AD_UNIT_ID = "ca-app-pub-3940256099942544/5224354917"

        fun mapLifecycleToRawAdResult(
            rewardEarned: Boolean,
            loadError: String?,
            showError: String?,
            isActivityAvailable: Boolean,
            claimId: String
        ): RawAdShowResult = when {
            !isActivityAvailable -> RawAdShowResult.NotAvailable("Iklan belum tersedia (Activity tidak aktif).")
            !loadError.isNullOrBlank() -> RawAdShowResult.FailedToLoadOrShow("AdMob gagal memuat iklan: $loadError")
            !showError.isNullOrBlank() -> RawAdShowResult.FailedToLoadOrShow("AdMob gagal menampilkan iklan: $showError")
            rewardEarned -> RawAdShowResult.EarnedReward(claimId)
            else -> RawAdShowResult.DismissedBeforeReward("Iklan AdMob ditutup sebelum reward diperoleh. Saldo tidak bertambah.")
        }
    }
}
