package com.binarydev.startergame.ui.screens

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.binarydev.startergame.BuildConfig
import com.binarydev.startergame.R
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.LoadAdError
import com.binarydev.startergame.core.achievement.AchievementState
import com.binarydev.startergame.core.contracts.LeaderboardState
import com.binarydev.startergame.core.sample.SamplePuzzleMove
import com.binarydev.startergame.core.sample.SamplePuzzleState
import com.binarydev.startergame.data.repository.InteractiveAdSession
import com.binarydev.startergame.data.repository.SimulatedAdMode
import com.binarydev.startergame.ui.viewmodel.AchievementsUiState
import com.binarydev.startergame.ui.viewmodel.GameplayUiState
import com.binarydev.startergame.ui.viewmodel.HomeUiState
import com.binarydev.startergame.ui.viewmodel.LevelResultSummary
import com.binarydev.startergame.ui.viewmodel.SettingsUiState
import kotlinx.coroutines.delay

// --- 0. Splash Screen (Uses replaceable @drawable/ic_launcher_foreground & @string/app_name) ---

@Composable
fun SplashScreen(
    gameTitle: String,
    durationMs: Long = 1_400L,
    onSplashFinished: () -> Unit
) {
    var startAnimation by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0.78f,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "splash_scale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = tween(durationMillis = 500),
        label = "splash_alpha"
    )

    LaunchedEffect(Unit) {
        startAnimation = true
        delay(durationMs)
        onSplashFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(24.dp)
            .testTag("splash_screen"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .scale(scale)
                .alpha(alpha)
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 12.dp,
                modifier = Modifier.size(116.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = gameTitle,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = gameTitle.ifBlank { stringResource(id = R.string.app_name) },
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Text(
                text = stringResource(id = R.string.splash_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(28.dp)
            )
        }

        Text(
            text = stringResource(id = R.string.splash_footer),
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF64748B),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }
}

// --- 1. Home Screen ---

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onPlayLevel: (String) -> Unit,
    onWatchRewardedAd: () -> Unit,
    onOpenLeaderboard: () -> Unit,
    onOpenAchievements: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = uiState.gameTitle,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = if (uiState.isDemoMode) "Mode Demo · Offline & Simulasi" else "Mode Terhubung",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Resource bar: hides hint or life pill automatically when disabled in game config
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (uiState.livesEnabled) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Nyawa: ${uiState.balance.lives}") },
                        modifier = Modifier.testTag("home_lives_chip")
                    )
                }
                if (uiState.hintsEnabled) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Hint: ${uiState.balance.hints}") },
                        modifier = Modifier.testTag("home_hints_chip")
                    )
                }
                if (uiState.isAdFreeSubscribed) {
                    AssistChip(
                        onClick = {},
                        label = { Text("★ Bebas Iklan") },
                        modifier = Modifier.testTag("home_ad_free_chip")
                    )
                }
            }

            if (!uiState.statusBanner.isNullOrBlank()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = uiState.statusBanner,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Continue checkpoint button if a mid-level checkpoint exists
            if (uiState.activeCheckpointLevelId != null) {
                Button(
                    onClick = { onPlayLevel(uiState.activeCheckpointLevelId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("continue_checkpoint_button")
                ) {
                    Text("Lanjut Main (${uiState.activeCheckpointLevelId})")
                }
            }

            Text(
                text = "Pilih Level",
                style = MaterialTheme.typography.titleLarge
            )

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(uiState.levels, key = { it.levelId }) { level ->
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = level.levelId.uppercase(),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = buildString {
                                        append("Skor Terbaik: ${level.bestScore}")
                                        if (level.completed) append(" · Selesai")
                                        if (level.hasCheckpoint) append(" · Checkpoint Tersimpan")
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Button(
                                onClick = { onPlayLevel(level.levelId) },
                                enabled = level.unlocked,
                                modifier = Modifier.testTag("play_${level.levelId}")
                            ) {
                                Text(
                                    when {
                                        !level.unlocked -> "Terkunci"
                                        level.hasCheckpoint -> "Lanjut"
                                        else -> "Mulai"
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (uiState.rewardedAdEnabled) {
                OutlinedButton(
                    onClick = onWatchRewardedAd,
                    enabled = !uiState.isLoadingAd,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("watch_rewarded_ad_button")
                ) {
                    Text(
                        if (uiState.isLoadingAd) {
                            "Memuat Iklan AdMob..."
                        } else {
                            "Tonton Rewarded Ad (+Hint / +Nyawa)"
                        }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onOpenLeaderboard,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Leaderboard", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = onOpenAchievements,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Pencapaian", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = onOpenSettings,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Pengaturan", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// --- 2. Gameplay Screen with pluggable PuzzleBoardContent slot ---

@Composable
fun <State, Move> GameplayScreen(
    uiState: GameplayUiState<State>,
    onMove: (Move) -> Unit,
    onRequestHint: () -> Unit,
    onRecoverLifeViaAd: () -> Unit,
    onNavigateToResult: () -> Unit,
    onBackToHome: () -> Unit,
    puzzleBoardContent: @Composable (State, (Move) -> Unit) -> Unit
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onBackToHome) {
                    Text("Kembali")
                }
                Text(
                    text = uiState.levelId.uppercase(),
                    style = MaterialTheme.typography.titleLarge
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (uiState.livesEnabled) {
                        AssistChip(
                            onClick = {},
                            label = { Text("Nyawa: ${uiState.balance.lives}") },
                            modifier = Modifier.testTag("gameplay_lives_indicator")
                        )
                    }
                    if (uiState.hintsEnabled) {
                        AssistChip(
                            onClick = {},
                            label = { Text("Hint: ${uiState.balance.hints}") },
                            modifier = Modifier.testTag("gameplay_hints_indicator")
                        )
                    }
                }
            }

            if (!uiState.statusMessage.isNullOrBlank()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = uiState.statusMessage,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            if (uiState.outOfLives) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("out_of_lives_card")
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Nyawa Habis",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text("Dapatkan nyawa tambahan melalui salah satu cara yang tersedia:")
                        if (uiState.rewardedAdEnabled) {
                            Button(
                                onClick = onRecoverLifeViaAd,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("recover_life_ad_button")
                            ) {
                                Text("Tonton Rewarded Ad (+1 Nyawa)")
                            }
                        }
                    }
                }
            }

            val currentGameState = uiState.gameState
            if (currentGameState != null) {
                Column(modifier = Modifier.weight(1f)) {
                    puzzleBoardContent(currentGameState, onMove)
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
            }

            // Hint button is ONLY rendered when hintsEnabled == true in config
            if (uiState.hintsEnabled && uiState.gameState != null && uiState.latestResult == null) {
                Button(
                    onClick = onRequestHint,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("use_hint_button")
                ) {
                    Text("Gunakan Hint (${uiState.balance.hints} tersisa)")
                }
            }

            if (uiState.latestResult != null) {
                Button(
                    onClick = onNavigateToResult,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("view_result_button")
                ) {
                    Text("Lihat Hasil Level")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SamplePuzzleBoardContent(
    state: SamplePuzzleState,
    onMove: (SamplePuzzleMove) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Petunjuk:",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = state.clue,
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "Kesalahan: ${state.mistakes} / ${state.maxMistakes} · Hint dipakai: ${state.hintsUsed}",
                style = MaterialTheme.typography.bodyMedium
            )

            HorizontalDivider()

            // Target slots
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.targetSequence.forEachIndexed { index, _ ->
                    val placedLetter = state.placedSequence.getOrNull(index) ?: "_"
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Text(
                            text = placedLetter,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (!state.lastFeedback.isNullOrBlank()) {
                Text(
                    text = state.lastFeedback.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = "Pilih Huruf Berikutnya:",
                style = MaterialTheme.typography.titleMedium
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                state.availableChoices.forEachIndexed { idx, letter ->
                    Button(
                        onClick = { onMove(SamplePuzzleMove.PlaceTile(letter)) },
                        enabled = !state.isComplete && !state.isFailed,
                        modifier = Modifier.testTag("tile_${idx}_$letter")
                    ) {
                        Text(letter)
                    }
                }
            }

            OutlinedButton(
                onClick = { onMove(SamplePuzzleMove.ClearPlaced) },
                enabled = state.placedSequence.isNotEmpty() && !state.isComplete && !state.isFailed
            ) {
                Text("Reset Susunan")
            }
        }
    }
}

// --- 3. Result Screen ---

@Composable
fun ResultScreen(
    result: LevelResultSummary?,
    livesEnabled: Boolean,
    remainingLives: Int,
    onReplayLevel: (String) -> Unit,
    onPlayNextLevel: (String) -> Unit,
    onRecoverLifeViaAd: () -> Unit,
    onBackToHome: () -> Unit
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (result == null) {
                Text("Belum ada hasil permainan.")
                Button(onClick = onBackToHome) {
                    Text("Kembali ke Home")
                }
                return@Column
            }

            Text(
                text = if (result.won) "Level Selesai!" else "Percobaan Gagal",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Level: ${result.levelId}")
                    Text("Skor Percobaan: ${result.score}")
                    Text("Skor Terbaik: ${result.bestScore}")
                    Text(result.reason)
                    if (result.won && !result.rewardGranted.isEmpty) {
                        Text(
                            text = "Hadiah Level: +${result.rewardGranted.hints} Hint, +${result.rewardGranted.lives} Nyawa",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (result.newlyUnlockedAchievements.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Pencapaian Baru Terbuka!",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        result.newlyUnlockedAchievements.forEach { ach ->
                            Text("• ${ach.title} (+${ach.reward.hints} Hint, +${ach.reward.lives} Nyawa)")
                        }
                    }
                }
            }

            if (!result.won && livesEnabled && remainingLives <= 0) {
                Button(
                    onClick = onRecoverLifeViaAd,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Nyawa Habis · Tonton Iklan untuk +1 Nyawa")
                }
            }

            if (result.nextLevelUnlockedId != null) {
                Button(
                    onClick = { onPlayNextLevel(result.nextLevelUnlockedId) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Lanjut ke ${result.nextLevelUnlockedId.uppercase()}")
                }
            }

            OutlinedButton(
                onClick = { onReplayLevel(result.levelId) },
                enabled = !livesEnabled || remainingLives > 0,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Main Lagi")
            }

            OutlinedButton(
                onClick = onBackToHome,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Kembali ke Home")
            }
        }
    }
}

// --- 4. Leaderboard Screen (Simulated from Mock Go Backend API) ---

@Composable
fun LeaderboardScreen(
    state: LeaderboardState,
    rawJsonPayload: String = "",
    isSimulatingOffline: Boolean = false,
    onRetry: () -> Unit,
    onToggleSimulateOffline: () -> Unit = {},
    onBack: () -> Unit
) {
    var showRawJson by remember { androidx.compose.runtime.mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onBack) {
                    Text("Kembali")
                }
                Text(
                    text = "Leaderboard",
                    style = MaterialTheme.typography.headlineMedium
                )
                val isDemo = (state as? LeaderboardState.Loaded)?.isDemo == true ||
                    (state as? LeaderboardState.Empty)?.isDemo == true
                if (isDemo) {
                    AssistChip(
                        onClick = {},
                        label = { Text("Demo · Mock BE") },
                        modifier = Modifier.testTag("leaderboard_demo_badge")
                    )
                }
            }

            // Mock Backend API Controls (Refresh, Toggle 200 OK vs 503 Offline, Inspect JSON)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onRetry,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("GET /leaderboard", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = onToggleSimulateOffline,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (isSimulatingOffline) "Simulasi: 503 Error" else "Simulasi: 200 OK",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (rawJsonPayload.isNotBlank()) {
                    OutlinedButton(
                        onClick = { showRawJson = !showRawJson },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(if (showRawJson) "Tutup JSON" else "JSON")
                    }
                }
            }

            if (showRawJson && rawJsonPayload.isNotBlank()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF0F172A)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "HTTP 200 OK · Payload JSON Mock Backend Go:",
                            color = Color(0xFF38BDF8),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = rawJsonPayload,
                            color = Color(0xFFE2E8F0),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            when (state) {
                is LeaderboardState.Loading -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(modifier = Modifier.testTag("leaderboard_loading"))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Memuat data ranking dari API Backend...")
                    }
                }
                is LeaderboardState.Empty -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = state.message,
                                modifier = Modifier.testTag("leaderboard_empty_message")
                            )
                            OutlinedButton(onClick = onRetry) {
                                Text("Muat Ulang")
                            }
                        }
                    }
                }
                is LeaderboardState.Unavailable -> {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = state.message,
                                modifier = Modifier.testTag("leaderboard_unavailable_message")
                            )
                            Button(onClick = onRetry) {
                                Text("Coba Lagi")
                            }
                        }
                    }
                }
                is LeaderboardState.Loaded -> {
                    if (!state.bannerMessage.isNullOrBlank()) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = state.bannerMessage.orEmpty(),
                                modifier = Modifier.padding(10.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.entries, key = { "${it.rank}-${it.playerId}" }) { entry ->
                            Card(
                                colors = if (entry.isCurrentPlayer) {
                                    CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                } else {
                                    CardDefaults.cardColors()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "#${entry.rank}  ${entry.displayName}",
                                        fontWeight = if (entry.isCurrentPlayer) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        text = "${entry.score} pts",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- 5. Achievements Screen (Simulated from Mock Go Backend API) ---

@Composable
fun AchievementsScreen(
    achievements: List<AchievementState>,
    uiState: AchievementsUiState? = null,
    onRefreshFromApi: () -> Unit = {},
    onToggleSimulateOffline: () -> Unit = {},
    onBack: () -> Unit
) {
    var showRawJson by remember { androidx.compose.runtime.mutableStateOf(false) }
    val displayItems = uiState?.achievements ?: achievements

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onBack) {
                    Text("Kembali")
                }
                Text(
                    text = "Pencapaian",
                    style = MaterialTheme.typography.headlineMedium
                )
                AssistChip(
                    onClick = {},
                    label = { Text(if (uiState?.isMockBackend != false) "Mock BE API" else "Live BE") }
                )
            }

            if (uiState != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = uiState.statusBanner,
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onRefreshFromApi,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("GET /achievements", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = onToggleSimulateOffline,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            if (uiState.isSimulatingOffline) "Simulasi: 503 Error" else "Simulasi: 200 OK",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (uiState.lastRawJson.isNotBlank()) {
                        OutlinedButton(
                            onClick = { showRawJson = !showRawJson },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(if (showRawJson) "Tutup JSON" else "JSON")
                        }
                    }
                }

                if (showRawJson && uiState.lastRawJson.isNotBlank()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF0F172A)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "HTTP 200 OK · Payload JSON Mock Backend Go:",
                                color = Color(0xFF38BDF8),
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = uiState.lastRawJson,
                                color = Color(0xFFE2E8F0),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(displayItems, key = { it.id }) { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (item.unlocked) "Terbuka ✓" else "Terkunci",
                                    color = if (item.unlocked) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Text(
                                text = item.description,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            val progressFraction = if (item.targetValue > 0) {
                                (item.currentProgress.toFloat() / item.targetValue.toFloat()).coerceIn(0f, 1f)
                            } else 0f
                            LinearProgressIndicator(
                                progress = { progressFraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "Progres: ${item.currentProgress} / ${item.targetValue} · Hadiah: +${item.reward.hints} Hint, +${item.reward.lives} Nyawa",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

// --- 6. Settings Screen ---

@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onToggleMusic: (Boolean) -> Unit,
    onToggleSoundEffects: (Boolean) -> Unit,
    onToggleVibration: (Boolean) -> Unit,
    onSignInGoogle: () -> Unit,
    onSignOut: () -> Unit,
    onTriggerSimulatedConfig: (hints: Boolean, lives: Boolean, ads: Boolean, failApi: Boolean) -> Unit,
    onTriggerFullBackendAdControl: (
        hints: Boolean,
        lives: Boolean,
        banner: Boolean,
        rewarded: Boolean,
        interstitial: Boolean,
        failApi: Boolean
    ) -> Unit = { h, l, _, r, _, f -> onTriggerSimulatedConfig(h, l, r, f) },
    onSetSimulatedAdMode: (SimulatedAdMode) -> Unit,
    onToggleRealAdMobTestAds: (Boolean) -> Unit = {},
    onSubscribeRemoveAdsGooglePlay: () -> Unit = {},
    onRestoreRemoveAdsSubscription: () -> Unit = {},
    onToggleSimulateSandboxSubscription: () -> Unit = {},
    onBack: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    var showConfigJson by remember { androidx.compose.runtime.mutableStateOf(false) }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onBack) {
                        Text("Kembali")
                    }
                    Text(
                        text = "Pengaturan",
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                }
            }

            if (!uiState.feedbackMessage.isNullOrBlank()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = uiState.feedbackMessage,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }

            // Preferences section (DataStore)
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Preferensi Perangkat",
                            style = MaterialTheme.typography.titleLarge
                        )
                        PreferenceToggleRow(
                            label = "Musik Latar",
                            checked = uiState.preferences.musicEnabled,
                            onCheckedChange = onToggleMusic,
                            tag = "toggle_music"
                        )
                        PreferenceToggleRow(
                            label = "Efek Suara (SFX)",
                            checked = uiState.preferences.soundEffectsEnabled,
                            onCheckedChange = onToggleSoundEffects,
                            tag = "toggle_sfx"
                        )
                        PreferenceToggleRow(
                            label = "Getaran",
                            checked = uiState.preferences.vibrationEnabled,
                            onCheckedChange = onToggleVibration,
                            tag = "toggle_vibration"
                        )
                    }
                }
            }

            // Account & Google Sign-In section
            item {
                val acc = uiState.accountStatus
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Status Akun & Login Google",
                                style = MaterialTheme.typography.titleLarge
                            )
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(if (acc.isGuest) "Guest Lokal" else "Google Terhubung ✓")
                                }
                            )
                        }
                        Text(
                            text = "Nama: ${acc.displayName}" +
                                (if (!acc.email.isNullOrBlank()) " (${acc.email})" else ""),
                            fontWeight = FontWeight.SemiBold
                        )
                        if (!acc.linkedPlayerId.isNullOrBlank()) {
                            Text(
                                text = "Player ID Backend: ${acc.linkedPlayerId}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Text(
                            text = acc.syncStatusMessage,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onSignInGoogle,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("google_sign_in_button")
                            ) {
                                Text(
                                    text = if (acc.isGuest) "Masuk dengan Google" else "Ganti Akun Google",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (!acc.isGuest || !acc.email.isNullOrBlank()) {
                                OutlinedButton(
                                    onClick = onSignOut,
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .height(44.dp)
                                        .testTag("google_sign_out_button")
                                ) {
                                    Text("Keluar")
                                }
                            }
                        }
                    }
                }
            }

            // Google Play Billing Subscription (Remove Ads / Google Pay)
            item {
                val sub = uiState.subscriptionEntitlement
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Hapus Iklan (Google Play / Google Pay)",
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.weight(1f)
                            )
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(if (sub.isAdFreeSubscribed) "Bebas Iklan ✓" else sub.formattedPrice)
                                }
                            )
                        }
                        Text(
                            text = "Produk: ${sub.productId} (${sub.basePlanId})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = sub.statusMessage,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onSubscribeRemoveAdsGooglePlay,
                                enabled = !sub.isAdFreeSubscribed,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("subscribe_remove_ads_button")
                            ) {
                                Text(
                                    text = if (sub.isAdFreeSubscribed) "Langganan Aktif ✓" else "Langganan Google Play",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            OutlinedButton(
                                onClick = onRestoreRemoveAdsSubscription,
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Text(
                                    text = "Restore Purchase",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = onToggleSimulateSandboxSubscription,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                        ) {
                            Text(
                                text = if (sub.isAdFreeSubscribed) {
                                    "Batalkan Langganan Uji (Tampilkan Iklan Kembali)"
                                } else {
                                    "Uji Sandbox Langganan Aktif (Langsung Hapus Iklan)"
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // Remote Config, FCM Push & Mock Backend Ad Control Panel
            item {
                val cfg = uiState.configState.config
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Kontrol Iklan API BE (Mockup) & Firebase FCM",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "GET /v1/games/${cfg.gameId}/config · Sumber: ${uiState.configState.source} · v${cfg.configVersion}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (uiState.lastFcmPushSummary.isNotBlank()) {
                            Text(
                                text = uiState.lastFcmPushSummary,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        HorizontalDivider()

                        Text(
                            text = "Simulasi Panel Admin BE -> Kirim FCM Push (Langsung Berefek Tanpa Reload):",
                            fontWeight = FontWeight.SemiBold
                        )

                        PreferenceToggleRow(
                            label = "BE: Iklan Banner Bawah",
                            subtitle = "bannerAd.enabled",
                            checked = cfg.bannerAd.enabled,
                            onCheckedChange = { enabled ->
                                onTriggerFullBackendAdControl(
                                    cfg.hintsEnabled,
                                    cfg.livesEnabled,
                                    enabled,
                                    cfg.rewardedAd.enabled,
                                    cfg.interstitialAd.enabled,
                                    false
                                )
                            },
                            tag = "toggle_be_banner_ad"
                        )

                        PreferenceToggleRow(
                            label = "BE: Iklan Rewarded",
                            subtitle = "rewardedAd.enabled",
                            checked = cfg.rewardedAd.enabled,
                            onCheckedChange = { enabled ->
                                onTriggerFullBackendAdControl(
                                    cfg.hintsEnabled,
                                    cfg.livesEnabled,
                                    cfg.bannerAd.enabled,
                                    enabled,
                                    cfg.interstitialAd.enabled,
                                    false
                                )
                            },
                            tag = "toggle_be_rewarded_ad"
                        )

                        PreferenceToggleRow(
                            label = "BE: Iklan Interstitial",
                            subtitle = "interstitialAd.enabled",
                            checked = cfg.interstitialAd.enabled,
                            onCheckedChange = { enabled ->
                                onTriggerFullBackendAdControl(
                                    cfg.hintsEnabled,
                                    cfg.livesEnabled,
                                    cfg.bannerAd.enabled,
                                    cfg.rewardedAd.enabled,
                                    enabled,
                                    false
                                )
                            },
                            tag = "toggle_be_interstitial_ad"
                        )

                        PreferenceToggleRow(
                            label = "BE: Fitur Hint",
                            subtitle = "hintsEnabled",
                            checked = cfg.hintsEnabled,
                            onCheckedChange = { enabled ->
                                onTriggerFullBackendAdControl(
                                    enabled,
                                    cfg.livesEnabled,
                                    cfg.bannerAd.enabled,
                                    cfg.rewardedAd.enabled,
                                    cfg.interstitialAd.enabled,
                                    false
                                )
                            },
                            tag = "toggle_be_hints"
                        )

                        PreferenceToggleRow(
                            label = "BE: Fitur Nyawa",
                            subtitle = "livesEnabled",
                            checked = cfg.livesEnabled,
                            onCheckedChange = { enabled ->
                                onTriggerFullBackendAdControl(
                                    cfg.hintsEnabled,
                                    enabled,
                                    cfg.bannerAd.enabled,
                                    cfg.rewardedAd.enabled,
                                    cfg.interstitialAd.enabled,
                                    false
                                )
                            },
                            tag = "toggle_be_lives"
                        )

                        HorizontalDivider()

                        PreferenceToggleRow(
                            label = "Pakai AdMob Test Ad SDK Asli",
                            subtitle = "Google Mobile Ads SDK",
                            checked = uiState.useRealAdMobTestAds,
                            onCheckedChange = onToggleRealAdMobTestAds,
                            tag = "toggle_real_admob"
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val isBeOffline = uiState.configState.source == com.binarydev.startergame.core.contracts.ConfigSource.SAFE_DEFAULT_ADS_OFF
                            OutlinedButton(
                                onClick = {
                                    onTriggerFullBackendAdControl(
                                        cfg.hintsEnabled,
                                        cfg.livesEnabled,
                                        if (isBeOffline) true else cfg.bannerAd.enabled,
                                        if (isBeOffline) true else cfg.rewardedAd.enabled,
                                        cfg.interstitialAd.enabled,
                                        !isBeOffline
                                    )
                                },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Text(
                                    text = if (isBeOffline) "Hidupkan Server BE" else "Simulasi BE Off",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            OutlinedButton(
                                onClick = { showConfigJson = !showConfigJson },
                                enabled = uiState.lastConfigJsonPayload.isNotBlank(),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Text(
                                    text = if (showConfigJson) "Tutup JSON" else "Lihat JSON Config",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        if (showConfigJson && uiState.lastConfigJsonPayload.isNotBlank()) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFF0F172A)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "GET /v1/games/${cfg.gameId}/config · JSON Response:",
                                        color = Color(0xFF38BDF8),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = uiState.lastConfigJsonPayload,
                                        color = Color(0xFFE2E8F0),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Privacy link section
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Privasi",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Tautan Kebijakan Privasi: ${uiState.privacyPolicyUrl}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedButton(
                            onClick = {
                                runCatching { uriHandler.openUri(uiState.privacyPolicyUrl) }
                            },
                            modifier = Modifier.testTag("privacy_policy_button")
                        ) {
                            Text("Buka Kebijakan Privasi")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreferenceToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
    subtitle: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag)
        )
    }
}

// --- 8. Persistent Bottom AdMob Banner Ad ---

@Composable
fun PersistentBottomAdMobBanner(
    adUnitId: String = BuildConfig.ADMOB_BANNER_AD_UNIT_ID,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var adLoaded by remember { androidx.compose.runtime.mutableStateOf(false) }
    var adStatusText by remember { androidx.compose.runtime.mutableStateOf("AdMob Test Banner · Memuat iklan...") }

    val adView = remember(adUnitId) {
        AdView(context).apply {
            setAdSize(AdSize.BANNER)
            this.adUnitId = adUnitId
            adListener = object : AdListener() {
                override fun onAdLoaded() {
                    adLoaded = true
                    adStatusText = "AdMob Banner Aktif"
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    adLoaded = false
                    adStatusText = "AdMob Banner SDK (${error.message})"
                }
            }
            runCatching {
                loadAd(AdRequest.Builder().build())
            }.onFailure {
                adStatusText = "AdMob Banner SDK · Test Mode"
            }
        }
    }

    DisposableEffect(adView) {
        onDispose {
            runCatching { adView.destroy() }
        }
    }

    Surface(
        color = Color(0xFF1E293B),
        contentColor = Color.White,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .testTag("persistent_bottom_banner_ad")
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (!adLoaded) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Ad",
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(Color(0xFFFACC15), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    Text(
                        text = adStatusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    Text(
                        text = "AdMob",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF38BDF8),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            AndroidView(
                factory = { adView }
            )
        }
    }
}

// --- 7. Interactive Simulated Rewarded Video Ad Player Modal ---


@Composable
fun SimulatedRewardedVideoAdOverlay(
    session: InteractiveAdSession
) {
    var remainingSeconds by remember(session.claimId) { mutableIntStateOf(session.durationSeconds) }

    LaunchedEffect(session.claimId) {
        remainingSeconds = session.durationSeconds
        while (remainingSeconds > 0) {
            delay(1_000L)
            remainingSeconds -= 1
        }
    }

    val isRewardEarned = remainingSeconds <= 0
    val progress = ((session.durationSeconds - remainingSeconds).toFloat() / session.durationSeconds.toFloat())
        .coerceIn(0f, 1f)

    Dialog(
        onDismissRequest = {
            if (isRewardEarned) session.onCompleted() else session.onDismissedEarly()
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F172A))
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top bar: Ad badge + countdown / close control
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                text = "IKLAN REWARDED · DEMO VIDEO",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    )
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                text = if (isRewardEarned) "Reward Siap ✓" else "$remainingSeconds dtk tersisa",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    )
                }

                // Center Video Stage
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1E293B)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = if (isRewardEarned) "🎉 VIDEO SELESAI DITONTON" else "▶ SEDANG MEMUTAR VIDEO IKLAN...",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color(0xFF2DD4BF),
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Kata Nusantara Puzzle · Sponsor Demo\nTonton sampai waktu habis untuk mendapatkan +1 Hint dan +1 Nyawa.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color(0xFFE2E8F0),
                            textAlign = TextAlign.Center
                        )
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                        )
                        Text(
                            text = "ID Transaksi Klaim: ${session.claimId}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF94A3B8),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Bottom action buttons
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (isRewardEarned) {
                        Button(
                            onClick = session.onCompleted,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("claim_ad_reward_button")
                        ) {
                            Text("Klaim Reward & Tutup (+1 Hint, +1 Nyawa)")
                        }
                    } else {
                        Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Menunggu Video Selesai ($remainingSeconds detik)...")
                        }
                        OutlinedButton(
                            onClick = session.onDismissedEarly,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("close_ad_early_button")
                        ) {
                            Text(
                                text = "Tutup Iklan Lebih Awal (Tanpa Reward)",
                                color = Color(0xFFFCA5A5)
                            )
                        }
                    }
                }
            }
        }
    }
}
