package com.binarydev.startergame.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.binarydev.startergame.core.achievement.AchievementState
import com.binarydev.startergame.core.contracts.AccountStatus
import com.binarydev.startergame.core.contracts.AchievementSyncRequestDto
import com.binarydev.startergame.core.contracts.ActiveConfigState
import com.binarydev.startergame.core.contracts.AdFormatConfig
import com.binarydev.startergame.core.contracts.AdRewardFlowResult
import com.binarydev.startergame.core.contracts.AuthActionResult
import com.binarydev.startergame.core.contracts.ConfigRefreshSignal
import com.binarydev.startergame.core.contracts.ConfigSource
import com.binarydev.startergame.core.contracts.DevicePreferences
import com.binarydev.startergame.core.contracts.LeaderboardState
import com.binarydev.startergame.core.contracts.ProgressSyncEventDto
import com.binarydev.startergame.core.contracts.RemoteGameConfigDto
import com.binarydev.startergame.core.contracts.ScoreSubmissionRequest
import com.binarydev.startergame.core.contracts.SubscriptionEntitlementState
import com.binarydev.startergame.core.contracts.BillingActionResult
import com.binarydev.startergame.core.engine.HintRequestResult
import com.binarydev.startergame.core.engine.LevelOutcome
import com.binarydev.startergame.core.engine.StartLevelResult
import com.binarydev.startergame.core.model.GameMetadataAndFeatures
import com.binarydev.startergame.core.model.LevelProgress
import com.binarydev.startergame.core.model.LifeRecoveryMethod
import com.binarydev.startergame.core.model.PlayerBalance
import com.binarydev.startergame.core.model.RewardBundle
import com.binarydev.startergame.data.repository.SimulatedAdMode
import com.binarydev.startergame.di.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

// --- 1. Home UI State & ViewModel ---

data class HomeUiState(
    val gameTitle: String = "",
    val isDemoMode: Boolean = true,
    val accountStatus: AccountStatus = AccountStatus(profileId = "guest-local-profile", isGuest = true),
    val balance: PlayerBalance = PlayerBalance(hints = 2, lives = 3),
    val hintsEnabled: Boolean = true,
    val livesEnabled: Boolean = true,
    val rewardedAdEnabled: Boolean = true,
    val isAdFreeSubscribed: Boolean = false,
    val isLoadingAd: Boolean = false,
    val levels: List<LevelItemUi> = emptyList(),
    val activeCheckpointLevelId: String? = null,
    val statusBanner: String? = null
)

data class LevelItemUi(
    val levelId: String,
    val unlocked: Boolean,
    val completed: Boolean,
    val bestScore: Int,
    val hasCheckpoint: Boolean
)

