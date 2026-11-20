package com.binarydev.startergame.di

import android.app.Activity
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.binarydev.startergame.BuildConfig
import com.binarydev.startergame.core.contracts.AdService
import com.binarydev.startergame.core.contracts.ConfigRepository
import com.binarydev.startergame.core.contracts.DevicePreferencesRepository
import com.binarydev.startergame.core.contracts.LeaderboardRepository
import com.binarydev.startergame.core.contracts.RewardClaimRepository
import com.binarydev.startergame.core.contracts.SessionRepository
import com.binarydev.startergame.core.contracts.SubscriptionBillingRepository
import com.binarydev.startergame.core.engine.GameSessionCoordinator
import com.binarydev.startergame.core.engine.LevelRuleEngine
import com.binarydev.startergame.core.sample.SamplePuzzleGame
import com.binarydev.startergame.core.sample.SamplePuzzleLevel
import com.binarydev.startergame.core.sample.SamplePuzzleMove
import com.binarydev.startergame.core.sample.SamplePuzzleState
import com.binarydev.startergame.data.integration.AdMobRewardedAdAdapter
import com.binarydev.startergame.data.integration.CredentialManagerAuthAdapter
import com.binarydev.startergame.data.integration.FcmPushTokenAndRefreshAdapter
import com.binarydev.startergame.data.integration.GooglePlayBillingSubscriptionAdapter
import com.binarydev.startergame.data.integration.SessionRepositoryImpl
import com.binarydev.startergame.data.integration.SimulatedGoogleIdTokenProvider
import com.binarydev.startergame.data.integration.StarterFirebaseMessagingService
import com.binarydev.startergame.data.local.StarterGameDatabase
import com.binarydev.startergame.data.preferences.DataStoreDevicePreferencesRepository
import com.binarydev.startergame.data.remote.GoBackendApiClient
import com.binarydev.startergame.data.remote.HttpUrlConnectionGoBackendApiClient
import com.binarydev.startergame.data.remote.MockGoBackendApiClient
import com.binarydev.startergame.data.repository.ConfigRepositoryImpl
import com.binarydev.startergame.data.repository.ConnectedLeaderboardRepository
import com.binarydev.startergame.data.repository.RewardClaimService
import com.binarydev.startergame.data.repository.RoomProgressRepository
import com.binarydev.startergame.data.repository.SimulatedAdAdapter
import com.binarydev.startergame.data.repository.SimulatedLeaderboardAdapter
import com.binarydev.startergame.data.repository.SimulatedRemoteConfigAdapter
import com.binarydev.startergame.data.repository.SwitchableAdService
import java.lang.ref.WeakReference

interface AppContainer<LevelData, State, Move> {
    val isDemoMode: Boolean
    val ruleEngine: LevelRuleEngine<LevelData, State, Move>
    val coordinator: GameSessionCoordinator<LevelData, State, Move>
    val progressRepository: RoomProgressRepository<LevelData, State, Move>
    val devicePreferencesRepository: DevicePreferencesRepository
    val configRepository: ConfigRepository
    val sessionRepository: SessionRepository
    val leaderboardRepository: LeaderboardRepository
    val adService: AdService
    val switchableAdService: SwitchableAdService
    val rewardClaimRepository: RewardClaimRepository
    val subscriptionBillingRepository: SubscriptionBillingRepository
    val fcmAdapter: FcmPushTokenAndRefreshAdapter
    val simulatedRemoteConfigAdapter: SimulatedRemoteConfigAdapter?
    val simulatedAdAdapter: SimulatedAdAdapter
    val credentialManagerAuthAdapter: CredentialManagerAuthAdapter
    val mockBackendApiClient: MockGoBackendApiClient
    val activeBackendApiClient: GoBackendApiClient

    fun updateCurrentActivity(activity: Activity?)
}

