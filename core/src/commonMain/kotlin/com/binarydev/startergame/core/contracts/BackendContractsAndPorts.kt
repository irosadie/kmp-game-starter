package com.binarydev.startergame.core.contracts

import com.binarydev.startergame.core.achievement.AchievementState
import com.binarydev.startergame.core.model.GameMetadataAndFeatures
import com.binarydev.startergame.core.model.LevelProgress
import com.binarydev.startergame.core.model.PlayerBalance
import com.binarydev.startergame.core.model.ResourceType
import com.binarydev.startergame.core.model.RewardBundle
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

// --- 1. Auth & Session Contracts (POST /v1/auth/google) ---

@Serializable
data class GoogleAuthExchangeRequest(
    val idToken: String,
    val installationId: String,
    val guestProfileId: String? = null
)

@Serializable
data class AuthSessionResponse(
    val sessionToken: String,
    val playerId: String,
    val displayName: String,
    val email: String? = null,
    val requiresGuestMerge: Boolean = true
)

@Serializable
data class AccountStatus(
    val profileId: String,
    val isGuest: Boolean,
    val displayName: String = "Guest Player",
    val email: String? = null,
    val linkedPlayerId: String? = null,
    val isBackendSyncActive: Boolean = false,
    val syncStatusMessage: String = "Bermain sebagai Guest (tersimpan lokal di perangkat)."
)

sealed class AuthActionResult {
    data class SignedIn(
        val status: AccountStatus,
        val guestMerged: Boolean
    ) : AuthActionResult()

    data class Cancelled(
        val message: String = "Login Google dibatalkan. Anda tetap bisa lanjut bermain sebagai Guest."
    ) : AuthActionResult()

    data class LocalOnlyFallback(
        val status: AccountStatus,
        val message: String = "Akun dipilih, namun server Go belum terhubung. Progress tetap milik profil lokal dan sinkronisasi belum aktif."
    ) : AuthActionResult()

    data class Error(
        val message: String
    ) : AuthActionResult()
}

interface SessionRepository {
    val accountStatus: Flow<AccountStatus>
    suspend fun getCurrentAccountStatus(): AccountStatus
    suspend fun signInWithGoogle(idTokenOverride: String? = null): AuthActionResult
    suspend fun signOut()
}

// --- 2. Remote Config Contracts (GET /v1/games/{gameId}/config) ---

@Serializable
data class AdFormatConfig(
    val enabled: Boolean = false,
    val maxImpressionsPerSession: Int = 0
)

@Serializable
data class RemoteGameConfigDto(
    val gameId: String,
    val configVersion: Int = 1,
    val ttlSeconds: Long = 3600L,
    val hintsEnabled: Boolean = true,
    val livesEnabled: Boolean = true,
    val rewardedAd: AdFormatConfig = AdFormatConfig(enabled = false, maxImpressionsPerSession = 5),
    val interstitialAd: AdFormatConfig = AdFormatConfig(enabled = false, maxImpressionsPerSession = 0),
    val bannerAd: AdFormatConfig = AdFormatConfig(enabled = false, maxImpressionsPerSession = 0),
    val rewardedAdGrant: RewardBundle = RewardBundle(hints = 1, lives = 1),
    val levelCompletionGrant: RewardBundle = RewardBundle(hints = 1, lives = 0),
    val featureFlags: Map<String, Boolean> = emptyMap()
) {
    companion object {
        fun safeAdsOffDefault(
            metadata: GameMetadataAndFeatures = GameMetadataAndFeatures()
        ): RemoteGameConfigDto = RemoteGameConfigDto(
            gameId = metadata.gameId,
            configVersion = 0,
            ttlSeconds = 0L,
            hintsEnabled = metadata.hintsEnabled,
            livesEnabled = metadata.livesEnabled,
            rewardedAd = AdFormatConfig(enabled = false, maxImpressionsPerSession = 0),
            interstitialAd = AdFormatConfig(enabled = false, maxImpressionsPerSession = 0),
            bannerAd = AdFormatConfig(enabled = false, maxImpressionsPerSession = 0),
            rewardedAdGrant = metadata.rewardedAdReward,
            levelCompletionGrant = metadata.levelCompletionReward
        )
    }
}

