package com.binarydev.startergame

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.binarydev.startergame.core.achievement.AchievementDefinition
import com.binarydev.startergame.core.achievement.AchievementEvaluator
import com.binarydev.startergame.core.contracts.AdFormatConfig
import com.binarydev.startergame.core.contracts.AdRewardFlowResult
import com.binarydev.startergame.core.contracts.AuthActionResult
import com.binarydev.startergame.core.contracts.AuthSessionResponse
import com.binarydev.startergame.core.contracts.ConfigRefreshSignal
import com.binarydev.startergame.core.contracts.ConfigSource
import com.binarydev.startergame.core.contracts.EventStatusDto
import com.binarydev.startergame.core.contracts.GoogleAuthExchangeRequest
import com.binarydev.startergame.core.contracts.LeaderboardEntryDto
import com.binarydev.startergame.core.contracts.LeaderboardResponseDto
import com.binarydev.startergame.core.contracts.LeaderboardState
import com.binarydev.startergame.core.contracts.PlayerProgressSnapshotDto
import com.binarydev.startergame.core.contracts.ProgressEventsBatchRequest
import com.binarydev.startergame.core.contracts.ProgressEventsBatchResponse
import com.binarydev.startergame.core.contracts.PushTokenRegistrationRequest
import com.binarydev.startergame.core.contracts.RawAdShowResult
import com.binarydev.startergame.core.contracts.RemoteGameConfigDto
import com.binarydev.startergame.core.contracts.RewardClaimRequestDto
import com.binarydev.startergame.core.contracts.RewardClaimResponseDto
import com.binarydev.startergame.core.contracts.RewardClaimStatus
import com.binarydev.startergame.core.contracts.ScoreSubmissionRequest
import com.binarydev.startergame.core.contracts.ScoreSubmissionResponse
import com.binarydev.startergame.core.engine.HintApplicationResult
import com.binarydev.startergame.core.engine.LevelOutcome
import com.binarydev.startergame.core.engine.LevelRuleEngine
import com.binarydev.startergame.core.model.GameMetadataAndFeatures
import com.binarydev.startergame.core.model.LevelProgress
import com.binarydev.startergame.core.model.PlayerBalance
import com.binarydev.startergame.core.model.RewardBundle
import com.binarydev.startergame.core.model.RewardSource
import com.binarydev.startergame.core.sample.SamplePuzzleGame
import com.binarydev.startergame.core.sample.SamplePuzzleMove
import com.binarydev.startergame.data.integration.AdMobRewardedAdAdapter
import com.binarydev.startergame.data.integration.FcmPushTokenAndRefreshAdapter
import com.binarydev.startergame.data.integration.GooglePickerResult
import com.binarydev.startergame.data.integration.SessionRepositoryImpl
import com.binarydev.startergame.data.integration.SimulatedGoogleIdTokenProvider
import com.binarydev.startergame.data.local.StarterGameDatabase
import com.binarydev.startergame.data.preferences.DataStoreDevicePreferencesRepository
import com.binarydev.startergame.data.remote.GoBackendApiClient
import com.binarydev.startergame.data.repository.ConfigRepositoryImpl
import com.binarydev.startergame.data.repository.ConnectedLeaderboardRepository
import com.binarydev.startergame.data.repository.RewardClaimService
import com.binarydev.startergame.data.repository.RoomProgressRepository
import com.binarydev.startergame.data.repository.SimulatedAdAdapter
import com.binarydev.startergame.data.repository.SimulatedAdMode
import com.binarydev.startergame.data.repository.SimulatedLeaderboardAdapter
import com.binarydev.startergame.data.repository.SimulatedRemoteConfigAdapter
import com.binarydev.startergame.di.StarterAppContainer
import com.binarydev.startergame.ui.theme.StarterThemeConfig
import com.binarydev.startergame.ui.theme.buildStarterTypography
import com.binarydev.startergame.ui.viewmodel.GameplayViewModel
import com.binarydev.startergame.ui.viewmodel.LeaderboardViewModel
import com.binarydev.startergame.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AppStarterTestSuite {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private lateinit var prefsFile: File
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var database: StarterGameDatabase
    private lateinit var dataStore: DataStore<Preferences>

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        context = ApplicationProvider.getApplicationContext()
        val uniqueSuffix = UUID.randomUUID().toString()
        dbFile = File(context.cacheDir, "test_starter_$uniqueSuffix.db")
        prefsFile = File(context.cacheDir, "test_prefs_$uniqueSuffix.preferences_pb")
        database = Room.databaseBuilder(context, StarterGameDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { prefsFile }
        )
    }

    @After
    fun tearDown() {
        database.close()
        dataStoreScope.cancel()
        Dispatchers.resetMain()
        dbFile.delete()
        prefsFile.delete()
    }

    // --- Group 3 Tests: Room, DataStore & Offline Guest Restart Persistence ---

    @Test
    fun roomAndDataStorePersistProgressBalancesAchievementsAndPreferencesAcrossRestart() = runTest {
        val game = SamplePuzzleGame()
        val progressRepo1 = RoomProgressRepository(database.starterGameDao(), game)
        val prefsRepo1 = DataStoreDevicePreferencesRepository(dataStore)

        // 1. Clean guest bootstrap
        assertEquals("guest-local-profile", progressRepo1.getProfileId())
        assertEquals(PlayerBalance(hints = 2, lives = 3), progressRepo1.getBalance())

        // 2. Grant reward idempotently & save checkpoint + achievement + toggle preferences
        val grantedFirst = progressRepo1.grantRewardIdempotent(
            transactionId = "sim-ad-1",
            bundle = RewardBundle(hints = 1, lives = 1),
            sourceName = RewardSource.REWARDED_AD.name,
            timestampEpochMs = 1000L
        )
        assertTrue(grantedFirst)

        // Duplicate callback with same transactionId must not increment again
        val grantedDuplicate = progressRepo1.grantRewardIdempotent(
            transactionId = "sim-ad-1",
            bundle = RewardBundle(hints = 1, lives = 1),
            sourceName = RewardSource.REWARDED_AD.name,
            timestampEpochMs = 1001L
        )
        assertFalse(grantedDuplicate)
        assertEquals(PlayerBalance(hints = 3, lives = 4), progressRepo1.getBalance())

        // Failed attempt deducts 1 life even when reprocessed
        assertTrue(progressRepo1.deductLifeForFailedAttemptIdempotent("fail-attempt-1", 1002L))
        assertFalse(progressRepo1.deductLifeForFailedAttemptIdempotent("fail-attempt-1", 1003L))
        assertEquals(PlayerBalance(hints = 3, lives = 3), progressRepo1.getBalance())

        // Save level checkpoint and unlocked achievement
        val savedLevels = mapOf(
            "level-1" to LevelProgress("level-1", unlocked = true, completed = true, bestScore = 950, checkpointJson = """{"step":3}"""),
            "level-2" to LevelProgress("level-2", unlocked = true, completed = false, bestScore = 0, checkpointJson = null)
        )
        val unlockedAchievements = AchievementEvaluator().initialStates().map {
            it.copy(currentProgress = 1, unlocked = true)
        }
        progressRepo1.saveGameState(savedLevels, unlockedAchievements)

        prefsRepo1.setMusicEnabled(false)
        prefsRepo1.setSoundEffectsEnabled(true)
        prefsRepo1.setVibrationEnabled(false)

        // 3. Close database and re-open a fresh Room instance pointing to the same file (simulating app restart)
        database.close()
        val reopenedDb = Room.databaseBuilder(context, StarterGameDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .build()
        try {
            val progressRepo2 = RoomProgressRepository(reopenedDb.starterGameDao(), game)
            val prefsRepo2 = DataStoreDevicePreferencesRepository(dataStore)

            assertEquals(PlayerBalance(hints = 3, lives = 3), progressRepo2.getBalance())
            assertEquals(950, progressRepo2.getLevels()["level-1"]?.bestScore)
            assertEquals("""{"step":3}""", progressRepo2.getLevels()["level-1"]?.checkpointJson)
            assertTrue(progressRepo2.getLevels()["level-2"]?.unlocked == true)
            assertTrue(progressRepo2.getAchievements().all { it.unlocked })

            // Duplicate transaction ID after restart is STILL rejected!
            assertFalse(
                progressRepo2.grantRewardIdempotent(
                    transactionId = "sim-ad-1",
                    bundle = RewardBundle(hints = 1, lives = 1),
                    sourceName = RewardSource.REWARDED_AD.name
                )
            )

            val restoredPrefs = prefsRepo2.getPreferences()
            assertFalse(restoredPrefs.musicEnabled)
            assertTrue(restoredPrefs.soundEffectsEnabled)
            assertFalse(restoredPrefs.vibrationEnabled)
        } finally {
            reopenedDb.close()
        }
    }

    // --- Group 4 Tests: Go v1 JSON Contracts, Leaderboard, Ads, and 3-Tier Remote Config Fallback ---

    @Test
    fun goBackendV1JsonContractsSerializeAndDeserializeCleanly() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        val authReq = GoogleAuthExchangeRequest("id-tok", "inst-1", "guest-1")
        val authDecoded = json.decodeFromString<GoogleAuthExchangeRequest>(json.encodeToString(authReq))
        assertEquals(authReq, authDecoded)

        val cfgDto = RemoteGameConfigDto(
            gameId = "sample-puzzle-v1",
            configVersion = 3,
            ttlSeconds = 1800,
            hintsEnabled = true,
            livesEnabled = false,
            rewardedAd = AdFormatConfig(enabled = true, maxImpressionsPerSession = 5)
        )
        val cfgDecoded = json.decodeFromString<RemoteGameConfigDto>(json.encodeToString(cfgDto))
        assertEquals(cfgDto, cfgDecoded)
        assertFalse(cfgDecoded.interstitialAd.enabled)
        assertFalse(cfgDecoded.bannerAd.enabled)

        val claimReq = RewardClaimRequestDto("claim-99", "admob", RewardBundle(hints = 1, lives = 1))
        assertEquals(claimReq, json.decodeFromString<RewardClaimRequestDto>(json.encodeToString(claimReq)))

        val pushReq = PushTokenRegistrationRequest("inst-1", "fcm-tok-1", "player-1")
        assertEquals(pushReq, json.decodeFromString<PushTokenRegistrationRequest>(json.encodeToString(pushReq)))
    }

    @Test
    fun demoAndConnectedLeaderboardsHandleDemoLabelAndServerStates() = runTest {
        val game = SamplePuzzleGame()
        val progressRepo = RoomProgressRepository(database.starterGameDao(), game)
        val demoLeaderboard = SimulatedLeaderboardAdapter(progressRepo)

        val demoState = demoLeaderboard.fetchLeaderboard()
        assertTrue(demoState is LeaderboardState.Loaded)
        assertTrue((demoState as LeaderboardState.Loaded).isDemo)
        assertTrue(demoState.entries.isNotEmpty())

        // Connected Leaderboard: test empty, loaded, and unavailable states
        val fakeApi = FakeGoBackendApiClient()
        val connectedRepo = ConnectedLeaderboardRepository("sample-puzzle-v1", fakeApi)

        // 1. Server returns empty list
        fakeApi.leaderboardResult = Result.success(LeaderboardResponseDto("sample-puzzle-v1", isDemo = false, entries = emptyList()))
        assertTrue(connectedRepo.fetchLeaderboard() is LeaderboardState.Empty)

        // 2. Server returns verified scores
        fakeApi.leaderboardResult = Result.success(
            LeaderboardResponseDto(
                gameId = "sample-puzzle-v1",
                isDemo = false,
                entries = listOf(LeaderboardEntryDto(1, "p-1", "Server Verified Champion", 1200))
            )
        )
        val loadedGlobal = connectedRepo.fetchLeaderboard()
        assertTrue(loadedGlobal is LeaderboardState.Loaded)
        assertFalse((loadedGlobal as LeaderboardState.Loaded).isDemo)
        assertEquals("Server Verified Champion", loadedGlobal.entries.first().displayName)

        // 3. Server unreachable -> Unavailable state
        fakeApi.leaderboardResult = Result.failure(IllegalStateException("Connection refused"))
        assertTrue(connectedRepo.fetchLeaderboard() is LeaderboardState.Unavailable)
    }

    @Test
    fun rewardedAdHandlesCompletionDismissalFailureDuplicateCallbackAndServerClaimPending() = runTest {
        val game = SamplePuzzleGame()
        val progressRepo = RoomProgressRepository(database.starterGameDao(), game)
        val simAd = SimulatedAdAdapter()
        val localClaimService = RewardClaimService(
            gameId = game.metadata.gameId,
            adService = simAd,
            progressRepository = progressRepo,
            useServerVerification = false
        )

        // 1. Dismissed early -> balance unchanged
        simAd.nextMode = SimulatedAdMode.DISMISS_EARLY
        val dismissedRes = localClaimService.watchAdAndClaimReward("claim-dismiss", RewardBundle(hints = 1, lives = 1))
        assertTrue(dismissedRes is AdRewardFlowResult.DismissedEarly)
        assertEquals(PlayerBalance(hints = 2, lives = 3), progressRepo.getBalance())

        // 2. Failed to load -> balance unchanged
        simAd.nextMode = SimulatedAdMode.AD_LOAD_FAILED
        val failedRes = localClaimService.watchAdAndClaimReward("claim-fail", RewardBundle(hints = 1, lives = 1))
        assertTrue(failedRes is AdRewardFlowResult.Failed)
        assertEquals(PlayerBalance(hints = 2, lives = 3), progressRepo.getBalance())

        // 3. Completed -> balance increments once; duplicate callback with same claimId does not increment again
        simAd.nextMode = SimulatedAdMode.COMPLETE_WITH_REWARD
        val granted1 = localClaimService.watchAdAndClaimReward("claim-success-1", RewardBundle(hints = 1, lives = 1))
        assertTrue(granted1 is AdRewardFlowResult.Granted)
        assertFalse((granted1 as AdRewardFlowResult.Granted).duplicateIgnored)
        assertEquals(PlayerBalance(hints = 3, lives = 4), progressRepo.getBalance())

        val grantedDup = localClaimService.watchAdAndClaimReward("claim-success-1", RewardBundle(hints = 1, lives = 1))
        assertTrue(grantedDup is AdRewardFlowResult.Granted)
        assertTrue((grantedDup as AdRewardFlowResult.Granted).duplicateIgnored)
        assertEquals(PlayerBalance(hints = 3, lives = 4), progressRepo.getBalance())

        // 4. Connected Server-Verified mode: PENDING then CONFIRMED
        val fakeApi = FakeGoBackendApiClient()
        val serverClaimService = RewardClaimService(
            gameId = game.metadata.gameId,
            adService = simAd,
            progressRepository = progressRepo,
            backendApiClient = fakeApi,
            useServerVerification = true
        )
        fakeApi.rewardClaimResult = Result.success(
            RewardClaimResponseDto(claimId = "claim-srv-1", status = RewardClaimStatus.PENDING)
        )
        val pendingRes = serverClaimService.watchAdAndClaimReward("claim-srv-1", RewardBundle(hints = 2, lives = 0))
        assertTrue(pendingRes is AdRewardFlowResult.PendingServerVerification)
        // Balance must not increase while PENDING
        assertEquals(PlayerBalance(hints = 3, lives = 4), progressRepo.getBalance())

        // Server confirms callback
        fakeApi.rewardClaimResult = Result.success(
            RewardClaimResponseDto(claimId = "claim-srv-1", status = RewardClaimStatus.CONFIRMED)
        )
        val confirmedRes = serverClaimService.watchAdAndClaimReward("claim-srv-1", RewardBundle(hints = 2, lives = 0))
        assertTrue(confirmedRes is AdRewardFlowResult.Granted)
        assertEquals(PlayerBalance(hints = 5, lives = 4), progressRepo.getBalance())
    }

    @Test
    fun remoteConfigRefreshSignalCacheFallbackAndSafeAdsOffDefaultFallback() = runTest {
        val game = SamplePuzzleGame()
        val prefsRepo = DataStoreDevicePreferencesRepository(dataStore)
        val simRemote = SimulatedRemoteConfigAdapter(game.metadata)
        val configRepo = ConfigRepositoryImpl(
            metadata = game.metadata,
            cacheStore = prefsRepo,
            simulatedAdapter = simRemote
        )

        // 1. Pull live v2 config via refresh signal
        simRemote.simulatedConfig = simRemote.simulatedConfig.copy(
            configVersion = 2,
            ttlSeconds = 60L,
            hintsEnabled = false,
            rewardedAd = AdFormatConfig(enabled = true, maxImpressionsPerSession = 5)
        )
        val liveState = configRepo.onRefreshSignal(ConfigRefreshSignal(game.metadata.gameId, 2), currentTimeEpochMs = 10_000L)
        assertEquals(ConfigSource.REMOTE_LIVE, liveState.source)
        assertFalse(liveState.config.hintsEnabled)
        assertTrue(liveState.config.rewardedAd.enabled)

        // 2. Simulate API failure while cache is still valid (at t = 30s <= 10s + 60s) -> falls back to LOCAL_CACHE
        simRemote.shouldSimulateNetworkFailure = true
        val cachedFallback = configRepo.onRefreshSignal(ConfigRefreshSignal(game.metadata.gameId, 3), currentTimeEpochMs = 30_000L)
        assertEquals(ConfigSource.LOCAL_CACHE, cachedFallback.source)
        assertEquals(2, cachedFallback.config.configVersion)
        assertTrue(cachedFallback.config.rewardedAd.enabled)

        // 3. Simulate API failure after cache TTL expires (at t = 100s > 70s) -> falls back to SAFE_DEFAULT_ADS_OFF with ads disabled
        val defaultFallback = configRepo.refreshIfNeeded(currentTimeEpochMs = 100_000L)
        assertEquals(ConfigSource.SAFE_DEFAULT_ADS_OFF, defaultFallback.source)
        assertFalse(defaultFallback.config.rewardedAd.enabled)
        assertFalse(defaultFallback.config.interstitialAd.enabled)
        assertFalse(defaultFallback.config.bannerAd.enabled)
    }

    // --- Group 5 Tests: Google Sign-In, Guest Merge, AdMob Lifecycle Mapping & FCM Refresh Routing ---

    @Test
    fun googleSignInHandlesCancellationOfflineBackendFallbackAndExplicitGuestMerge() = runTest {
        val game = SamplePuzzleGame()
        val prefsRepo = DataStoreDevicePreferencesRepository(dataStore)
        val fakeApi = FakeGoBackendApiClient()
        val simGoogle = SimulatedGoogleIdTokenProvider()

        var sessionTokenHolder: String? = null
        val progressRepo = RoomProgressRepository(
            dao = database.starterGameDao(),
            ruleEngine = game,
            backendApiClient = fakeApi,
            sessionTokenProvider = { sessionTokenHolder }
        )
        val configRepo = ConfigRepositoryImpl(game.metadata, prefsRepo, backendApiClient = fakeApi)
        val fcmAdapter = FcmPushTokenAndRefreshAdapter(game.metadata.gameId, prefsRepo, configRepo, fakeApi)
        val sessionRepo = SessionRepositoryImpl(
            dao = database.starterGameDao(),
            progressRepository = progressRepo,
            preferencesRepository = prefsRepo,
            googleIdTokenProvider = simGoogle,
            backendApiClient = fakeApi,
            pushTokenRepository = fcmAdapter
        )

        // 1. User cancels Google picker -> continues as guest
        simGoogle.simulatedResult = GooglePickerResult.Cancelled()
        val cancelledRes = sessionRepo.signInWithGoogle()
        assertTrue(cancelledRes is AuthActionResult.Cancelled)
        assertTrue(sessionRepo.getCurrentAccountStatus().isGuest)

        // 2. User picks Google account, but Go server is offline -> keeps local profile & explains sync not active
        simGoogle.simulatedResult = GooglePickerResult.Success("tok-123", "Budi", "budi@example.com")
        fakeApi.authResult = Result.failure(IllegalStateException("Backend offline"))
        val offlineFallback = sessionRepo.signInWithGoogle()
        assertTrue(offlineFallback is AuthActionResult.LocalOnlyFallback)
        assertFalse(sessionRepo.getCurrentAccountStatus().isBackendSyncActive)
        assertTrue(sessionRepo.getCurrentAccountStatus().syncStatusMessage.contains("sinkronisasi belum aktif"))

        // 3. Go server comes online -> exchanges token AND explicitly merges guest progress before first sync
        fakeApi.authResult = Result.success(
            AuthSessionResponse(
                sessionToken = "jwt-session-99",
                playerId = "player-go-99",
                displayName = "Budi Connected",
                email = "budi@example.com",
                requiresGuestMerge = true
            )
        )
        sessionTokenHolder = "jwt-session-99"
        val signedInRes = sessionRepo.signInWithGoogle()
        assertTrue(signedInRes is AuthActionResult.SignedIn)
        assertTrue((signedInRes as AuthActionResult.SignedIn).guestMerged)
        assertTrue(fakeApi.lastEventsRequest?.isGuestMerge == true)
        assertFalse(sessionRepo.getCurrentAccountStatus().isGuest)

        // 4. FCM token registration & logout disassociation + FCM refresh signal routing
        fcmAdapter.registerOrUpdateToken("fcm-token-abc", linkedPlayerId = "player-go-99")
        assertEquals("player-go-99", fakeApi.lastPushTokenRequest?.linkedPlayerId)

        val refreshState = fcmAdapter.handleIncomingPushData(
            mapOf("type" to "config_refresh", "gameId" to game.metadata.gameId, "configVersion" to "7"),
            currentTimeEpochMs = 50_000L
        )
        assertNotNull(refreshState)

        sessionRepo.signOut()
        assertNull(fakeApi.lastPushTokenRequest?.linkedPlayerId)
        assertTrue(sessionRepo.getCurrentAccountStatus().isGuest)
    }

    @Test
    fun adMobLifecycleMapperMapsAllStatesCorrectly() {
        assertTrue(
            AdMobRewardedAdAdapter.mapLifecycleToRawAdResult(
                rewardEarned = true,
                loadError = null,
                showError = null,
                isActivityAvailable = true,
                claimId = "c1"
            ) is RawAdShowResult.EarnedReward
        )
        assertTrue(
            AdMobRewardedAdAdapter.mapLifecycleToRawAdResult(
                rewardEarned = false,
                loadError = null,
                showError = null,
                isActivityAvailable = true,
                claimId = "c2"
            ) is RawAdShowResult.DismissedBeforeReward
        )
        assertTrue(
            AdMobRewardedAdAdapter.mapLifecycleToRawAdResult(
                rewardEarned = false,
                loadError = "No fill",
                showError = null,
                isActivityAvailable = true,
                claimId = "c3"
            ) is RawAdShowResult.FailedToLoadOrShow
        )
        assertTrue(
            AdMobRewardedAdAdapter.mapLifecycleToRawAdResult(
                rewardEarned = false,
                loadError = null,
                showError = null,
                isActivityAvailable = false,
                claimId = "c4"
            ) is RawAdShowResult.NotAvailable
        )
    }

    // --- Group 6 & 7 Tests: ViewModels, Feature Flag Disabling, and Customization Isolation ---

    @Test
    fun viewModelsPlayThroughSampleLevelUnlockAchievementsAndRespectDisabledFeatureFlags() = runTest {
        val container = StarterAppContainer(
            context = context,
            ruleEngine = SamplePuzzleGame(),
            isDemoMode = true,
            databaseOverride = database,
            dataStoreOverride = dataStore
        )
        val gameplayVm = GameplayViewModel(container)
        val settingsVm = SettingsViewModel(container)
        val leaderboardVm = LeaderboardViewModel(container)

        gameplayVm.startLevelSuspend("level-1", "attempt-e2e-1")
        assertEquals("level-1", gameplayVm.uiState.value.levelId)
        assertTrue(gameplayVm.uiState.value.hintsEnabled)

        // Disable hints via simulated remote config refresh -> GameplayViewModel rejects hint action
        settingsVm.triggerSimulatedConfigChangeSuspend(
            hintsEnabled = false,
            livesEnabled = true,
            rewardedAdsEnabled = true
        )
        gameplayVm.onRequestHintSuspend("hint-should-be-blocked")
        assertEquals(2, container.progressRepository.getBalance().hints)

        // Complete level-1 without hints ("K", "O", "T", "L", "I", "N")
        for (letter in listOf("K", "O", "T", "L", "I", "N")) {
            gameplayVm.onMoveSuspend(SamplePuzzleMove.PlaceTile(letter))
        }
        val summary = gameplayVm.uiState.value.latestResult
        assertNotNull(summary)
        assertTrue(summary!!.won)
        assertEquals(2, summary.newlyUnlockedAchievements.size)

        leaderboardVm.refreshSuspend()
        val lbState = leaderboardVm.state.value
        assertTrue(lbState is LeaderboardState.Loaded)
        assertTrue((lbState as LeaderboardState.Loaded).isDemo)
    }

    @Test
    fun developerCanSwapCustomThemeAndCustomGameRulesWithoutModifyingCoreOrAdapters() = runTest {
        // 1. Custom Theme Config & Typography builds cleanly
        val customTheme = StarterThemeConfig(appName = "Kuis Pintar Nusantara")
        val typography = buildStarterTypography(customTheme.fontFamily)
        assertEquals(customTheme.fontFamily, typography.headlineMedium.fontFamily)

        // 2. Custom Trivia Game rule engine plugs into StarterAppContainer and GameplayViewModel with zero changes to core/auth/ads/settings
        val customTriviaEngine = object : LevelRuleEngine<String, String, String> {
            override val metadata = GameMetadataAndFeatures(
                gameId = "custom-trivia-v1",
                title = "Kuis Pintar Nusantara",
                hintsEnabled = true,
                livesEnabled = true
            )
            override val levels = listOf("ibu-kota-indonesia")
            override val achievements: List<AchievementDefinition> = AchievementEvaluator.defaultDemoAchievements()
            override fun getLevelId(level: String): String = level
            override fun createInitialState(level: String, attemptId: String): String = ""
            override fun getAttemptId(state: String): String = "trivia-attempt-1"
            override fun getHintsUsedCount(state: String): Int = 0
            override fun applyMove(state: String, move: String): String = move
            override fun applyHint(state: String): HintApplicationResult<String> =
                HintApplicationResult.Applied("JAKARTA", "Hint: Dimulai dengan huruf J")
            override fun evaluateOutcome(state: String): LevelOutcome =
                if (state == "JAKARTA") LevelOutcome.Won(1500) else LevelOutcome.InProgress
            override fun serializeCheckpoint(state: String): String = state
            override fun deserializeCheckpoint(level: String, json: String): String = json
        }

        val customContainer = StarterAppContainer(
            context = context,
            ruleEngine = customTriviaEngine,
            isDemoMode = true,
            databaseOverride = database,
            dataStoreOverride = dataStore
        )
        val customGameplayVm = GameplayViewModel(customContainer)
        customGameplayVm.startLevelSuspend("ibu-kota-indonesia", "trivia-attempt-1")
        customGameplayVm.onMoveSuspend("JAKARTA")

        val result = customGameplayVm.uiState.value.latestResult
        assertNotNull(result)
        assertTrue(result!!.won)
        assertEquals(1500, result.score)
        assertEquals(1500, customContainer.progressRepository.getLevels()["ibu-kota-indonesia"]?.bestScore)
    }
}