/**
 * Flavored/configurable DI container binding either:
 * - `DemoAppContainer` (`isDemoMode = true`): offline local Room + DataStore + simulated Google/Leaderboard/Ad/RemoteConfig adapters.
 * - `IntegratedAppContainer` (`isDemoMode = false`): Room + DataStore + Credential Manager + AdMob test ads + FCM + Go Backend v1 HTTP client.
 */
class StarterAppContainer<LevelData, State, Move>(
    context: Context,
    override val ruleEngine: LevelRuleEngine<LevelData, State, Move>,
    override val isDemoMode: Boolean = BuildConfig.STARTER_MODE == "demo",
    databaseOverride: StarterGameDatabase? = null,
    dataStoreOverride: DataStore<Preferences>? = null,
    backendApiOverride: GoBackendApiClient? = null
) : AppContainer<LevelData, State, Move> {

    private var currentActivityRef: WeakReference<Activity>? = null

    override fun updateCurrentActivity(activity: Activity?) {
        currentActivityRef = if (activity != null) WeakReference(activity) else null
    }

    val database: StarterGameDatabase = databaseOverride ?: Room.databaseBuilder(
        context.applicationContext,
        StarterGameDatabase::class.java,
        "starter_game.db"
    ).fallbackToDestructiveMigration().build()

    private val dataStore: DataStore<Preferences> = dataStoreOverride ?: PreferenceDataStoreFactory.create(
        produceFile = { context.applicationContext.preferencesDataStoreFile("starter_game_prefs") }
    )

    override val coordinator: GameSessionCoordinator<LevelData, State, Move> =
        GameSessionCoordinator(ruleEngine)

    private val prefsRepo = DataStoreDevicePreferencesRepository(dataStore)
    override val devicePreferencesRepository: DevicePreferencesRepository = prefsRepo

    private val backendApiClient: GoBackendApiClient? = when {
        backendApiOverride != null -> backendApiOverride
        isDemoMode -> null
        else -> HttpUrlConnectionGoBackendApiClient(
            baseUrl = BuildConfig.BACKEND_BASE_URL,
            apiKey = BuildConfig.BACKEND_API_KEY,
            timeoutMs = BuildConfig.BACKEND_TIMEOUT_MS
        )
    }

    private var sessionRepoRef: SessionRepositoryImpl? = null

    override val progressRepository: RoomProgressRepository<LevelData, State, Move> =
        RoomProgressRepository(
            dao = database.starterGameDao(),
            ruleEngine = ruleEngine,
            backendApiClient = backendApiClient ?: MockGoBackendApiClient(
                gameId = ruleEngine.metadata.gameId,
                progressRepositoryProvider = { progressRepository }
            ),
            sessionTokenProvider = { sessionRepoRef?.currentSessionToken }
        )

    override val simulatedRemoteConfigAdapter: SimulatedRemoteConfigAdapter? =
        if (isDemoMode) SimulatedRemoteConfigAdapter(ruleEngine.metadata) else null

    override val configRepository: ConfigRepository = ConfigRepositoryImpl(
        metadata = ruleEngine.metadata,
        cacheStore = prefsRepo,
        backendApiClient = backendApiClient,
        simulatedAdapter = simulatedRemoteConfigAdapter
    )

    override val fcmAdapter: FcmPushTokenAndRefreshAdapter = FcmPushTokenAndRefreshAdapter(
        defaultGameId = ruleEngine.metadata.gameId,
        preferencesRepository = prefsRepo,
        configRepository = configRepository,
        backendApiClient = backendApiClient
    ).also { adapter ->
        StarterFirebaseMessagingService.activeAdapterProvider = { adapter }
    }

    override val credentialManagerAuthAdapter: CredentialManagerAuthAdapter =
        CredentialManagerAuthAdapter(
            contextProvider = { currentActivityRef?.get() },
            webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID
        )

    override val mockBackendApiClient: MockGoBackendApiClient by lazy {
        MockGoBackendApiClient(
            gameId = ruleEngine.metadata.gameId,
            progressRepositoryProvider = { progressRepository },
            simulatedLatencyMs = 180L
        )
    }

    override val activeBackendApiClient: GoBackendApiClient by lazy {
        backendApiClient ?: mockBackendApiClient
    }

    override val sessionRepository: SessionRepository = SessionRepositoryImpl(
        dao = database.starterGameDao(),
        progressRepository = progressRepository,
        preferencesRepository = prefsRepo,
        googleIdTokenProvider = credentialManagerAuthAdapter,
        backendApiClient = activeBackendApiClient,
        pushTokenRepository = fcmAdapter
    ).also { sessionRepoRef = it }

    override val leaderboardRepository: LeaderboardRepository = if (isDemoMode || backendApiClient == null) {
        SimulatedLeaderboardAdapter(
            progressRepository = progressRepository,
            mockBackendApiClient = mockBackendApiClient,
            gameId = ruleEngine.metadata.gameId
        )
    } else {
        ConnectedLeaderboardRepository(
            gameId = ruleEngine.metadata.gameId,
            apiClient = backendApiClient,
            sessionTokenProvider = { sessionRepoRef?.currentSessionToken }
        )
    }

    override val simulatedAdAdapter: SimulatedAdAdapter = SimulatedAdAdapter()

    val adMobAdapter = AdMobRewardedAdAdapter(
        context = context.applicationContext,
        activityProvider = { currentActivityRef?.get() },
        rewardedAdUnitId = BuildConfig.ADMOB_REWARDED_AD_UNIT_ID
    )

    override val switchableAdService: SwitchableAdService = SwitchableAdService(
        simulatedAdAdapter = simulatedAdAdapter,
        adMobAdAdapter = adMobAdapter,
        useRealAdMobTestAds = true
    )

    override val adService: AdService = switchableAdService

    override val rewardClaimRepository: RewardClaimRepository = RewardClaimService(
        gameId = ruleEngine.metadata.gameId,
        adService = switchableAdService,
        progressRepository = progressRepository,
        backendApiClient = backendApiClient,
        useServerVerification = !isDemoMode && BuildConfig.USE_SERVER_REWARD_VERIFICATION,
        sessionTokenProvider = { sessionRepoRef?.currentSessionToken }
    )

    override val subscriptionBillingRepository: SubscriptionBillingRepository =
        GooglePlayBillingSubscriptionAdapter(
            context = context.applicationContext,
            activityProvider = { currentActivityRef?.get() },
            gameId = ruleEngine.metadata.gameId,
            subscriptionProductId = BuildConfig.BILLING_REMOVE_ADS_SUB_PRODUCT_ID,
            basePlanId = BuildConfig.BILLING_REMOVE_ADS_BASE_PLAN_ID,
            preferencesRepository = prefsRepo,
            progressRepositoryProvider = { progressRepository },
            backendApiClientProvider = { activeBackendApiClient },
            sessionTokenProvider = { sessionRepoRef?.currentSessionToken }
        )

    companion object {
        fun createDefaultSamplePuzzleContainer(
            context: Context,
            isDemoMode: Boolean = BuildConfig.STARTER_MODE == "demo"
        ): StarterAppContainer<SamplePuzzleLevel, SamplePuzzleState, SamplePuzzleMove> {
            val defaultEngine = SamplePuzzleGame()
            val envConfiguredMetadata = defaultEngine.metadata.copy(
                gameId = BuildConfig.GAME_ID.ifBlank { defaultEngine.metadata.gameId },
                privacyPolicyUrl = BuildConfig.PRIVACY_POLICY_URL.ifBlank { defaultEngine.metadata.privacyPolicyUrl }
            )
            return StarterAppContainer(
                context = context,
                ruleEngine = SamplePuzzleGame(metadata = envConfiguredMetadata),
                isDemoMode = isDemoMode
            )
        }
    }
}
