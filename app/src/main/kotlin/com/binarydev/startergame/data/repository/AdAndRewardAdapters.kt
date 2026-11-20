package com.binarydev.startergame.data.repository

import com.binarydev.startergame.core.contracts.AdRewardFlowResult
import com.binarydev.startergame.core.contracts.AdService
import com.binarydev.startergame.core.contracts.ProgressRepository
import com.binarydev.startergame.core.contracts.RawAdShowResult
import com.binarydev.startergame.core.contracts.RewardClaimRepository
import com.binarydev.startergame.core.contracts.RewardClaimRequestDto
import com.binarydev.startergame.core.contracts.RewardClaimStatus
import com.binarydev.startergame.core.model.RewardBundle
import com.binarydev.startergame.core.model.RewardSource
import com.binarydev.startergame.data.remote.GoBackendApiClient
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

enum class SimulatedAdMode {
    COMPLETE_WITH_REWARD,
    DISMISS_EARLY,
    AD_UNAVAILABLE,
    AD_LOAD_FAILED
}

data class InteractiveAdSession(
    val claimId: String,
    val durationSeconds: Int = 5,
    val onCompleted: () -> Unit,
    val onDismissedEarly: () -> Unit
)

/**
 * Simulated AdService for Demo mode and automated testing.
 * - When `interactiveUiAttached = true` (in live app UI) and `nextMode == COMPLETE_WITH_REWARD`,
 *   it opens an interactive full-screen video ad player overlay with a countdown timer so the player
 *   can watch the ad to completion or close it early.
 * - In headless unit tests (`interactiveUiAttached = false`), it resolves immediately according to `nextMode`.
 */
class SimulatedAdAdapter(
    var nextMode: SimulatedAdMode = SimulatedAdMode.COMPLETE_WITH_REWARD,
    var interactiveUiAttached: Boolean = false
) : AdService {

    private val _activeSession = MutableStateFlow<InteractiveAdSession?>(null)
    val activeSession: StateFlow<InteractiveAdSession?> = _activeSession.asStateFlow()

    override suspend fun showRewardedAd(claimId: String): RawAdShowResult = when (nextMode) {
        SimulatedAdMode.DISMISS_EARLY ->
            RawAdShowResult.DismissedBeforeReward("Simulasi: Pemain menutup iklan sebelum selesai.")
        SimulatedAdMode.AD_UNAVAILABLE ->
            RawAdShowResult.NotAvailable("Simulasi: Iklan belum tersedia.")
        SimulatedAdMode.AD_LOAD_FAILED ->
            RawAdShowResult.FailedToLoadOrShow("Simulasi: Gagal memuat rewarded ad.")
        SimulatedAdMode.COMPLETE_WITH_REWARD -> {
            if (!interactiveUiAttached) {
                RawAdShowResult.EarnedReward(claimId)
            } else {
                suspendCancellableCoroutine<RawAdShowResult> { continuation ->
                    val session = InteractiveAdSession(
                        claimId = claimId,
                        durationSeconds = 5,
                        onCompleted = {
                            _activeSession.value = null
                            if (continuation.isActive) {
                                continuation.resume(RawAdShowResult.EarnedReward(claimId))
                            }
                        },
                        onDismissedEarly = {
                            _activeSession.value = null
                            if (continuation.isActive) {
                                continuation.resume(
                                    RawAdShowResult.DismissedBeforeReward(
                                        "Iklan ditutup sebelum selesai ditonton. Saldo tidak bertambah."
                                    )
                                )
                            }
                        }
                    )
                    _activeSession.value = session
                    continuation.invokeOnCancellation {
                        _activeSession.value = null
                    }
                }
            }
        }
    }
}

/**
 * Allows switching at runtime between the Simulated Interactive Video Ad Player and the real Google AdMob Test Ad SDK.
 */
class SwitchableAdService(
    private val simulatedAdAdapter: SimulatedAdAdapter,
    private val adMobAdAdapter: AdService,
    var useRealAdMobTestAds: Boolean = false
) : AdService {

    override suspend fun showRewardedAd(claimId: String): RawAdShowResult = if (useRealAdMobTestAds) {
        adMobAdAdapter.showRewardedAd(claimId)
    } else {
        simulatedAdAdapter.showRewardedAd(claimId)
    }
}

