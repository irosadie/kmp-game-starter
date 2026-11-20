package com.binarydev.startergame.data.remote

import com.binarydev.startergame.core.achievement.AchievementCriterion
import com.binarydev.startergame.core.achievement.AchievementDefinition
import com.binarydev.startergame.core.achievement.AchievementEvaluator
import com.binarydev.startergame.core.achievement.AchievementState
import com.binarydev.startergame.core.achievement.LevelCompletionContext
import com.binarydev.startergame.core.contracts.AchievementSyncRequestDto
import com.binarydev.startergame.core.contracts.AchievementsResponseDto
import com.binarydev.startergame.core.contracts.AdFormatConfig
import com.binarydev.startergame.core.contracts.AuthSessionResponse
import com.binarydev.startergame.core.contracts.EventStatusDto
import com.binarydev.startergame.core.contracts.GoogleAuthExchangeRequest
import com.binarydev.startergame.core.contracts.LeaderboardEntryDto
import com.binarydev.startergame.core.contracts.LeaderboardResponseDto
import com.binarydev.startergame.core.contracts.PlayerProgressSnapshotDto
import com.binarydev.startergame.core.contracts.ProgressEventsBatchRequest
import com.binarydev.startergame.core.contracts.ProgressEventsBatchResponse
import com.binarydev.startergame.core.contracts.ProgressRepository
import com.binarydev.startergame.core.contracts.PushTokenRegistrationRequest
import com.binarydev.startergame.core.contracts.RemoteGameConfigDto
import com.binarydev.startergame.core.contracts.RewardClaimRequestDto
import com.binarydev.startergame.core.contracts.RewardClaimResponseDto
import com.binarydev.startergame.core.contracts.RewardClaimStatus
import com.binarydev.startergame.core.contracts.ScoreSubmissionRequest
import com.binarydev.startergame.core.contracts.ScoreSubmissionResponse
import com.binarydev.startergame.core.contracts.SubscriptionVerificationRequestDto
import com.binarydev.startergame.core.contracts.SubscriptionVerificationResponseDto
import com.binarydev.startergame.core.ledger.RewardLedger
import com.binarydev.startergame.core.model.PlayerBalance
import com.binarydev.startergame.core.model.RewardBundle
import kotlinx.coroutines.delay
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

/**
 * Mockup Go Backend v1 HTTP JSON API Client (`MockGoBackendApiClient`).
 * Simulates the real Go server endpoints using full JSON request/response serialization:
 * - GET  /v1/games/{gameId}/leaderboard
 * - POST /v1/games/{gameId}/scores
 * - GET  /v1/games/{gameId}/achievements
 * - POST /v1/games/{gameId}/achievements/sync
 * - POST /v1/auth/google
 * - GET  /v1/games/{gameId}/config
 * - GET  /v1/games/{gameId}/progress
 * - POST /v1/games/{gameId}/progress/events
 * - POST /v1/games/{gameId}/rewards/claims
 * - PUT  /v1/devices/{installationId}/push-token
 */
