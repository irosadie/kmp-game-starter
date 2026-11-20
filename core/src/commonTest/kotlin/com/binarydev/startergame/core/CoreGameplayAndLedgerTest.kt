package com.binarydev.startergame.core

import com.binarydev.startergame.core.achievement.AchievementDefinition
import com.binarydev.startergame.core.achievement.AchievementEvaluator
import com.binarydev.startergame.core.engine.GameSessionCoordinator
import com.binarydev.startergame.core.engine.HintApplicationResult
import com.binarydev.startergame.core.engine.HintRequestResult
import com.binarydev.startergame.core.engine.LevelOutcome
import com.binarydev.startergame.core.engine.LevelRuleEngine
import com.binarydev.startergame.core.engine.StartLevelResult
import com.binarydev.startergame.core.ledger.LedgerOperationResult
import com.binarydev.startergame.core.ledger.RewardLedger
import com.binarydev.startergame.core.model.GameMetadataAndFeatures
import com.binarydev.startergame.core.model.PlayerBalance
import com.binarydev.startergame.core.model.ResourceType
import com.binarydev.startergame.core.model.RewardBundle
import com.binarydev.startergame.core.model.RewardSource
import com.binarydev.startergame.core.sample.SamplePuzzleGame
import com.binarydev.startergame.core.sample.SamplePuzzleMove
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreGameplayAndLedgerTest {

    @Test
    fun rewardLedgerGrantsHintAndLifeIdempotentlyByTransactionId() {
        val initial = RewardLedger(balance = PlayerBalance(hints = 2, lives = 3))

        val (afterFirst, res1) = initial.grantReward(
            transactionId = "ad_claim_101",
            resourceType = ResourceType.HINTS,
            amount = 1,
            source = RewardSource.REWARDED_AD
        )
        assertIs<LedgerOperationResult.Applied>(res1)
        assertEquals(3, afterFirst.balance.hints)

        // Duplicate callback with same transactionId must be ignored
        val (afterDuplicate, res2) = afterFirst.grantReward(
            transactionId = "ad_claim_101",
            resourceType = ResourceType.HINTS,
            amount = 1,
            source = RewardSource.REWARDED_AD
        )
        assertIs<LedgerOperationResult.AlreadyApplied>(res2)
        assertEquals(3, afterDuplicate.balance.hints)
        assertEquals(1, afterDuplicate.transactions.size)
    }

    @Test
    fun failedAttemptDeductsLifeAtMostOncePerAttemptId() {
        val initial = RewardLedger(balance = PlayerBalance(hints = 1, lives = 2))

        val (afterFail1, res1) = initial.deductLifeForFailedAttempt("attempt-xyz")
        assertIs<LedgerOperationResult.Applied>(res1)
        assertEquals(1, afterFail1.balance.lives)

        // Reprocessing the exact same failed attempt ID does not deduct another life
        val (afterFailDuplicate, res2) = afterFail1.deductLifeForFailedAttempt("attempt-xyz")
        assertIs<LedgerOperationResult.AlreadyApplied>(res2)
        assertEquals(1, afterFailDuplicate.balance.lives)
    }

    @Test
    fun hintIsDeductedOnlyWhenEffectAppliesAndRejectedWhenFeatureDisabled() {
        val game = SamplePuzzleGame()
        val coordinator = GameSessionCoordinator(game)
        val level1 = game.levels.first()
        val initialProgress = coordinator.initialProgressMap()
        val ledger = RewardLedger(balance = PlayerBalance(hints = 2, lives = 3))
        val achievements = AchievementEvaluator().initialStates().associateBy { it.id }

        val started = coordinator.startOrResumeLevel(level1, "attempt-1", initialProgress, ledger)
        assertIs<StartLevelResult.Started<*>>(started)
        val initialState = (started as StartLevelResult.Started).state

        // 1. Hints disabled in feature override -> rejected by core
        val disabledMeta = game.metadata.copy(hintsEnabled = false)
        val disabledResult = coordinator.requestHint(
            profileId = "guest-1",
            level = level1,
            currentState = initialState,
            hintTransactionId = "hint-tx-1",
            progressMap = initialProgress,
            ledger = ledger,
            achievements = achievements,
            featuresOverride = disabledMeta
        )
        assertIs<HintRequestResult.FeatureDisabled>(disabledResult)

        // 2. Hints enabled -> applies first letter 'K' and deducts 1 hint
        val enabledResult = coordinator.requestHint(
            profileId = "guest-1",
            level = level1,
            currentState = initialState,
            hintTransactionId = "hint-tx-1",
            progressMap = initialProgress,
            ledger = ledger,
            achievements = achievements
        )
        assertIs<HintRequestResult.Applied<*>>(enabledResult)
        assertEquals(1, enabledResult.updatedLedger.balance.hints)
        assertEquals(listOf("K"), (enabledResult as HintRequestResult.Applied).newState.placedSequence)

        // 3. When puzzle is already complete, hint is not applicable and does NOT deduct balance
        val completedState = initialState.copy(placedSequence = level1.targetSequence)
        val notApplicableRes = coordinator.requestHint(
            profileId = "guest-1",
            level = level1,
            currentState = completedState,
            hintTransactionId = "hint-tx-2",
            progressMap = initialProgress,
            ledger = enabledResult.updatedLedger,
            achievements = achievements
        )
        assertIs<HintRequestResult.NotApplicable>(notApplicableRes)
    }

    @Test
    fun livesFeatureToggleAndOutOfLivesRecoveryCheck() {
        val game = SamplePuzzleGame()
        val coordinator = GameSessionCoordinator(game)
        val level1 = game.levels.first()
        val initialProgress = coordinator.initialProgressMap()
        val achievements = AchievementEvaluator().initialStates().associateBy { it.id }

        // When lives = 0 and livesEnabled = true, starting a level returns OutOfLives with recovery methods
        val zeroLivesLedger = RewardLedger(balance = PlayerBalance(hints = 1, lives = 0))
        val outOfLivesStart = coordinator.startOrResumeLevel(level1, "attempt-0", initialProgress, zeroLivesLedger)
        assertIs<StartLevelResult.OutOfLives>(outOfLivesStart)
        assertTrue(outOfLivesStart.recoveryMethods.isNotEmpty())

        // When livesEnabled = false, player can start even with 0 lives and failing does not deduct lives
        val livesOffMeta = game.metadata.copy(livesEnabled = false)
        val allowedStart = coordinator.startOrResumeLevel(
            level = level1,
            attemptId = "attempt-no-lives",
            progressMap = initialProgress,
            ledger = RewardLedger(balance = PlayerBalance(hints = 1, lives = 3)),
            featuresOverride = livesOffMeta
        )
        assertIs<StartLevelResult.Started<*>>(allowedStart)
        var state = (allowedStart as StartLevelResult.Started).state
        var ledger = RewardLedger(balance = PlayerBalance(hints = 1, lives = 3))

        // Make 2 wrong moves to fail level1 (maxMistakes = 2)
        val step1 = coordinator.submitMove(
            profileId = "guest-1",
            level = level1,
            currentState = state,
            move = SamplePuzzleMove.PlaceTile("Z"),
            progressMap = initialProgress,
            ledger = ledger,
            achievements = achievements,
            featuresOverride = livesOffMeta
        )
        state = step1.newState
        val step2 = coordinator.submitMove(
            profileId = "guest-1",
            level = level1,
            currentState = state,
            move = SamplePuzzleMove.PlaceTile("Z"),
            progressMap = step1.updatedProgress,
            ledger = step1.updatedLedger,
            achievements = step1.updatedAchievements,
            featuresOverride = livesOffMeta
        )
        assertIs<LevelOutcome.Lost>(step2.outcome)
        assertFalse(step2.lifeDeducted)
        assertEquals(3, step2.updatedLedger.balance.lives)
    }

    @Test
    fun completingFirstLevelWithoutHintUnlocksDemoAchievementsAndGrantsRewardsOnce() {
        val game = SamplePuzzleGame()
        val coordinator = GameSessionCoordinator(game)
        val level1 = game.levels.first()
        var progress = coordinator.initialProgressMap()
        var ledger = RewardLedger(balance = PlayerBalance(hints = 2, lives = 3))
        var achievements = AchievementEvaluator().initialStates().associateBy { it.id }

        val started = coordinator.startOrResumeLevel(level1, "attempt-win-1", progress, ledger) as StartLevelResult.Started
        var state = started.state

        // Play first 3 letters ("K", "O", "T") and verify checkpoint is saved
        for (letter in listOf("K", "O", "T")) {
            val res = coordinator.submitMove("guest-1", level1, state, SamplePuzzleMove.PlaceTile(letter), progress, ledger, achievements)
            state = res.newState
            progress = res.updatedProgress
            ledger = res.updatedLedger
            achievements = res.updatedAchievements
        }
        assertNotNull(progress[level1.id]?.checkpointJson)

        // Simulate app restart: resume from saved checkpoint
        val resumed = coordinator.startOrResumeLevel(level1, "attempt-new-ignored", progress, ledger)
        assertIs<StartLevelResult.Started<*>>(resumed)
        assertTrue(resumed.restoredFromCheckpoint)
        state = (resumed as StartLevelResult.Started).state
        assertEquals(listOf("K", "O", "T"), state.placedSequence)

        // Finish remaining letters ("L", "I", "N") without hints
        var lastMoveResult = coordinator.submitMove("guest-1", level1, state, SamplePuzzleMove.PlaceTile("L"), progress, ledger, achievements)
        state = lastMoveResult.newState
        progress = lastMoveResult.updatedProgress
        ledger = lastMoveResult.updatedLedger
        achievements = lastMoveResult.updatedAchievements

        lastMoveResult = coordinator.submitMove("guest-1", level1, state, SamplePuzzleMove.PlaceTile("I"), progress, ledger, achievements)
        state = lastMoveResult.newState
        progress = lastMoveResult.updatedProgress
        ledger = lastMoveResult.updatedLedger
        achievements = lastMoveResult.updatedAchievements

        lastMoveResult = coordinator.submitMove("guest-1", level1, state, SamplePuzzleMove.PlaceTile("N"), progress, ledger, achievements)
        progress = lastMoveResult.updatedProgress
        ledger = lastMoveResult.updatedLedger
        achievements = lastMoveResult.updatedAchievements

        assertIs<LevelOutcome.Won>(lastMoveResult.outcome)
        assertEquals(1000, (lastMoveResult.outcome as LevelOutcome.Won).score)
        assertNull(progress[level1.id]?.checkpointJson)
        assertTrue(progress[level1.id]?.completed == true)
        assertTrue(progress["level-2"]?.unlocked == true)

        // Both demo achievements ("Selesaikan level pertama" and "Selesaikan level tanpa hint") unlocked!
        assertEquals(2, lastMoveResult.newlyUnlockedAchievements.size)
        assertTrue(achievements[AchievementEvaluator.ACHIEVEMENT_FIRST_LEVEL_ID]?.unlocked == true)
        assertTrue(achievements[AchievementEvaluator.ACHIEVEMENT_NO_HINT_ID]?.unlocked == true)

        // Balance check: initial (2 hints, 3 lives) + level completion (1 hint) + first_level_clear (1 hint) + no_hint_clear (1 hint, 1 life) = 5 hints, 4 lives
        assertEquals(5, ledger.balance.hints)
        assertEquals(4, ledger.balance.lives)

        // Replaying level 1 again must NOT re-grant achievement rewards
        val replayStart = coordinator.startOrResumeLevel(level1, "attempt-win-2", progress, ledger) as StartLevelResult.Started
        var replayState = replayStart.state
        for (letter in level1.targetSequence) {
            val res = coordinator.submitMove("guest-1", level1, replayState, SamplePuzzleMove.PlaceTile(letter), progress, ledger, achievements)
            replayState = res.newState
            progress = res.updatedProgress
            ledger = res.updatedLedger
            achievements = res.updatedAchievements
            lastMoveResult = res
        }
        assertTrue(lastMoveResult.newlyUnlockedAchievements.isEmpty())
        // Only the new level completion reward (+1 hint for attempt-win-2) is granted; achievements are NOT re-paid
        assertEquals(6, ledger.balance.hints)
        assertEquals(4, ledger.balance.lives)
    }

    @Test
    fun customGameRuleImplementationSwapsWithoutChangingCoordinatorOrLedger() {
        // Verify a developer can plug in completely different game rules (e.g. a trivia/number quiz)
        val customRuleEngine = object : LevelRuleEngine<Int, Int, Int> {
            override val metadata = GameMetadataAndFeatures(gameId = "math-quiz", title = "Math Quiz")
            override val levels = listOf(42)
            override val achievements: List<AchievementDefinition> = AchievementEvaluator.defaultDemoAchievements()
            override fun getLevelId(level: Int): String = "math-$level"
            override fun createInitialState(level: Int, attemptId: String): Int = 0
            override fun getAttemptId(state: Int): String = "custom-attempt"
            override fun getHintsUsedCount(state: Int): Int = 0
            override fun applyMove(state: Int, move: Int): Int = move
            override fun applyHint(state: Int): HintApplicationResult<Int> = HintApplicationResult.Applied(42, "Answer is 42")
            override fun evaluateOutcome(state: Int): LevelOutcome =
                if (state == 42) LevelOutcome.Won(500) else LevelOutcome.InProgress
            override fun serializeCheckpoint(state: Int): String = state.toString()
            override fun deserializeCheckpoint(level: Int, json: String): Int = json.toInt()
        }

        val coordinator = GameSessionCoordinator(customRuleEngine)
        val progress = coordinator.initialProgressMap()
        val ledger = RewardLedger(balance = PlayerBalance(hints = 1, lives = 3))
        val achievements = AchievementEvaluator().initialStates().associateBy { it.id }

        val res = coordinator.submitMove("guest-1", 42, 0, 42, progress, ledger, achievements)
        assertIs<LevelOutcome.Won>(res.outcome)
        assertEquals(500, res.updatedProgress["math-42"]?.bestScore)
    }
}