class HomeViewModel<LevelData, State, Move>(
    private val container: AppContainer<LevelData, State, Move>
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        HomeUiState(
            gameTitle = container.ruleEngine.metadata.title,
            isDemoMode = container.isDemoMode
        )
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            container.progressRepository.ensureInitialized()
            container.configRepository.onRefreshSignal(
                signal = ConfigRefreshSignal(gameId = container.ruleEngine.metadata.gameId),
                currentTimeEpochMs = System.currentTimeMillis()
            )
        }
        viewModelScope.launch {
            combine(
                container.progressRepository.balanceFlow,
                container.progressRepository.levelsFlow,
                container.configRepository.activeConfig,
                container.sessionRepository.accountStatus,
                container.subscriptionBillingRepository.entitlementFlow
            ) { balance, levelsMap, configState, account, subEntitlement ->
                val orderedLevels = container.ruleEngine.levels.map { level ->
                    val id = container.ruleEngine.getLevelId(level)
                    val p = levelsMap[id] ?: LevelProgress(levelId = id, unlocked = id == "level-1")
                    LevelItemUi(
                        levelId = id,
                        unlocked = p.unlocked,
                        completed = p.completed,
                        bestScore = p.bestScore,
                        hasCheckpoint = !p.checkpointJson.isNullOrBlank()
                    )
                }
                val checkpointLevel = orderedLevels.firstOrNull { it.hasCheckpoint }?.levelId
                _uiState.value.copy(
                    gameTitle = container.ruleEngine.metadata.title,
                    isDemoMode = container.isDemoMode,
                    accountStatus = account,
                    balance = balance,
                    hintsEnabled = configState.config.hintsEnabled,
                    livesEnabled = configState.config.livesEnabled,
                    rewardedAdEnabled = configState.config.rewardedAd.enabled && !subEntitlement.isAdFreeSubscribed,
                    isAdFreeSubscribed = subEntitlement.isAdFreeSubscribed,
                    levels = orderedLevels,
                    activeCheckpointLevelId = checkpointLevel
                )
            }.collect { updated ->
                _uiState.value = updated
            }
        }
    }

    fun watchRewardedAdForBonus(claimId: String = "home-ad-${UUID.randomUUID()}") {
        if (_uiState.value.isLoadingAd) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingAd = true,
                    statusBanner = "Memuat iklan Rewarded AdMob..."
                )
            }
            val activeCfg = container.configRepository.getActiveConfig().config
            val reward = activeCfg.rewardedAdGrant
            val result = container.rewardClaimRepository.watchAdAndClaimReward(
                claimId = claimId,
                rewardBundle = reward,
                timestampEpochMs = System.currentTimeMillis()
            )
            val msg = when (result) {
                is AdRewardFlowResult.Granted -> result.message
                is AdRewardFlowResult.PendingServerVerification -> result.message
                is AdRewardFlowResult.DismissedEarly -> result.message
                is AdRewardFlowResult.Unavailable -> result.message
                is AdRewardFlowResult.Failed -> result.message
            }
            _uiState.update {
                it.copy(
                    isLoadingAd = false,
                    statusBanner = msg
                )
            }
        }
    }
}

// --- 2. Gameplay & Result UI State & ViewModel ---

data class GameplayUiState<State>(
    val levelId: String = "",
    val gameState: State? = null,
    val balance: PlayerBalance = PlayerBalance(hints = 2, lives = 3),
    val hintsEnabled: Boolean = true,
    val livesEnabled: Boolean = true,
    val rewardedAdEnabled: Boolean = true,
    val restoredFromCheckpoint: Boolean = false,
    val outOfLives: Boolean = false,
    val availableRecoveryMethods: List<LifeRecoveryMethod> = emptyList(),
    val statusMessage: String? = null,
    val latestResult: LevelResultSummary? = null
)

data class LevelResultSummary(
    val levelId: String,
    val attemptId: String,
    val won: Boolean,
    val score: Int,
    val bestScore: Int,
    val reason: String,
    val newlyUnlockedAchievements: List<AchievementState>,
    val rewardGranted: RewardBundle,
    val nextLevelUnlockedId: String?
)