class MockGoBackendApiClient(
    private val gameId: String,
    private val progressRepositoryProvider: () -> ProgressRepository,
    var simulatedLatencyMs: Long = 0L,
    var simulateServerUnavailable: Boolean = false
) : GoBackendApiClient {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val serverAchievementDefinitions: List<AchievementDefinition> = listOf(
        AchievementDefinition(
            id = AchievementEvaluator.ACHIEVEMENT_FIRST_LEVEL_ID,
            title = "Selesaikan level pertama",
            description = "Selesaikan level pertama permainan (API BE Mockup).",
            targetValue = 1,
            criterion = AchievementCriterion.CompleteFirstLevel,
            reward = RewardBundle(hints = 1, lives = 0)
        ),
        AchievementDefinition(
            id = AchievementEvaluator.ACHIEVEMENT_NO_HINT_ID,
            title = "Selesaikan level tanpa hint",
            description = "Menangkan level tanpa memakai bantuan hint (API BE Mockup).",
            targetValue = 1,
            criterion = AchievementCriterion.CompleteLevelWithoutHints,
            reward = RewardBundle(hints = 1, lives = 1)
        ),
        AchievementDefinition(
            id = "high_scorer_850",
            title = "Master Skor Tinggi",
            description = "Raih skor minimal 850 dalam satu level (API BE Mockup).",
            targetValue = 850,
            criterion = AchievementCriterion.ReachScoreInSingleLevel(850),
            reward = RewardBundle(hints = 2, lives = 0)
        ),
        AchievementDefinition(
            id = "complete_2_levels",
            title = "Penjelajah Kata Nusantara",
            description = "Selesaikan semua 2 level puzzle yang tersedia (API BE Mockup).",
            targetValue = 2,
            criterion = AchievementCriterion.CompleteLevelsCount(2),
            reward = RewardBundle(hints = 2, lives = 1)
        )
    )

    private val evaluator = AchievementEvaluator(serverAchievementDefinitions)

    private val mockServerLeaderboardScores = mutableMapOf(
        "api-bot-1" to ("Raka Puzzle (BE Mock)" to 1850),
        "api-bot-2" to ("Sinta Kata (BE Mock)" to 1420),
        "api-bot-3" to ("Budi Santoso (BE Mock)" to 1100),
        "api-bot-4" to ("Citra Lestari (BE Mock)" to 850),
        "api-bot-5" to ("Deni Pratama (BE Mock)" to 600)
    )

    private var serverAchievementStates: Map<String, AchievementState> =
        evaluator.initialStates().associateBy { it.id }

    var lastLeaderboardJsonPayload: String = ""
        private set

    var lastAchievementsJsonPayload: String = ""
        private set

    var lastConfigJsonPayload: String = ""
        private set

    var lastFcmPushPayloadJson: String = ""
        private set

    var lastAuthExchangeJsonPayload: String = ""
        private set

    var serverGameConfig: RemoteGameConfigDto = RemoteGameConfigDto(
        gameId = gameId,
        configVersion = 1,
        ttlSeconds = 3600L,
        hintsEnabled = true,
        livesEnabled = true,
        rewardedAd = AdFormatConfig(enabled = true, maxImpressionsPerSession = 10),
        interstitialAd = AdFormatConfig(enabled = false, maxImpressionsPerSession = 0),
        bannerAd = AdFormatConfig(enabled = true, maxImpressionsPerSession = 100)
    )
        private set

    fun updateServerConfigFromMockAdminPanel(
        hintsEnabled: Boolean = serverGameConfig.hintsEnabled,
        livesEnabled: Boolean = serverGameConfig.livesEnabled,
        bannerAdEnabled: Boolean = serverGameConfig.bannerAd.enabled,
        rewardedAdEnabled: Boolean = serverGameConfig.rewardedAd.enabled,
        interstitialAdEnabled: Boolean = serverGameConfig.interstitialAd.enabled
    ): Map<String, String> {
        val nextVersion = serverGameConfig.configVersion + 1
        serverGameConfig = serverGameConfig.copy(
            configVersion = nextVersion,
            hintsEnabled = hintsEnabled,
            livesEnabled = livesEnabled,
            bannerAd = serverGameConfig.bannerAd.copy(enabled = bannerAdEnabled),
            rewardedAd = serverGameConfig.rewardedAd.copy(
                enabled = rewardedAdEnabled,
                maxImpressionsPerSession = if (rewardedAdEnabled) 10 else 0
            ),
            interstitialAd = serverGameConfig.interstitialAd.copy(
                enabled = interstitialAdEnabled,
                maxImpressionsPerSession = if (interstitialAdEnabled) 5 else 0
            )
        )
        val fcmDataPayload = mapOf(
            "type" to "config_refresh",
            "gameId" to gameId,
            "configVersion" to nextVersion.toString(),
            "bannerAdEnabled" to bannerAdEnabled.toString(),
            "rewardedAdEnabled" to rewardedAdEnabled.toString(),
            "interstitialAdEnabled" to interstitialAdEnabled.toString()
        )
        lastFcmPushPayloadJson = json.encodeToString(fcmDataPayload)
        return fcmDataPayload
    }

    private suspend fun simulateNetworkRoundTrip() {
        if (simulatedLatencyMs > 0L) {
            delay(simulatedLatencyMs)
        }
        if (simulateServerUnavailable) {
            throw IOException("Mock BE API 503: Server Go sedang disimulasikan offline/tidak tersedia.")
        }
    }

    override suspend fun getLeaderboard(gameId: String): Result<LeaderboardResponseDto> = runCatching {
        simulateNetworkRoundTrip()
        val progressRepo = progressRepositoryProvider()
        val playerId = progressRepo.getProfileId()
        val localTotalScore = progressRepo.getLevels().values.sumOf { it.bestScore }

        val currentBestInMock = mockServerLeaderboardScores[playerId]?.second ?: 0
        val effectivePlayerScore = maxOf(localTotalScore, currentBestInMock)
        mockServerLeaderboardScores[playerId] = "Anda ($playerId)" to effectivePlayerScore

        val sortedEntries = mockServerLeaderboardScores.entries
            .sortedByDescending { it.value.second }
            .mapIndexed { index, entry ->
                LeaderboardEntryDto(
                    rank = index + 1,
                    playerId = entry.key,
                    displayName = entry.value.first,
                    score = entry.value.second,
                    isCurrentPlayer = entry.key == playerId
                )
            }

        val rawResponse = LeaderboardResponseDto(
            gameId = gameId,
            isDemo = true,
            entries = sortedEntries
        )
        // Round-trip through JSON serializer to guarantee HTTP JSON contract compliance
        val serialized = json.encodeToString(rawResponse)
        lastLeaderboardJsonPayload = serialized
        json.decodeFromString<LeaderboardResponseDto>(serialized)
    }

    override suspend fun submitScore(
        gameId: String,
        sessionToken: String?,
        request: ScoreSubmissionRequest
    ): Result<ScoreSubmissionResponse> = runCatching {
        simulateNetworkRoundTrip()
        val requestJson = json.encodeToString(request)
        val decodedReq = json.decodeFromString<ScoreSubmissionRequest>(requestJson)

        val progressRepo = progressRepositoryProvider()
        val playerId = progressRepo.getProfileId()
        val localTotal = progressRepo.getLevels().values.sumOf { it.bestScore }
        val updatedTotal = maxOf(localTotal, decodedReq.score)
        mockServerLeaderboardScores[playerId] = "Anda ($playerId)" to updatedTotal

        val rank = mockServerLeaderboardScores.values
            .sortedByDescending { it.second }
            .indexOfFirst { it.second <= updatedTotal }
            .let { if (it < 0) mockServerLeaderboardScores.size else it + 1 }

        val resp = ScoreSubmissionResponse(
            attemptId = decodedReq.attemptId,
            accepted = true,
            globalRank = rank
        )
        json.decodeFromString<ScoreSubmissionResponse>(json.encodeToString(resp))
    }

    override suspend fun getAchievements(
        gameId: String,
        sessionToken: String?
    ): Result<AchievementsResponseDto> = runCatching {
        simulateNetworkRoundTrip()
        val progressRepo = progressRepositoryProvider()
        val localSaved = progressRepo.getAchievements().associateBy { it.id }
        val localLevels = progressRepo.getLevels()
        val completedCount = localLevels.values.count { it.completed }
        val maxBestScore = localLevels.values.maxOfOrNull { it.bestScore } ?: 0
        val firstLevelCompleted = localLevels["level-1"]?.completed == true

        // Merge local Room achievement state with Mock Backend API definitions
        val mergedList = serverAchievementDefinitions.map { def ->
            val fromServer = serverAchievementStates[def.id]
            val fromLocal = localSaved[def.id]
            val computedProgress = when (val c = def.criterion) {
                is AchievementCriterion.CompleteFirstLevel ->
                    if (firstLevelCompleted) 1 else 0
                is AchievementCriterion.CompleteLevelWithoutHints ->
                    maxOf(fromLocal?.currentProgress ?: 0, fromServer?.currentProgress ?: 0)
                is AchievementCriterion.CompleteLevelsCount ->
                    completedCount.coerceAtMost(c.targetCount)
                is AchievementCriterion.ReachScoreInSingleLevel ->
                    maxBestScore.coerceAtMost(c.targetScore)
            }
            val bestProgress = maxOf(
                computedProgress,
                fromLocal?.currentProgress ?: 0,
                fromServer?.currentProgress ?: 0
            ).coerceAtMost(def.targetValue)
            val isUnlocked = (fromLocal?.unlocked == true) ||
                (fromServer?.unlocked == true) ||
                (bestProgress >= def.targetValue)

            AchievementState(
                id = def.id,
                title = def.title,
                description = def.description,
                targetValue = def.targetValue,
                currentProgress = if (isUnlocked) def.targetValue else bestProgress,
                unlocked = isUnlocked,
                reward = def.reward,
                rewardTransactionId = fromLocal?.rewardTransactionId ?: fromServer?.rewardTransactionId
            )
        }
        serverAchievementStates = mergedList.associateBy { it.id }

        val responseDto = AchievementsResponseDto(
            gameId = gameId,
            isMockBackend = true,
            endpoint = "GET /v1/games/$gameId/achievements",
            achievements = mergedList
        )
        val serialized = json.encodeToString(responseDto)
        lastAchievementsJsonPayload = serialized
        json.decodeFromString<AchievementsResponseDto>(serialized)
    }

    override suspend fun syncAchievements(
        gameId: String,
        sessionToken: String?,
        request: AchievementSyncRequestDto
    ): Result<AchievementsResponseDto> = runCatching {
        simulateNetworkRoundTrip()
        val decodedReq = json.decodeFromString<AchievementSyncRequestDto>(json.encodeToString(request))
        val clientMap = decodedReq.clientAchievements.associateBy { it.id }
        val mergedSeed = evaluator.initialStates(serverAchievementStates + clientMap).associateBy { it.id }

        val evalOut = evaluator.evaluateOnLevelCompleted(
            context = LevelCompletionContext(
                profileId = decodedReq.playerId,
                levelId = decodedReq.levelId,
                isFirstLevel = decodedReq.levelId == "level-1",
                hintsUsedInAttempt = decodedReq.hintsUsed,
                score = decodedReq.score,
                totalCompletedLevelsCount = decodedReq.completedLevelsCount
            ),
            existingStates = mergedSeed,
            ledger = RewardLedger(balance = PlayerBalance(hints = 0, lives = 0))
        )
        serverAchievementStates = evalOut.states.associateBy { it.id }

        val responseDto = AchievementsResponseDto(
            gameId = gameId,
            isMockBackend = true,
            endpoint = "POST /v1/games/$gameId/achievements/sync",
            achievements = evalOut.states
        )
        val serialized = json.encodeToString(responseDto)
        lastAchievementsJsonPayload = serialized
        json.decodeFromString<AchievementsResponseDto>(serialized)
    }

    override suspend fun exchangeGoogleToken(request: GoogleAuthExchangeRequest): Result<AuthSessionResponse> =
        runCatching {
            simulateNetworkRoundTrip()
            val reqDecoded = json.decodeFromString<GoogleAuthExchangeRequest>(json.encodeToString(request))
            val accountSlug = reqDecoded.idToken.substringAfterLast("-", "01")
            val response = AuthSessionResponse(
                sessionToken = "mock-go-jwt-session-${reqDecoded.installationId}-$accountSlug",
                playerId = "go-player-$accountSlug",
                displayName = "Google Player ($accountSlug)",
                email = "$accountSlug@gmail.com",
                requiresGuestMerge = true
            )
            val serialized = json.encodeToString(response)
            lastAuthExchangeJsonPayload = serialized
            json.decodeFromString<AuthSessionResponse>(serialized)
        }

    override suspend fun getGameConfig(gameId: String): Result<RemoteGameConfigDto> = runCatching {
        simulateNetworkRoundTrip()
        val serialized = json.encodeToString(serverGameConfig.copy(gameId = gameId))
        lastConfigJsonPayload = serialized
        json.decodeFromString<RemoteGameConfigDto>(serialized)
    }

    override suspend fun getProgressSnapshot(
        gameId: String,
        sessionToken: String
    ): Result<PlayerProgressSnapshotDto> = runCatching {
        simulateNetworkRoundTrip()
        val repo = progressRepositoryProvider()
        PlayerProgressSnapshotDto(
            playerId = repo.getProfileId(),
            serverRevision = 1L,
            balance = repo.getBalance(),
            levels = repo.getLevels().values.toList(),
            achievements = serverAchievementStates.values.toList()
        )
    }

    override suspend fun postProgressEvents(
        gameId: String,
        sessionToken: String,
        request: ProgressEventsBatchRequest
    ): Result<ProgressEventsBatchResponse> = runCatching {
        simulateNetworkRoundTrip()
        val repo = progressRepositoryProvider()
        ProgressEventsBatchResponse(
            serverRevision = request.baseServerRevision + 1L,
            eventStatuses = request.events.map { EventStatusDto(it.eventId, "ACCEPTED") },
            mergedSnapshot = PlayerProgressSnapshotDto(
                playerId = request.playerId,
                serverRevision = request.baseServerRevision + 1L,
                balance = repo.getBalance(),
                levels = repo.getLevels().values.toList(),
                achievements = serverAchievementStates.values.toList()
            )
        )
    }

    override suspend fun createOrCheckRewardClaim(
        gameId: String,
        sessionToken: String?,
        request: RewardClaimRequestDto
    ): Result<RewardClaimResponseDto> = runCatching {
        simulateNetworkRoundTrip()
        RewardClaimResponseDto(
            claimId = request.claimId,
            status = RewardClaimStatus.CONFIRMED,
            message = "Mock BE API: Reward klaim ${request.claimId} dikonfirmasi."
        )
    }

    override suspend fun putPushToken(
        installationId: String,
        request: PushTokenRegistrationRequest
    ): Result<Unit> = runCatching {
        simulateNetworkRoundTrip()
    }

    override suspend fun verifySubscription(
        gameId: String,
        sessionToken: String?,
        request: SubscriptionVerificationRequestDto
    ): Result<SubscriptionVerificationResponseDto> = runCatching {
        simulateNetworkRoundTrip()
        val decoded = json.decodeFromString<SubscriptionVerificationRequestDto>(json.encodeToString(request))
        val thirtyDaysMs = 30L * 24L * 3600L * 1000L
        val response = SubscriptionVerificationResponseDto(
            verified = true,
            isAdFreeActive = true,
            productId = decoded.productId,
            expiresAtEpochMs = System.currentTimeMillis() + thirtyDaysMs,
            autoRenewing = true,
            message = "POST /v1/games/$gameId/subscriptions/verify · 200 OK (Langganan ${decoded.productId} aktif, seluruh iklan dimatikan)."
        )
        json.decodeFromString<SubscriptionVerificationResponseDto>(json.encodeToString(response))
    }
}