@Serializable
enum class ConfigSource {
    REMOTE_LIVE,
    LOCAL_CACHE,
    SAFE_DEFAULT_ADS_OFF
}

@Serializable
data class ActiveConfigState(
    val config: RemoteGameConfigDto,
    val source: ConfigSource,
    val lastUpdatedEpochMs: Long = 0L,
    val statusMessage: String = ""
)

@Serializable
data class ConfigRefreshSignal(
    val gameId: String,
    val targetVersion: Int? = null
)

interface ConfigRepository {
    val activeConfig: Flow<ActiveConfigState>
    suspend fun getActiveConfig(): ActiveConfigState
    suspend fun refreshIfNeeded(currentTimeEpochMs: Long): ActiveConfigState
    suspend fun onRefreshSignal(signal: ConfigRefreshSignal, currentTimeEpochMs: Long): ActiveConfigState
}

// --- 3. Progress & Event Sync Contracts (GET /v1/games/{gameId}/progress, POST /v1/games/{gameId}/progress/events) ---

@Serializable
data class ProgressSyncEventDto(
    val eventId: String,
    val eventType: String,
    val payloadJson: String,
    val clientTimestampEpochMs: Long
)

@Serializable
data class ProgressEventsBatchRequest(
    val playerId: String,
    val baseServerRevision: Long,
    val isGuestMerge: Boolean = false,
    val events: List<ProgressSyncEventDto>
)

@Serializable
data class EventStatusDto(
    val eventId: String,
    val status: String // "ACCEPTED", "DUPLICATE_IGNORED", "CONFLICT_RESOLVED"
)

@Serializable
data class PlayerProgressSnapshotDto(
    val playerId: String,
    val serverRevision: Long,
    val balance: PlayerBalance,
    val levels: List<LevelProgress>,
    val achievements: List<AchievementState>
)

@Serializable
data class ProgressEventsBatchResponse(
    val serverRevision: Long,
    val eventStatuses: List<EventStatusDto>,
    val mergedSnapshot: PlayerProgressSnapshotDto
)

// --- 3b. Achievements Backend API Contracts (GET /v1/games/{gameId}/achievements, POST /v1/games/{gameId}/achievements/sync) ---

@Serializable
data class AchievementSyncRequestDto(
    val playerId: String,
    val levelId: String,
    val attemptId: String,
    val score: Int,
    val hintsUsed: Int,
    val completedLevelsCount: Int,
    val clientAchievements: List<AchievementState> = emptyList()
)

@Serializable
data class AchievementsResponseDto(
    val gameId: String,
    val isMockBackend: Boolean = false,
    val endpoint: String = "GET /v1/games/{gameId}/achievements",
    val achievements: List<AchievementState>
)

interface ProgressRepository {
    val balanceFlow: Flow<PlayerBalance>
    val levelsFlow: Flow<Map<String, LevelProgress>>
    val achievementsFlow: Flow<List<AchievementState>>

    suspend fun getProfileId(): String
    suspend fun getBalance(): PlayerBalance
    suspend fun getLevels(): Map<String, LevelProgress>
    suspend fun getAchievements(): List<AchievementState>

    suspend fun saveGameState(
        levels: Map<String, LevelProgress>,
        achievements: List<AchievementState>,
        syncEvent: ProgressSyncEventDto? = null
    )

    suspend fun grantRewardIdempotent(
        transactionId: String,
        bundle: RewardBundle,
        sourceName: String,
        timestampEpochMs: Long = 0L
    ): Boolean