/**
 * Coordinates showing a rewarded ad and crediting the reward idempotently.
 * - In local/demo balance mode: credits RoomProgressRepository once per `claimId`.
 * - In connected server-verified mode (`useServerVerification = true`): submits claim to
 *   `POST /v1/games/{gameId}/rewards/claims` and returns `PendingServerVerification` while status is `PENDING`,
 *   crediting local balance only when `CONFIRMED`.
 */
class RewardClaimService(
    private val gameId: String,
    private val adService: AdService,
    private val progressRepository: ProgressRepository,
    private val backendApiClient: GoBackendApiClient? = null,
    private val useServerVerification: Boolean = false,
    private val sessionTokenProvider: () -> String? = { null }
) : RewardClaimRepository {

    override suspend fun watchAdAndClaimReward(
        claimId: String,
        rewardBundle: RewardBundle,
        timestampEpochMs: Long
    ): AdRewardFlowResult = when (val adResult = adService.showRewardedAd(claimId)) {
        is RawAdShowResult.DismissedBeforeReward -> AdRewardFlowResult.DismissedEarly(adResult.reason)
        is RawAdShowResult.NotAvailable -> AdRewardFlowResult.Unavailable(adResult.reason)
        is RawAdShowResult.FailedToLoadOrShow -> AdRewardFlowResult.Failed(adResult.reason)
        is RawAdShowResult.EarnedReward -> processEarnedRewardClaim(
            claimId = adResult.transactionOrClaimId,
            rewardBundle = rewardBundle,
            timestampEpochMs = timestampEpochMs
        )
    }

    suspend fun processEarnedRewardClaim(
        claimId: String,
        rewardBundle: RewardBundle,
        timestampEpochMs: Long = System.currentTimeMillis()
    ): AdRewardFlowResult {
        val txId = "ad_reward:$claimId"
        if (useServerVerification && backendApiClient != null) {
            val responseResult = backendApiClient.createOrCheckRewardClaim(
                gameId = gameId,
                sessionToken = sessionTokenProvider(),
                request = RewardClaimRequestDto(
                    claimId = claimId,
                    rewardBundle = rewardBundle
                )
            )
            return responseResult.fold(
                onSuccess = { resp ->
                    when (resp.status) {
                        RewardClaimStatus.PENDING -> AdRewardFlowResult.PendingServerVerification(
                            claimId = claimId,
                            message = resp.message.ifBlank {
                                "Menunggu konfirmasi callback AdMob dari server Go..."
                            }
                        )
                        RewardClaimStatus.CONFIRMED -> {
                            val applied = progressRepository.grantRewardIdempotent(
                                transactionId = txId,
                                bundle = rewardBundle,
                                sourceName = RewardSource.REWARDED_AD.name,
                                timestampEpochMs = timestampEpochMs
                            )
                            AdRewardFlowResult.Granted(
                                claimId = claimId,
                                reward = rewardBundle,
                                duplicateIgnored = !applied,
                                message = if (applied) {
                                    "Reward iklan terverifikasi (+${rewardBundle.hints} Hint, +${rewardBundle.lives} Nyawa)."
                                } else {
                                    "Reward untuk klaim ini sudah pernah ditambahkan sebelumnya."
                                }
                            )
                        }
                        RewardClaimStatus.REJECTED -> AdRewardFlowResult.Failed(
                            resp.message.ifBlank { "Verifikasi reward iklan ditolak oleh server." }
                        )
                    }
                },
                onFailure = {
                    AdRewardFlowResult.Failed("Layanan verifikasi reward belum tersedia. Saldo tidak ditambah.")
                }
            )
        }

        val applied = progressRepository.grantRewardIdempotent(
            transactionId = txId,
            bundle = rewardBundle,
            sourceName = RewardSource.REWARDED_AD.name,
            timestampEpochMs = timestampEpochMs
        )
        return AdRewardFlowResult.Granted(
            claimId = claimId,
            reward = rewardBundle,
            duplicateIgnored = !applied,
            message = if (applied) {
                "Reward diterima: +${rewardBundle.hints} Hint, +${rewardBundle.lives} Nyawa!"
            } else {
                "Callback ganda terdeteksi (ID: $claimId). Saldo hanya ditambah sekali."
            }
        )
    }
}
