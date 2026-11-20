package com.binarydev.startergame

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.binarydev.startergame.core.sample.SamplePuzzleLevel
import com.binarydev.startergame.core.sample.SamplePuzzleMove
import com.binarydev.startergame.core.sample.SamplePuzzleState
import com.binarydev.startergame.di.StarterAppContainer
import com.binarydev.startergame.ui.navigation.StarterGameApp
import com.binarydev.startergame.ui.screens.SamplePuzzleBoardContent
import com.binarydev.startergame.ui.theme.StarterGameTheme
import com.binarydev.startergame.ui.theme.StarterThemeConfig
import com.google.android.gms.ads.MobileAds
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var appContainer: StarterAppContainer<SamplePuzzleLevel, SamplePuzzleState, SamplePuzzleMove>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContainer = StarterAppContainer.createDefaultSamplePuzzleContainer(applicationContext)
        appContainer.updateCurrentActivity(this)
        appContainer.simulatedAdAdapter.interactiveUiAttached = true
        runCatching {
            MobileAds.initialize(this) {
                appContainer.adMobAdapter.preloadAd()
            }
        }
        // Inisialisasi FCM token starter & auto-refresh remote config saat aplikasi dibuka / kembali aktif (ON_RESUME)
        appContainer.fcmAdapter.fetchAndRegisterFirebaseTokenIfAvailable()
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    lifecycleScope.launch {
                        appContainer.configRepository.refreshIfNeeded(System.currentTimeMillis())
                    }
                }
            }
        )

        setContent {
            StarterGameTheme(
                themeConfig = StarterThemeConfig(
                    appName = appContainer.ruleEngine.metadata.title
                )
            ) {
                StarterGameApp(
                    container = appContainer,
                    puzzleBoardContent = { state, onMove ->
                        SamplePuzzleBoardContent(state = state, onMove = onMove)
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        appContainer.updateCurrentActivity(null)
        super.onDestroy()
    }
}