private class FakeGoBackendApiClient : GoBackendApiClient {
    var authResult: Result<AuthSessionResponse> = Result.failure(IllegalStateException("Not configured"))
    var configResult: Result<RemoteGameConfigDto> = Result.success(
        RemoteGameConfigDto(gameId = "sample-puzzle-v1", configVersion = 7, ttlSeconds = 3600)
    )
    var leaderboardResult: Result<LeaderboardResponseDto> = Result.success(
        LeaderboardResponseDto("sample-puzzle-v1", isDemo = false, entries = emptyList())
    )
    var rewardClaimResult: Result<RewardClaimResponseDto> = Result.success(
        RewardClaimResponseDto("claim-1", RewardClaimStatus.CONFIRMED)
    )
    var lastEventsRequest: ProgressEventsBatchRequest? = null
    var lastPushTokenRequest: PushTokenRegistrationRequest? = null

    override suspend fun exchangeGoogleToken(request: GoogleAuthExchangeRequest): Result<AuthSessionResponse> =
        authResult

    override suspend fun getGameConfig(gameId: String): Result<RemoteGameConfigDto> =
        configResult

    override suspend fun getProgressSnapshot(
        gameId: String,
        sessionToken: String
    ): Result<PlayerProgressSnapshotDto> = Result.success(
        PlayerProgressSnapshotDto(
            playerId = "player-go-99",
            serverRevision = 1L,
            balance = PlayerBalance(hints = 2, lives = 3),
            levels = emptyList(),
            achievements = emptyList()
        )
    )

