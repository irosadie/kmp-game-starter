package com.binarydev.startergame.core.engine

import com.binarydev.startergame.core.achievement.AchievementDefinition
import com.binarydev.startergame.core.achievement.AchievementEvaluator
import com.binarydev.startergame.core.achievement.AchievementState
import com.binarydev.startergame.core.achievement.LevelCompletionContext
import com.binarydev.startergame.core.ledger.LedgerOperationResult
import com.binarydev.startergame.core.ledger.RewardLedger
import com.binarydev.startergame.core.model.GameMetadataAndFeatures
import com.binarydev.startergame.core.model.LevelProgress
import com.binarydev.startergame.core.model.LifeRecoveryMethod
import com.binarydev.startergame.core.model.RewardSource

sealed class HintApplicationResult<out State> {
    data class Applied<State>(
        val newState: State,
        val hintDescription: String
    ) : HintApplicationResult<State>()

    data class NotApplicable(
        val reason: String
    ) : HintApplicationResult<Nothing>()
}

sealed class LevelOutcome {
    data object InProgress : LevelOutcome()

    data class Won(
        val score: Int,
        val validatorPayload: Map<String, String> = emptyMap()
    ) : LevelOutcome()

    data class Lost(
        val attemptId: String,
        val reason: String
    ) : LevelOutcome()
}

interface LevelRuleEngine<LevelData, State, Move> {
    val metadata: GameMetadataAndFeatures
    val levels: List<LevelData>
    val achievements: List<AchievementDefinition>

    fun getLevelId(level: LevelData): String
    fun createInitialState(level: LevelData, attemptId: String): State
    fun getAttemptId(state: State): String
    fun getHintsUsedCount(state: State): Int
    fun applyMove(state: State, move: Move): State
    fun applyHint(state: State): HintApplicationResult<State>
    fun evaluateOutcome(state: State): LevelOutcome
    fun serializeCheckpoint(state: State): String
    fun deserializeCheckpoint(level: LevelData, json: String): State
}

sealed class StartLevelResult<out State> {
    data class Started<State>(
        val state: State,
        val restoredFromCheckpoint: Boolean
    ) : StartLevelResult<State>()

    data class OutOfLives(
        val recoveryMethods: List<LifeRecoveryMethod>
    ) : StartLevelResult<Nothing>()

    data class LevelLocked(
        val levelId: String
    ) : StartLevelResult<Nothing>()
}

sealed class HintRequestResult<out State> {
    data class Applied<State>(
        val newState: State,
        val hintDescription: String,
        val updatedLedger: RewardLedger,
        val updatedProgress: Map<String, LevelProgress>,
        val outcome: LevelOutcome,
        val newlyUnlockedAchievements: List<AchievementState> = emptyList(),
        val updatedAchievements: Map<String, AchievementState> = emptyMap()
    ) : HintRequestResult<State>()

    data object FeatureDisabled : HintRequestResult<Nothing>()
    data object InsufficientHints : HintRequestResult<Nothing>()
    data class NotApplicable(val reason: String) : HintRequestResult<Nothing>()
}

data class MoveEvaluationResult<State>(
    val newState: State,
    val outcome: LevelOutcome,
    val updatedLedger: RewardLedger,
    val updatedProgress: Map<String, LevelProgress>,
    val updatedAchievements: Map<String, AchievementState>,
    val newlyUnlockedAchievements: List<AchievementState>,
    val lifeDeducted: Boolean,
    val recoveryMethodsIfOutOfLives: List<LifeRecoveryMethod>
)

/**
 * Headless coordinator enforcing hint/life feature flags, idempotent reward/life mutations,
 * checkpoint persistence updates, and achievement unlocking.
 */
