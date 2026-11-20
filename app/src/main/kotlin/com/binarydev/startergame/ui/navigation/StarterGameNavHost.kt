package com.binarydev.startergame.ui.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.binarydev.startergame.di.AppContainer
import com.binarydev.startergame.ui.screens.AchievementsScreen
import com.binarydev.startergame.ui.screens.GameplayScreen
import com.binarydev.startergame.ui.screens.HomeScreen
import com.binarydev.startergame.ui.screens.LeaderboardScreen
import com.binarydev.startergame.ui.screens.PersistentBottomAdMobBanner
import com.binarydev.startergame.ui.screens.ResultScreen
import com.binarydev.startergame.ui.screens.SettingsScreen
import com.binarydev.startergame.ui.screens.SimulatedRewardedVideoAdOverlay
import com.binarydev.startergame.ui.screens.SplashScreen
import com.binarydev.startergame.ui.viewmodel.AchievementsViewModel
import com.binarydev.startergame.ui.viewmodel.GameplayViewModel
import com.binarydev.startergame.ui.viewmodel.HomeUiState
import com.binarydev.startergame.ui.viewmodel.HomeViewModel
import com.binarydev.startergame.ui.viewmodel.LeaderboardViewModel
import com.binarydev.startergame.ui.viewmodel.SettingsViewModel

object StarterDestinations {
    const val SPLASH = "splash"
    const val HOME = "home"
    const val GAMEPLAY = "gameplay"
    const val RESULT = "result"
    const val LEADERBOARD = "leaderboard"
    const val ACHIEVEMENTS = "achievements"
    const val SETTINGS = "settings"
}

/**
 * Navigation host connecting all 6 screens with customization slots so a developer can replace
 * `puzzleBoardContent` or an entire screen (e.g. `customHomeScreen`) without touching core or ViewModels.
 */