    override suspend fun postProgressEvents(
        gameId: String,
        sessionToken: String,
        request: ProgressEventsBatchRequest
    ): Result<ProgressEventsBatchResponse> {
        lastEventsRequest = request
        return Result.success(
            ProgressEventsBatchResponse(
                serverRevision = request.baseServerRevision + 1,
                eventStatuses = request.events.map { EventStatusDto(it.eventId, "ACCEPTED") },
                mergedSnapshot = PlayerProgressSnapshotDto(
                    playerId = request.playerId,
                    serverRevision = request.baseServerRevision + 1,
                    balance = PlayerBalance(hints = 2, lives = 3),
                    levels = emptyList(),
                    achievements = emptyList()
                )
            )
        )
    }

    override suspend fun getLeaderboard(gameId: String): Result<LeaderboardResponseDto> =
        leaderboardResult

    override suspend fun submitScore(
        gameId: String,
        sessionToken: String?,
        request: ScoreSubmissionRequest
    ): Result<ScoreSubmissionResponse> = Result.success(
        ScoreSubmissionResponse(attemptId = request.attemptId, accepted = true, globalRank = 1)
    )

    override suspend fun createOrCheckRewardClaim(
        gameId: String,
        sessionToken: String?,
        request: RewardClaimRequestDto
    ): Result<RewardClaimResponseDto> = rewardClaimResult

    override suspend fun putPushToken(
        installationId: String,
        request: PushTokenRegistrationRequest
    ): Result<Unit> {
        lastPushTokenRequest = request
        return Result.success(Unit)
    }
}