    suspend fun consumeHintIdempotent(
        transactionId: String,
        timestampEpochMs: Long = 0L
    ): Boolean

    suspend fun deductLifeForFailedAttemptIdempotent(
        attemptId: String,
        timestampEpochMs: Long = 0L
    ): Boolean

    suspend fun mergeGuestToAuthenticatedAccount(playerId: String): Result<PlayerProgressSnapshotDto>
    suspend fun flushPendingSyncEvents(): Result<Int>
}

// --- 4. Leaderboard & Score Submission Contracts (GET /v1/games/{gameId}/leaderboard, POST /v1/games/{gameId}/scores) ---

@Serializable
data class ScoreSubmissionRequest(
    val attemptId: String,
    val levelId: String,
    val score: Int,
    val validatorPayload: Map<String, String>
)

@Serializable
data class ScoreSubmissionResponse(
    val attemptId: String,
    val accepted: Boolean,
    val globalRank: Int? = null,
    val rejectionReason: String? = null
)

@Serializable
data class LeaderboardEntryDto(
    val rank: Int,
    val playerId: String,
    val displayName: String,
    val score: Int,
    val isCurrentPlayer: Boolean = false
)

@Serializable
data class LeaderboardResponseDto(
    val gameId: String,
    val isDemo: Boolean,
    val entries: List<LeaderboardEntryDto>
)

sealed class LeaderboardState {
    data object Loading : LeaderboardState()

    data class Loaded(
        val isDemo: Boolean,
        val entries: List<LeaderboardEntryDto>,
        val bannerMessage: String? = null
    ) : LeaderboardState()

    data class Empty(
        val isDemo: Boolean,
        val message: String = "Belum ada skor yang tercatat di leaderboard."
    ) : LeaderboardState()

    data class Unavailable(
        val message: String = "Layanan leaderboard global belum tersedia. Skor lokal Anda tetap tersimpan."
    ) : LeaderboardState()
}

interface LeaderboardRepository {
    suspend fun fetchLeaderboard(): LeaderboardState
    suspend fun submitScore(request: ScoreSubmissionRequest): Result<ScoreSubmissionResponse>
}

// --- 5. Reward Claims & Ad Contracts (POST /v1/games/{gameId}/rewards/claims) ---

@Serializable
enum class RewardClaimStatus {
    PENDING,
    CONFIRMED,
    REJECTED
}

@Serializable
data class RewardClaimRequestDto(
    val claimId: String,
    val adNetwork: String = "admob",
    val rewardBundle: RewardBundle,
    val adUnitId: String = ""
)

@Serializable
data class RewardClaimResponseDto(
    val claimId: String,
    val status: RewardClaimStatus,
    val updatedBalance: PlayerBalance? = null,
    val message: String = ""
)

sealed class AdRewardFlowResult {
    data class Granted(
        val claimId: String,
        val reward: RewardBundle,
        val duplicateIgnored: Boolean = false,
        val message: String = "Reward berhasil ditambahkan!"
    ) : AdRewardFlowResult()

    data class PendingServerVerification(
        val claimId: String,
        val message: String = "Menunggu konfirmasi verifikasi reward dari server..."
    ) : AdRewardFlowResult()

    data class DismissedEarly(
        val message: String = "Iklan ditutup sebelum selesai. Saldo tidak bertambah."
    ) : AdRewardFlowResult()

    data class Unavailable(
        val message: String = "Iklan belum tersedia saat ini. Saldo tidak bertambah."
    ) : AdRewardFlowResult()

    data class Failed(
        val message: String = "Gagal memuat iklan. Saldo tidak bertambah."
    ) : AdRewardFlowResult()
}

sealed class RawAdShowResult {
    data class EarnedReward(val transactionOrClaimId: String) : RawAdShowResult()
    data class DismissedBeforeReward(val reason: String = "Iklan ditutup sebelum selesai.") : RawAdShowResult()
    data class NotAvailable(val reason: String = "Iklan tidak tersedia.") : RawAdShowResult()
    data class FailedToLoadOrShow(val reason: String = "Iklan gagal ditampilkan.") : RawAdShowResult()
}