@Composable
fun <LevelData, State, Move> StarterGameApp(
    container: AppContainer<LevelData, State, Move>,
    puzzleBoardContent: @Composable (State, (Move) -> Unit) -> Unit,
    customHomeScreen: (@Composable (
        uiState: HomeUiState,
        onPlayLevel: (String) -> Unit,
        onWatchRewardedAd: () -> Unit,
        onOpenLeaderboard: () -> Unit,
        onOpenAchievements: () -> Unit,
        onOpenSettings: () -> Unit
    ) -> Unit)? = null
) {
    val navController = rememberNavController()

    val homeViewModel = remember(container) { HomeViewModel(container) }
    val gameplayViewModel = remember(container) { GameplayViewModel(container) }
    val leaderboardViewModel = remember(container) { LeaderboardViewModel(container) }
    val achievementsViewModel = remember(container) { AchievementsViewModel(container) }
    val settingsViewModel = remember(container) { SettingsViewModel(container) }

    val homeState by homeViewModel.uiState.collectAsState()
    val gameplayState by gameplayViewModel.uiState.collectAsState()
    val leaderboardState by leaderboardViewModel.state.collectAsState()
    val leaderboardRawJson by leaderboardViewModel.lastRawJson.collectAsState()
    val leaderboardSimOffline by leaderboardViewModel.isSimulatingOffline.collectAsState()
    val achievementsState by achievementsViewModel.achievements.collectAsState()
    val achievementsUiState by achievementsViewModel.uiState.collectAsState()
    val settingsState by settingsViewModel.uiState.collectAsState()
    val activeAdSession by container.simulatedAdAdapter.activeSession.collectAsState()

    val currentAdSession = activeAdSession
    if (currentAdSession != null) {
        SimulatedRewardedVideoAdOverlay(session = currentAdSession)
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Column(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = StarterDestinations.SPLASH,
            modifier = Modifier.weight(1f)
        ) {
            composable(StarterDestinations.SPLASH) {
                SplashScreen(
                    gameTitle = homeState.gameTitle,
                    onSplashFinished = {
                        navController.navigate(StarterDestinations.HOME) {
                            popUpTo(StarterDestinations.SPLASH) { inclusive = true }
                        }
                    }
                )
            }

            composable(StarterDestinations.HOME) {
                val playAction: (String) -> Unit = { levelId ->
                    gameplayViewModel.startLevel(levelId)
                    navController.navigate(StarterDestinations.GAMEPLAY)
                }
                if (customHomeScreen != null) {
                    customHomeScreen(
                        homeState,
                        playAction,
                        { homeViewModel.watchRewardedAdForBonus() },
                        {
                            leaderboardViewModel.refresh()
                            navController.navigate(StarterDestinations.LEADERBOARD)
                        },
                        { navController.navigate(StarterDestinations.ACHIEVEMENTS) },
                        { navController.navigate(StarterDestinations.SETTINGS) }
                    )
                } else {
                    HomeScreen(
                        uiState = homeState,
                        onPlayLevel = playAction,
                        onWatchRewardedAd = { homeViewModel.watchRewardedAdForBonus() },
                        onOpenLeaderboard = {
                            leaderboardViewModel.refresh()
                            navController.navigate(StarterDestinations.LEADERBOARD)
                        },
                        onOpenAchievements = { navController.navigate(StarterDestinations.ACHIEVEMENTS) },
                        onOpenSettings = { navController.navigate(StarterDestinations.SETTINGS) }
                    )
                }
            }

            composable(StarterDestinations.GAMEPLAY) {
                GameplayScreen(
                    uiState = gameplayState,
                    onMove = { move -> gameplayViewModel.onMove(move) },
                    onRequestHint = { gameplayViewModel.onRequestHint() },
                    onRecoverLifeViaAd = { gameplayViewModel.recoverLivesViaRewardedAd() },
                    onNavigateToResult = { navController.navigate(StarterDestinations.RESULT) },
                    onBackToHome = { navController.popBackStack(StarterDestinations.HOME, inclusive = false) },
                    puzzleBoardContent = puzzleBoardContent
                )
            }

            composable(StarterDestinations.RESULT) {
                ResultScreen(
                    result = gameplayState.latestResult,
                    livesEnabled = gameplayState.livesEnabled,
                    remainingLives = gameplayState.balance.lives,
                    onReplayLevel = { levelId ->
                        gameplayViewModel.startLevel(levelId)
                        navController.popBackStack(StarterDestinations.GAMEPLAY, inclusive = false)
                    },
                    onPlayNextLevel = { nextLevelId ->
                        gameplayViewModel.startLevel(nextLevelId)
                        navController.popBackStack(StarterDestinations.GAMEPLAY, inclusive = false)
                    },
                    onRecoverLifeViaAd = { gameplayViewModel.recoverLivesViaRewardedAd() },
                    onBackToHome = { navController.popBackStack(StarterDestinations.HOME, inclusive = false) }
                )
            }

            composable(StarterDestinations.LEADERBOARD) {
                LeaderboardScreen(
                    state = leaderboardState,
                    rawJsonPayload = leaderboardRawJson,
                    isSimulatingOffline = leaderboardSimOffline,
                    onRetry = { leaderboardViewModel.refresh() },
                    onToggleSimulateOffline = { leaderboardViewModel.toggleSimulateBackendOffline() },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(StarterDestinations.ACHIEVEMENTS) {
                AchievementsScreen(
                    achievements = achievementsState,
                    uiState = achievementsUiState,
                    onRefreshFromApi = { achievementsViewModel.refreshFromApi() },
                    onToggleSimulateOffline = { achievementsViewModel.toggleSimulateBackendOffline() },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(StarterDestinations.SETTINGS) {
                SettingsScreen(
                    uiState = settingsState,
                    onToggleMusic = { settingsViewModel.toggleMusic(it) },
                    onToggleSoundEffects = { settingsViewModel.toggleSoundEffects(it) },
                    onToggleVibration = { settingsViewModel.toggleVibration(it) },
                    onSignInGoogle = { settingsViewModel.signInWithGoogle() },
                    onSignOut = { settingsViewModel.signOut() },
                    onTriggerSimulatedConfig = { hints, lives, ads, failApi ->
                        settingsViewModel.triggerSimulatedConfigChange(hints, lives, ads, failApi)
                    },
                    onTriggerFullBackendAdControl = { hints, lives, banner, rewarded, interstitial, failApi ->
                        settingsViewModel.triggerSimulatedConfigChange(
                            hintsEnabled = hints,
                            livesEnabled = lives,
                            rewardedAdsEnabled = rewarded,
                            simulateApiFailure = failApi,
                            bannerAdsEnabled = banner,
                            interstitialAdsEnabled = interstitial
                        )
                    },
                    onSetSimulatedAdMode = { settingsViewModel.setSimulatedAdMode(it) },
                    onToggleRealAdMobTestAds = { settingsViewModel.setUseRealAdMobTestAds(it) },
                    onSubscribeRemoveAdsGooglePlay = { settingsViewModel.subscribeRemoveAdsGooglePlay() },
                    onRestoreRemoveAdsSubscription = { settingsViewModel.restoreRemoveAdsSubscription() },
                    onToggleSimulateSandboxSubscription = { settingsViewModel.toggleSimulateSandboxSubscription() },
                    onBack = { navController.popBackStack() }
                )
            }
        }

        // Iklan banner bawah AdMob yang bereaksi real-time terhadap konfigurasi Backend / sinyal FCM
        // DAN langsung hilang saat pemain berlangganan Remove Ads (Google Play Billing) atau saat di layar Splash
        val shouldShowBottomBanner = currentRoute != StarterDestinations.SPLASH &&
            settingsState.configState.config.bannerAd.enabled &&
            !settingsState.subscriptionEntitlement.isAdFreeSubscribed
        AnimatedVisibility(visible = shouldShowBottomBanner) {
            PersistentBottomAdMobBanner()
        }
    }
}