class GameplayViewModel<LevelData, State, Move>(
    private val container: AppContainer<LevelData, State, Move>
) : ViewModel() {

    private val _uiState = MutableStateFlow(GameplayUiState<State>())
    val uiState: StateFlow<GameplayUiState<State>> = _uiState.asStateFlow()

    private var activeLevel: LevelData? = null
    private var hintCounter = 0

    init {
        viewModelScope.launch {
            combine(
                container.progressRepository.balanceFlow,
                container.configRepository.activeConfig
            ) { balance, cfgState ->
                balance to cfgState.config
            }.collect { (balance, cfg) ->
                _uiState.update { current ->
                    current.copy(
                        balance = balance,
                        hintsEnabled = cfg.hintsEnabled,
                        livesEnabled = cfg.livesEnabled,
                        rewardedAdEnabled = cfg.rewardedAd.enabled,
                        outOfLives = cfg.livesEnabled && balance.lives <= 0 && current.outOfLives
                    )
                }
            }
        }
    }

    suspend fun startLevelSuspend(
        levelId: String? = null,
        attemptId: String = "attempt-${UUID.randomUUID()}"
    ) {
        container.progressRepository.ensureInitialized()
        val level = container.ruleEngine.levels.firstOrNull {
            levelId == null || container.ruleEngine.getLevelId(it) == levelId
        } ?: container.ruleEngine.levels.first()
        activeLevel = level

        val progressMap = container.progressRepository.getLevels()
        val ledger = container.progressRepository.loadCurrentRewardLedger()
        val features = currentEffectiveFeatures()

        when (val startRes = container.coordinator.startOrResumeLevel(
            level = level,
            attemptId = attemptId,
            progressMap = progressMap,
            ledger = ledger,
            featuresOverride = features
        )) {
            is StartLevelResult.LevelLocked -> {
                _uiState.update {
                    it.copy(
                        levelId = startRes.levelId,
                        statusMessage = "Level ${startRes.levelId} masih terkunci."
                    )
                }
            }
            is StartLevelResult.OutOfLives -> {
                _uiState.update {
                    it.copy(
                        levelId = container.ruleEngine.getLevelId(level),
                        gameState = null,
                        outOfLives = true,
                        availableRecoveryMethods = startRes.recoveryMethods,
                        statusMessage = "Nyawa habis! Tonton rewarded ad atau klaim reward untuk memperoleh nyawa."
                    )
                }
            }
            is StartLevelResult.Started -> {
                _uiState.update {
                    it.copy(
                        levelId = container.ruleEngine.getLevelId(level),
                        gameState = startRes.state,
                        balance = ledger.balance,
                        hintsEnabled = features.hintsEnabled,
                        livesEnabled = features.livesEnabled,
                        restoredFromCheckpoint = startRes.restoredFromCheckpoint,
                        outOfLives = false,
                        availableRecoveryMethods = emptyList(),
                        latestResult = null,
                        statusMessage = if (startRes.restoredFromCheckpoint) {
                            "Melanjutkan dari checkpoint tersimpan."
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }

    fun startLevel(
        levelId: String? = null,
        attemptId: String = "attempt-${UUID.randomUUID()}"
    ) {
        viewModelScope.launch {
            startLevelSuspend(levelId, attemptId)
        }
    }

    suspend fun onMoveSuspend(move: Move) {
        val level = activeLevel ?: return
        val currentState = _uiState.value.gameState ?: return
        val profileId = container.progressRepository.getProfileId()
        val progressMap = container.progressRepository.getLevels()
        val ledger = container.progressRepository.loadCurrentRewardLedger()
        val achievements = container.progressRepository.getAchievements().associateBy { it.id }
        val features = currentEffectiveFeatures()
        val now = System.currentTimeMillis()

        val eval = container.coordinator.submitMove(
            profileId = profileId,
            level = level,
            currentState = currentState,
            move = move,
            progressMap = progressMap,
            ledger = ledger,
            achievements = achievements,
            featuresOverride = features,
            timestampEpochMs = now
        )

        persistEvaluationAndPublish(level, eval.newState, eval.outcome, eval.updatedLedger, eval.updatedProgress, eval.updatedAchievements.values.toList(), eval.newlyUnlockedAchievements, eval.recoveryMethodsIfOutOfLives, now)
    }

    fun onMove(move: Move) {
        viewModelScope.launch {
            onMoveSuspend(move)
        }
    }

    suspend fun onRequestHintSuspend(customTransactionId: String? = null) {
        val level = activeLevel ?: return
        val currentState = _uiState.value.gameState ?: return
        val profileId = container.progressRepository.getProfileId()
        val progressMap = container.progressRepository.getLevels()
        val ledger = container.progressRepository.loadCurrentRewardLedger()
        val achievements = container.progressRepository.getAchievements().associateBy { it.id }
        val features = currentEffectiveFeatures()
        val now = System.currentTimeMillis()
        hintCounter++
        val txId = customTransactionId ?: "hint:${container.ruleEngine.getAttemptId(currentState)}:$hintCounter"

        when (val hintRes = container.coordinator.requestHint(
            profileId = profileId,
            level = level,
            currentState = currentState,
            hintTransactionId = txId,
            progressMap = progressMap,
            ledger = ledger,
            achievements = achievements,
            featuresOverride = features,
            timestampEpochMs = now
        )) {
            is HintRequestResult.FeatureDisabled -> {
                _uiState.update { it.copy(statusMessage = "Fitur Hint dinonaktifkan pada konfigurasi game ini.") }
            }
            is HintRequestResult.InsufficientHints -> {
                _uiState.update { it.copy(statusMessage = "Saldo Hint habis. Selesaikan level atau tonton iklan untuk memperoleh Hint.") }
            }
            is HintRequestResult.NotApplicable -> {
                _uiState.update { it.copy(statusMessage = hintRes.reason) }
            }
            is HintRequestResult.Applied -> {
                persistEvaluationAndPublish(
                    level = level,
                    newState = hintRes.newState,
                    outcome = hintRes.outcome,
                    updatedLedger = hintRes.updatedLedger,
                    updatedProgress = hintRes.updatedProgress,
                    updatedAchievements = hintRes.updatedAchievements.values.toList(),
                    newlyUnlocked = hintRes.newlyUnlockedAchievements,
                    recoveryMethods = emptyList(),
                    now = now,
                    overrideMessage = hintRes.hintDescription
                )
            }
        }
    }

    fun onRequestHint(customTransactionId: String? = null) {
        viewModelScope.launch {
            onRequestHintSuspend(customTransactionId)
        }
    }

    suspend fun recoverLivesViaRewardedAdSuspend(claimId: String = "recover-life-${UUID.randomUUID()}") {
        val activeCfg = container.configRepository.getActiveConfig().config
        val grant = if (activeCfg.rewardedAdGrant.lives > 0) {
            activeCfg.rewardedAdGrant
        } else {
            RewardBundle(hints = 1, lives = 1)
        }
        val result = container.rewardClaimRepository.watchAdAndClaimReward(
            claimId = claimId,
            rewardBundle = grant,
            timestampEpochMs = System.currentTimeMillis()
        )
        val balance = container.progressRepository.getBalance()
        val message = when (result) {
            is AdRewardFlowResult.Granted -> result.message
            is AdRewardFlowResult.PendingServerVerification -> result.message
            is AdRewardFlowResult.DismissedEarly -> result.message
            is AdRewardFlowResult.Unavailable -> result.message
            is AdRewardFlowResult.Failed -> result.message
        }
        _uiState.update {
            it.copy(
                balance = balance,
                outOfLives = it.livesEnabled && balance.lives <= 0,
                statusMessage = message
            )
        }
    }

    fun recoverLivesViaRewardedAd(claimId: String = "recover-life-${UUID.randomUUID()}") {
        viewModelScope.launch {
            recoverLivesViaRewardedAdSuspend(claimId)
        }
    }

    private suspend fun persistEvaluationAndPublish(
        level: LevelData,
        newState: State,
        outcome: LevelOutcome,
        updatedLedger: com.binarydev.startergame.core.ledger.RewardLedger,
        updatedProgress: Map<String, LevelProgress>,
        updatedAchievements: List<AchievementState>,
        newlyUnlocked: List<AchievementState>,
        recoveryMethods: List<LifeRecoveryMethod>,
        now: Long,
        overrideMessage: String? = null
    ) {
        val levelId = container.ruleEngine.getLevelId(level)
        val attemptId = container.ruleEngine.getAttemptId(newState)
        val syncEvent = when (outcome) {
            is LevelOutcome.Won -> ProgressSyncEventDto(
                eventId = "evt_win:$levelId:$attemptId",
                eventType = "LEVEL_WON",
                payloadJson = """{"levelId":"$levelId","attemptId":"$attemptId","score":${outcome.score}}""",
                clientTimestampEpochMs = now
            )
            is LevelOutcome.Lost -> ProgressSyncEventDto(
                eventId = "evt_lost:$levelId:$attemptId",
                eventType = "LEVEL_LOST",
                payloadJson = """{"levelId":"$levelId","attemptId":"$attemptId"}""",
                clientTimestampEpochMs = now
            )
            is LevelOutcome.InProgress -> null
        }

        container.progressRepository.persistLedgerResults(updatedLedger, now)
        container.progressRepository.saveGameState(
            levels = updatedProgress,
            achievements = updatedAchievements,
            syncEvent = syncEvent
        )

        if (outcome is LevelOutcome.Won) {
            container.leaderboardRepository.submitScore(
                ScoreSubmissionRequest(
                    attemptId = attemptId,
                    levelId = levelId,
                    score = outcome.score,
                    validatorPayload = outcome.validatorPayload
                )
            )
            container.activeBackendApiClient.syncAchievements(
                gameId = container.ruleEngine.metadata.gameId,
                request = AchievementSyncRequestDto(
                    playerId = container.progressRepository.getProfileId(),
                    levelId = levelId,
                    attemptId = attemptId,
                    score = outcome.score,
                    hintsUsed = container.ruleEngine.getHintsUsedCount(newState),
                    completedLevelsCount = updatedProgress.values.count { it.completed },
                    clientAchievements = updatedAchievements
                )
            )
        }

        val features = currentEffectiveFeatures()
        val resultSummary = when (outcome) {
            is LevelOutcome.InProgress -> null
            is LevelOutcome.Won -> {
                val currentIdx = container.ruleEngine.levels.indexOfFirst { container.ruleEngine.getLevelId(it) == levelId }
                val nextId = container.ruleEngine.levels.getOrNull(currentIdx + 1)?.let { container.ruleEngine.getLevelId(it) }
                LevelResultSummary(
                    levelId = levelId,
                    attemptId = attemptId,
                    won = true,
                    score = outcome.score,
                    bestScore = updatedProgress[levelId]?.bestScore ?: outcome.score,
                    reason = "Level berhasil diselesaikan!",
                    newlyUnlockedAchievements = newlyUnlocked,
                    rewardGranted = features.levelCompletionReward,
                    nextLevelUnlockedId = nextId
                )
            }
            is LevelOutcome.Lost -> LevelResultSummary(
                levelId = levelId,
                attemptId = attemptId,
                won = false,
                score = 0,
                bestScore = updatedProgress[levelId]?.bestScore ?: 0,
                reason = outcome.reason,
                newlyUnlockedAchievements = emptyList(),
                rewardGranted = RewardBundle(),
                nextLevelUnlockedId = null
            )
        }

        _uiState.update {
            it.copy(
                levelId = levelId,
                gameState = newState,
                balance = updatedLedger.balance,
                outOfLives = features.livesEnabled && updatedLedger.balance.lives <= 0,
                availableRecoveryMethods = recoveryMethods,
                statusMessage = overrideMessage,
                latestResult = resultSummary
            )
        }
    }

    private suspend fun currentEffectiveFeatures(): GameMetadataAndFeatures {
        val base = container.ruleEngine.metadata
        val remote = container.configRepository.getActiveConfig().config
        return base.copy(
            hintsEnabled = remote.hintsEnabled,
            livesEnabled = remote.livesEnabled,
            levelCompletionReward = remote.levelCompletionGrant,
            rewardedAdReward = remote.rewardedAdGrant
        )
    }
}

// --- 3. Leaderboard ViewModel ---

class LeaderboardViewModel<LevelData, State, Move>(
    private val container: AppContainer<LevelData, State, Move>
) : ViewModel() {

    private val _state = MutableStateFlow<LeaderboardState>(LeaderboardState.Loading)
    val state: StateFlow<LeaderboardState> = _state.asStateFlow()

    private val _lastRawJson = MutableStateFlow("")
    val lastRawJson: StateFlow<String> = _lastRawJson.asStateFlow()

    private val _isSimulatingOffline = MutableStateFlow(false)
    val isSimulatingOffline: StateFlow<Boolean> = _isSimulatingOffline.asStateFlow()

    init {
        refresh()
    }

    suspend fun refreshSuspend() {
        _state.value = LeaderboardState.Loading
        _state.value = container.leaderboardRepository.fetchLeaderboard()
        _lastRawJson.value = container.mockBackendApiClient.lastLeaderboardJsonPayload
        _isSimulatingOffline.value = container.mockBackendApiClient.simulateServerUnavailable
    }

    fun refresh() {
        viewModelScope.launch {
            refreshSuspend()
        }
    }

    fun toggleSimulateBackendOffline() {
        viewModelScope.launch {
            container.mockBackendApiClient.simulateServerUnavailable =
                !container.mockBackendApiClient.simulateServerUnavailable
            refreshSuspend()
        }
    }
}

// --- 4. Achievements ViewModel (Synced via Mock/Connected Go Backend API) ---

data class AchievementsUiState(
    val isLoading: Boolean = false,
    val isMockBackend: Boolean = true,
    val endpointLabel: String = "GET /v1/games/{gameId}/achievements",
    val statusBanner: String = "Memuat data pencapaian dari API Backend...",
    val achievements: List<AchievementState> = emptyList(),
    val lastRawJson: String = "",
    val isSimulatingOffline: Boolean = false
)

class AchievementsViewModel<LevelData, State, Move>(
    private val container: AppContainer<LevelData, State, Move>
) : ViewModel() {

    private val _achievements = MutableStateFlow<List<AchievementState>>(emptyList())
    val achievements: StateFlow<List<AchievementState>> = _achievements.asStateFlow()

    private val _uiState = MutableStateFlow(
        AchievementsUiState(
            isMockBackend = container.isDemoMode,
            endpointLabel = "GET /v1/games/${container.ruleEngine.metadata.gameId}/achievements"
        )
    )
    val uiState: StateFlow<AchievementsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            container.progressRepository.ensureInitialized()
            refreshFromApiSuspend()
            container.progressRepository.achievementsFlow.collect {
                refreshFromApiSuspend()
            }
        }
    }

    suspend fun refreshFromApiSuspend() {
        val gameId = container.ruleEngine.metadata.gameId
        _uiState.update { it.copy(isLoading = true) }

        val apiResult = container.activeBackendApiClient.getAchievements(gameId)
        apiResult.fold(
            onSuccess = { response ->
                _achievements.value = response.achievements
                _uiState.value = AchievementsUiState(
                    isLoading = false,
                    isMockBackend = response.isMockBackend,
                    endpointLabel = response.endpoint,
                    statusBanner = if (response.isMockBackend) {
                        "${response.endpoint} · 200 OK (Simulasi API BE Mockup JSON · ${response.achievements.size} item)"
                    } else {
                        "${response.endpoint} · 200 OK (Server Go Terhubung)"
                    },
                    achievements = response.achievements,
                    lastRawJson = container.mockBackendApiClient.lastAchievementsJsonPayload,
                    isSimulatingOffline = container.mockBackendApiClient.simulateServerUnavailable
                )
            },
            onFailure = { err ->
                val localFallback = container.progressRepository.getAchievements()
                _achievements.value = localFallback
                _uiState.value = AchievementsUiState(
                    isLoading = false,
                    isMockBackend = container.isDemoMode,
                    endpointLabel = "GET /v1/games/$gameId/achievements",
                    statusBanner = "API BE tidak tersedia (${err.message}). Menampilkan fallback data lokal Room (${localFallback.size} item).",
                    achievements = localFallback,
                    lastRawJson = container.mockBackendApiClient.lastAchievementsJsonPayload,
                    isSimulatingOffline = container.mockBackendApiClient.simulateServerUnavailable
                )
            }
        )
    }

    fun refreshFromApi() {
        viewModelScope.launch {
            refreshFromApiSuspend()
        }
    }

    fun toggleSimulateBackendOffline() {
        viewModelScope.launch {
            container.mockBackendApiClient.simulateServerUnavailable =
                !container.mockBackendApiClient.simulateServerUnavailable
            refreshFromApiSuspend()
        }
    }
}

// --- 5. Settings ViewModel ---

data class SettingsUiState(
    val preferences: DevicePreferences = DevicePreferences(),
    val accountStatus: AccountStatus = AccountStatus(profileId = "guest-local-profile", isGuest = true),
    val configState: ActiveConfigState = ActiveConfigState(
        config = RemoteGameConfigDto.safeAdsOffDefault(),
        source = ConfigSource.SAFE_DEFAULT_ADS_OFF
    ),
    val subscriptionEntitlement: SubscriptionEntitlementState = SubscriptionEntitlementState(),
    val privacyPolicyUrl: String = "https://example.com/privacy",
    val isDemoMode: Boolean = true,
    val simulatedAdMode: SimulatedAdMode = SimulatedAdMode.COMPLETE_WITH_REWARD,
    val useRealAdMobTestAds: Boolean = true,
    val lastFcmPushSummary: String = "",
    val lastConfigJsonPayload: String = "",
    val feedbackMessage: String? = null
)

class SettingsViewModel<LevelData, State, Move>(
    private val container: AppContainer<LevelData, State, Move>
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            privacyPolicyUrl = container.ruleEngine.metadata.privacyPolicyUrl,
            isDemoMode = container.isDemoMode,
            useRealAdMobTestAds = container.switchableAdService.useRealAdMobTestAds
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                container.devicePreferencesRepository.preferencesFlow,
                container.sessionRepository.accountStatus,
                container.configRepository.activeConfig,
                container.fcmAdapter.lastReceivedPushSummary,
                container.subscriptionBillingRepository.entitlementFlow
            ) { prefs, account, cfg, fcmSummary, subEntitlement ->
                _uiState.value.copy(
                    preferences = prefs,
                    accountStatus = account,
                    configState = cfg,
                    subscriptionEntitlement = subEntitlement,
                    privacyPolicyUrl = container.ruleEngine.metadata.privacyPolicyUrl,
                    isDemoMode = container.isDemoMode,
                    simulatedAdMode = container.simulatedAdAdapter.nextMode,
                    useRealAdMobTestAds = container.switchableAdService.useRealAdMobTestAds,
                    lastFcmPushSummary = fcmSummary,
                    lastConfigJsonPayload = container.mockBackendApiClient.lastConfigJsonPayload
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    fun toggleMusic(enabled: Boolean) {
        viewModelScope.launch {
            container.devicePreferencesRepository.setMusicEnabled(enabled)
        }
    }

    fun toggleSoundEffects(enabled: Boolean) {
        viewModelScope.launch {
            container.devicePreferencesRepository.setSoundEffectsEnabled(enabled)
        }
    }

    fun toggleVibration(enabled: Boolean) {
        viewModelScope.launch {
            container.devicePreferencesRepository.setVibrationEnabled(enabled)
        }
    }

    suspend fun signInWithGoogleSuspend() {
        val result = container.sessionRepository.signInWithGoogle()
        val msg = when (result) {
            is AuthActionResult.SignedIn -> result.status.syncStatusMessage
            is AuthActionResult.Cancelled -> result.message
            is AuthActionResult.LocalOnlyFallback -> result.message
            is AuthActionResult.Error -> result.message
        }
        _uiState.update { it.copy(feedbackMessage = msg) }
    }

    fun signInWithGoogle() {
        viewModelScope.launch {
            signInWithGoogleSuspend()
        }
    }

    suspend fun signOutSuspend() {
        container.sessionRepository.signOut()
        _uiState.update {
            it.copy(feedbackMessage = "Berhasil keluar akun. Progress lokal tetap tersimpan di perangkat.")
        }
    }

    fun signOut() {
        viewModelScope.launch {
            signOutSuspend()
        }
    }

    fun subscribeRemoveAdsGooglePlay() {
        viewModelScope.launch {
            val result = container.subscriptionBillingRepository.launchRemoveAdsSubscriptionFlow()
            val msg = when (result) {
                is BillingActionResult.Subscribed -> result.message
                is BillingActionResult.Restored -> result.message
                is BillingActionResult.Cancelled -> result.message
                is BillingActionResult.Error -> result.message
            }
            _uiState.update { it.copy(feedbackMessage = msg) }
        }
    }

    fun restoreRemoveAdsSubscription() {
        viewModelScope.launch {
            val result = container.subscriptionBillingRepository.restorePurchases()
            val msg = when (result) {
                is BillingActionResult.Subscribed -> result.message
                is BillingActionResult.Restored -> result.message
                is BillingActionResult.Cancelled -> result.message
                is BillingActionResult.Error -> result.message
            }
            _uiState.update { it.copy(feedbackMessage = msg) }
        }
    }

    fun toggleSimulateSandboxSubscription() {
        viewModelScope.launch {
            val currentlyActive = _uiState.value.subscriptionEntitlement.isAdFreeSubscribed
            val result = container.subscriptionBillingRepository.simulateSandboxSubscription(!currentlyActive)
            val msg = when (result) {
                is BillingActionResult.Subscribed -> result.message
                is BillingActionResult.Restored -> result.message
                is BillingActionResult.Cancelled -> result.message
                is BillingActionResult.Error -> result.message
            }
            _uiState.update { it.copy(feedbackMessage = msg) }
        }
    }

    fun setSimulatedAdMode(mode: SimulatedAdMode) {
        container.simulatedAdAdapter.nextMode = mode
        _uiState.update {
            it.copy(
                simulatedAdMode = mode,
                feedbackMessage = "Mode simulasi iklan diubah ke: ${mode.name}"
            )
        }
    }

    fun setUseRealAdMobTestAds(enabled: Boolean) {
        container.switchableAdService.useRealAdMobTestAds = enabled
        _uiState.update {
            it.copy(
                useRealAdMobTestAds = enabled,
                feedbackMessage = if (enabled) {
                    "Menggunakan Google AdMob Test Ad SDK asli."
                } else {
                    "Menggunakan Video Player Iklan Simulasi Interaktif (5 detik)."
                }
            )
        }
    }

    suspend fun triggerSimulatedConfigChangeSuspend(
        hintsEnabled: Boolean,
        livesEnabled: Boolean,
        rewardedAdsEnabled: Boolean,
        simulateApiFailure: Boolean = false,
        bannerAdsEnabled: Boolean = _uiState.value.configState.config.bannerAd.enabled,
        interstitialAdsEnabled: Boolean = _uiState.value.configState.config.interstitialAd.enabled
    ) {
        // 1. Update Mock Go Backend API (/v1/games/{gameId}/config) & generate FCM push payload
        val fcmPayload = container.mockBackendApiClient.updateServerConfigFromMockAdminPanel(
            hintsEnabled = hintsEnabled,
            livesEnabled = livesEnabled,
            bannerAdEnabled = bannerAdsEnabled,
            rewardedAdEnabled = rewardedAdsEnabled,
            interstitialAdEnabled = interstitialAdsEnabled
        )
        container.mockBackendApiClient.simulateServerUnavailable = simulateApiFailure

        val sim = container.simulatedRemoteConfigAdapter
        if (sim != null) {
            sim.shouldSimulateNetworkFailure = simulateApiFailure
            if (!simulateApiFailure) {
                sim.simulatedConfig = container.mockBackendApiClient.serverGameConfig
                // Also record the JSON payload from MockGoBackendApiClient
                container.mockBackendApiClient.getGameConfig(container.ruleEngine.metadata.gameId)
            } else {
                // When simulating BE server offline from the live control panel, expire cached TTL so safe ads-off default takes effect immediately
                val expiredDto = container.mockBackendApiClient.serverGameConfig.copy(ttlSeconds = 0L)
                (container.devicePreferencesRepository as? com.binarydev.startergame.data.preferences.ConfigCacheStore)
                    ?.saveCachedConfig(expiredDto, 0L)
            }
        }

        // 2. Route through the exact same FCM Push handler (`FcmPushTokenAndRefreshAdapter.handleIncomingPushData`)
        // that `StarterFirebaseMessagingService.onMessageReceived` invokes in production!
        val updated = container.fcmAdapter.handleIncomingPushData(
            data = fcmPayload,
            currentTimeEpochMs = System.currentTimeMillis()
        ) ?: container.configRepository.onRefreshSignal(
            signal = ConfigRefreshSignal(
                gameId = container.ruleEngine.metadata.gameId,
                targetVersion = container.mockBackendApiClient.serverGameConfig.configVersion
            ),
            currentTimeEpochMs = System.currentTimeMillis()
        )

        _uiState.update {
            it.copy(
                configState = updated,
                lastConfigJsonPayload = container.mockBackendApiClient.lastConfigJsonPayload,
                feedbackMessage = "FCM Push -> GET /v1/games/${container.ruleEngine.metadata.gameId}/config (v${updated.config.configVersion}) diterapkan langsung tanpa reload!"
            )
        }
    }

    fun triggerSimulatedConfigChange(
        hintsEnabled: Boolean,
        livesEnabled: Boolean,
        rewardedAdsEnabled: Boolean,
        simulateApiFailure: Boolean = false,
        bannerAdsEnabled: Boolean = _uiState.value.configState.config.bannerAd.enabled,
        interstitialAdsEnabled: Boolean = _uiState.value.configState.config.interstitialAd.enabled
    ) {
        viewModelScope.launch {
            triggerSimulatedConfigChangeSuspend(
                hintsEnabled = hintsEnabled,
                livesEnabled = livesEnabled,
                rewardedAdsEnabled = rewardedAdsEnabled,
                simulateApiFailure = simulateApiFailure,
                bannerAdsEnabled = bannerAdsEnabled,
                interstitialAdsEnabled = interstitialAdsEnabled
            )
        }
    }
}