interface AdService {
    suspend fun showRewardedAd(claimId: String): RawAdShowResult
    fun isInterstitialEnabled(config: RemoteGameConfigDto): Boolean = config.interstitialAd.enabled
    fun isBannerEnabled(config: RemoteGameConfigDto): Boolean = config.bannerAd.enabled
}

interface RewardClaimRepository {
    suspend fun watchAdAndClaimReward(
        claimId: String,
        rewardBundle: RewardBundle,
        timestampEpochMs: Long = 0L
    ): AdRewardFlowResult
}

// --- 6. FCM Push Token Contract (PUT /v1/devices/{installationId}/push-token) ---

@Serializable
data class PushTokenRegistrationRequest(
    val installationId: String,
    val fcmToken: String,
    val linkedPlayerId: String? = null,
    val platform: String = "android"
)

interface PushTokenRepository {
    suspend fun registerOrUpdateToken(fcmToken: String, linkedPlayerId: String?): Result<Unit>
    suspend fun clearAccountAssociationOnLogout(): Result<Unit>
}

// --- 7. Device Preferences Contract ---

@Serializable
data class DevicePreferences(
    val musicEnabled: Boolean = true,
    val soundEffectsEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val installationId: String = "local-install-default"
)

interface DevicePreferencesRepository {
    val preferencesFlow: Flow<DevicePreferences>
    suspend fun getPreferences(): DevicePreferences
    suspend fun setMusicEnabled(enabled: Boolean)
    suspend fun setSoundEffectsEnabled(enabled: Boolean)
    suspend fun setVibrationEnabled(enabled: Boolean)
}

// --- 8. Google Play Billing Subscription (Remove Ads / Google Pay) & Backend Verification Contract ---

@Serializable
data class SubscriptionEntitlementState(
    val isAdFreeSubscribed: Boolean = false,
    val productId: String = "sub_remove_ads_monthly",
    val basePlanId: String = "monthly-autorenew",
    val purchaseToken: String? = null,
    val orderId: String? = null,
    val expiresAtEpochMs: Long? = null,
    val autoRenewing: Boolean = false,
    val formattedPrice: String = "Rp 15.000 / bln",
    val isPlayBillingConnected: Boolean = false,
    val statusMessage: String = "Belum berlangganan (Iklan aktif)."
)

@Serializable
data class SubscriptionVerificationRequestDto(
    val playerId: String,
    val productId: String,
    val basePlanId: String,
    val purchaseToken: String,
    val orderId: String? = null
)

@Serializable
data class SubscriptionVerificationResponseDto(
    val verified: Boolean,
    val isAdFreeActive: Boolean,
    val productId: String,
    val expiresAtEpochMs: Long,
    val autoRenewing: Boolean = true,
    val message: String = "Langganan Hapus Iklan terverifikasi."
)

sealed class BillingActionResult {
    data class Subscribed(
        val entitlement: SubscriptionEntitlementState,
        val message: String
    ) : BillingActionResult()

    data class Restored(
        val entitlement: SubscriptionEntitlementState,
        val message: String
    ) : BillingActionResult()

    data class Cancelled(
        val message: String = "Pembayaran langganan Google Play dibatalkan."
    ) : BillingActionResult()

    data class Error(
        val message: String
    ) : BillingActionResult()
}

interface SubscriptionBillingRepository {
    val entitlementFlow: Flow<SubscriptionEntitlementState>
    suspend fun getEntitlement(): SubscriptionEntitlementState
    suspend fun launchRemoveAdsSubscriptionFlow(): BillingActionResult
    suspend fun restorePurchases(): BillingActionResult
    suspend fun simulateSandboxSubscription(activate: Boolean): BillingActionResult
}