class GameSessionCoordinator<LevelData, State, Move>(
    val ruleEngine: LevelRuleEngine<LevelData, State, Move>,
    private val achievementEvaluator: AchievementEvaluator = AchievementEvaluator(ruleEngine.achievements)
) {
    fun initialProgressMap(): Map<String, LevelProgress> {
        return ruleEngine.levels.mapIndexed { index, level ->
            val id = ruleEngine.getLevelId(level)
            id to LevelProgress(
                levelId = id,
                unlocked = index == 0,
                completed = false,
                bestScore = 0,
                checkpointJson = null
            )
        }.toMap()
    }

    fun startOrResumeLevel(
        level: LevelData,
        attemptId: String,
        progressMap: Map<String, LevelProgress>,
        ledger: RewardLedger,
        featuresOverride: GameMetadataAndFeatures = ruleEngine.metadata
    ): StartLevelResult<State> {
        val levelId = ruleEngine.getLevelId(level)
        val levelProgress = progressMap[levelId] ?: LevelProgress(
            levelId = levelId,
            unlocked = ruleEngine.levels.firstOrNull()?.let { ruleEngine.getLevelId(it) == levelId } == true
        )
        if (!levelProgress.unlocked) {
            return StartLevelResult.LevelLocked(levelId)
        }

        if (featuresOverride.livesEnabled && ledger.balance.lives <= 0) {
            return StartLevelResult.OutOfLives(
                listOf(LifeRecoveryMethod.REWARDED_AD, LifeRecoveryMethod.ACHIEVEMENT_REWARD)
            )
        }

        val checkpoint = levelProgress.checkpointJson
        if (!checkpoint.isNullOrBlank()) {
            val restored = runCatching { ruleEngine.deserializeCheckpoint(level, checkpoint) }.getOrNull()
            if (restored != null && ruleEngine.evaluateOutcome(restored) is LevelOutcome.InProgress) {
                return StartLevelResult.Started(restored, restoredFromCheckpoint = true)
            }
        }

        return StartLevelResult.Started(
            state = ruleEngine.createInitialState(level, attemptId),
            restoredFromCheckpoint = false
        )
    }

    fun submitMove(
        profileId: String,
        level: LevelData,
        currentState: State,
        move: Move,
        progressMap: Map<String, LevelProgress>,
        ledger: RewardLedger,
        achievements: Map<String, AchievementState>,
        featuresOverride: GameMetadataAndFeatures = ruleEngine.metadata,
        timestampEpochMs: Long = 0L
    ): MoveEvaluationResult<State> {
        val nextState = ruleEngine.applyMove(currentState, move)
        return resolvePostActionState(
            profileId = profileId,
            level = level,
            nextState = nextState,
            progressMap = progressMap,
            ledger = ledger,
            achievements = achievements,
            featuresOverride = featuresOverride,
            timestampEpochMs = timestampEpochMs
        )
    }

    fun requestHint(
        profileId: String,
        level: LevelData,
        currentState: State,
        hintTransactionId: String,
        progressMap: Map<String, LevelProgress>,
        ledger: RewardLedger,
        achievements: Map<String, AchievementState>,
        featuresOverride: GameMetadataAndFeatures = ruleEngine.metadata,
        timestampEpochMs: Long = 0L
    ): HintRequestResult<State> {
        if (!featuresOverride.hintsEnabled) {
            return HintRequestResult.FeatureDisabled
        }
        if (ledger.balance.hints <= 0) {
            return HintRequestResult.InsufficientHints
        }

        return when (val hintResult = ruleEngine.applyHint(currentState)) {
            is HintApplicationResult.NotApplicable -> HintRequestResult.NotApplicable(hintResult.reason)
            is HintApplicationResult.Applied -> {
                val (afterHintLedger, consumeRes) = ledger.consumeHint(
                    transactionId = hintTransactionId,
                    timestampEpochMs = timestampEpochMs
                )
                if (consumeRes is LedgerOperationResult.Rejected) {
                    return HintRequestResult.InsufficientHints
                }
                val resolved = resolvePostActionState(
                    profileId = profileId,
                    level = level,
                    nextState = hintResult.newState,
                    progressMap = progressMap,
                    ledger = afterHintLedger,
                    achievements = achievements,
                    featuresOverride = featuresOverride,
                    timestampEpochMs = timestampEpochMs
                )
                HintRequestResult.Applied(
                    newState = resolved.newState,
                    hintDescription = hintResult.hintDescription,
                    updatedLedger = resolved.updatedLedger,
                    updatedProgress = resolved.updatedProgress,
                    outcome = resolved.outcome,
                    newlyUnlockedAchievements = resolved.newlyUnlockedAchievements,
                    updatedAchievements = resolved.updatedAchievements
                )
            }
        }
    }

    fun processFailedAttemptOutcome(
        attemptId: String,
        ledger: RewardLedger,
        featuresOverride: GameMetadataAndFeatures = ruleEngine.metadata,
        timestampEpochMs: Long = 0L
    ): Pair<RewardLedger, Boolean> {
        if (!featuresOverride.livesEnabled) {
            return ledger to false
        }
        val (updatedLedger, opResult) = ledger.deductLifeForFailedAttempt(attemptId, timestampEpochMs)
        return updatedLedger to (opResult is LedgerOperationResult.Applied)
    }

    private fun resolvePostActionState(
        profileId: String,
        level: LevelData,
        nextState: State,
        progressMap: Map<String, LevelProgress>,
        ledger: RewardLedger,
        achievements: Map<String, AchievementState>,
        featuresOverride: GameMetadataAndFeatures,
        timestampEpochMs: Long
    ): MoveEvaluationResult<State> {
        val levelId = ruleEngine.getLevelId(level)
        val outcome = ruleEngine.evaluateOutcome(nextState)
        val mutableProgress = progressMap.toMutableMap()
        val existingProgress = mutableProgress[levelId] ?: LevelProgress(levelId = levelId, unlocked = true)

        var currentLedger = ledger
        var currentAchievements = achievements
        var newlyUnlocked = emptyList<AchievementState>()
        var lifeDeducted = false

        when (outcome) {
            is LevelOutcome.InProgress -> {
                mutableProgress[levelId] = existingProgress.copy(
                    checkpointJson = ruleEngine.serializeCheckpoint(nextState)
                )
            }
            is LevelOutcome.Lost -> {
                mutableProgress[levelId] = existingProgress.copy(checkpointJson = null)
                val (afterLossLedger, deducted) = processFailedAttemptOutcome(
                    attemptId = outcome.attemptId,
                    ledger = currentLedger,
                    featuresOverride = featuresOverride,
                    timestampEpochMs = timestampEpochMs
                )
                currentLedger = afterLossLedger
                lifeDeducted = deducted
            }
            is LevelOutcome.Won -> {
                val updatedBest = maxOf(existingProgress.bestScore, outcome.score)
                mutableProgress[levelId] = existingProgress.copy(
                    unlocked = true,
                    completed = true,
                    bestScore = updatedBest,
                    checkpointJson = null
                )

                // Unlock next level if present
                val currentIndex = ruleEngine.levels.indexOfFirst { ruleEngine.getLevelId(it) == levelId }
                if (currentIndex >= 0 && currentIndex + 1 < ruleEngine.levels.size) {
                    val nextLevelId = ruleEngine.getLevelId(ruleEngine.levels[currentIndex + 1])
                    val nextLevelExisting = mutableProgress[nextLevelId] ?: LevelProgress(
                        levelId = nextLevelId,
                        unlocked = true
                    )
                    mutableProgress[nextLevelId] = nextLevelExisting.copy(unlocked = true)
                }

                // Grant level completion reward idempotently by attemptId
                val attemptId = ruleEngine.getAttemptId(nextState)
                if (!featuresOverride.levelCompletionReward.isEmpty) {
                    currentLedger = currentLedger.grantBundle(
                        baseTransactionId = "level_complete:$levelId:$attemptId",
                        bundle = featuresOverride.levelCompletionReward,
                        source = RewardSource.LEVEL_COMPLETION,
                        timestampEpochMs = timestampEpochMs
                    ).ledger
                }

                // Evaluate achievements
                val firstLevelId = ruleEngine.levels.firstOrNull()?.let { ruleEngine.getLevelId(it) }
                val completedCount = mutableProgress.values.count { it.completed }
                val evalResult = achievementEvaluator.evaluateOnLevelCompleted(
                    context = LevelCompletionContext(
                        profileId = profileId,
                        levelId = levelId,
                        isFirstLevel = levelId == firstLevelId,
                        hintsUsedInAttempt = ruleEngine.getHintsUsedCount(nextState),
                        score = outcome.score,
                        totalCompletedLevelsCount = completedCount,
                        timestampEpochMs = timestampEpochMs
                    ),
                    existingStates = currentAchievements,
                    ledger = currentLedger
                )
                currentLedger = evalResult.ledger
                currentAchievements = evalResult.states.associateBy { it.id }
                newlyUnlocked = evalResult.newlyUnlocked
            }
        }

        val recoveryMethods = if (featuresOverride.livesEnabled && currentLedger.balance.lives <= 0) {
            listOf(LifeRecoveryMethod.REWARDED_AD, LifeRecoveryMethod.ACHIEVEMENT_REWARD)
        } else {
            emptyList()
        }

        return MoveEvaluationResult(
            newState = nextState,
            outcome = outcome,
            updatedLedger = currentLedger,
            updatedProgress = mutableProgress,
            updatedAchievements = currentAchievements,
            newlyUnlockedAchievements = newlyUnlocked,
            lifeDeducted = lifeDeducted,
            recoveryMethodsIfOutOfLives = recoveryMethods
        )
    }
}
