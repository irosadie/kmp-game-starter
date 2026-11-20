package com.binarydev.startergame.core.achievement

import com.binarydev.startergame.core.ledger.RewardLedger
import com.binarydev.startergame.core.model.RewardBundle
import com.binarydev.startergame.core.model.RewardSource
import kotlinx.serialization.Serializable

@Serializable
sealed class AchievementCriterion {
    @Serializable
    data object CompleteFirstLevel : AchievementCriterion()

    @Serializable
    data object CompleteLevelWithoutHints : AchievementCriterion()

    @Serializable
    data class CompleteLevelsCount(val targetCount: Int) : AchievementCriterion()

    @Serializable
    data class ReachScoreInSingleLevel(val targetScore: Int) : AchievementCriterion()
}

@Serializable
data class AchievementDefinition(
    val id: String,
    val title: String,
    val description: String,
    val targetValue: Int,
    val criterion: AchievementCriterion,
    val reward: RewardBundle
)

@Serializable
data class AchievementState(
    val id: String,
    val title: String,
    val description: String,
    val targetValue: Int,
    val currentProgress: Int = 0,
    val unlocked: Boolean = false,
    val reward: RewardBundle = RewardBundle(),
    val rewardTransactionId: String? = null
)

data class LevelCompletionContext(
    val profileId: String,
    val levelId: String,
    val isFirstLevel: Boolean,
    val hintsUsedInAttempt: Int,
    val score: Int,
    val totalCompletedLevelsCount: Int,
    val timestampEpochMs: Long = 0L
)

data class AchievementEvaluationResult(
    val states: List<AchievementState>,
    val newlyUnlocked: List<AchievementState>,
    val ledger: RewardLedger
)

class AchievementEvaluator(
    val definitions: List<AchievementDefinition> = defaultDemoAchievements()
) {
    fun initialStates(existing: Map<String, AchievementState> = emptyMap()): List<AchievementState> = definitions.map { def ->
        val saved = existing[def.id]
        saved?.copy(
            title = def.title,
            description = def.description,
            targetValue = def.targetValue,
            reward = def.reward
        ) ?: AchievementState(
            id = def.id,
            title = def.title,
            description = def.description,
            targetValue = def.targetValue,
            currentProgress = 0,
            unlocked = false,
            reward = def.reward,
            rewardTransactionId = null
        )
    }

    fun evaluateOnLevelCompleted(
        context: LevelCompletionContext,
        existingStates: Map<String, AchievementState>,
        ledger: RewardLedger
    ): AchievementEvaluationResult {
        var currentLedger = ledger
        val updatedStates = mutableListOf<AchievementState>()
        val newlyUnlocked = mutableListOf<AchievementState>()

        for (def in definitions) {
            val prev = existingStates[def.id] ?: AchievementState(
                id = def.id,
                title = def.title,
                description = def.description,
                targetValue = def.targetValue,
                reward = def.reward
            )

            val candidateProgress = when (val c = def.criterion) {
                is AchievementCriterion.CompleteFirstLevel ->
                    if (context.isFirstLevel || prev.currentProgress >= 1) 1 else 0
                is AchievementCriterion.CompleteLevelWithoutHints ->
                    if (context.hintsUsedInAttempt == 0 || prev.currentProgress >= 1) 1 else 0
                is AchievementCriterion.CompleteLevelsCount ->
                    maxOf(prev.currentProgress, context.totalCompletedLevelsCount).coerceAtMost(c.targetCount)
                is AchievementCriterion.ReachScoreInSingleLevel ->
                    maxOf(prev.currentProgress, context.score).coerceAtMost(c.targetScore)
            }.coerceAtMost(def.targetValue)

            val nowUnlocked = prev.unlocked || candidateProgress >= def.targetValue
            val rewardTxBaseId = "achievement:${context.profileId}:${def.id}"

            if (nowUnlocked && !def.reward.isEmpty) {
                val grantResult = currentLedger.grantBundle(
                    baseTransactionId = rewardTxBaseId,
                    bundle = def.reward,
                    source = RewardSource.ACHIEVEMENT,
                    timestampEpochMs = context.timestampEpochMs
                )
                currentLedger = grantResult.ledger
            }

            val nextState = prev.copy(
                title = def.title,
                description = def.description,
                targetValue = def.targetValue,
                currentProgress = if (nowUnlocked) def.targetValue else candidateProgress,
                unlocked = nowUnlocked,
                reward = def.reward,
                rewardTransactionId = if (nowUnlocked) rewardTxBaseId else prev.rewardTransactionId
            )
            updatedStates += nextState
            if (!prev.unlocked && nowUnlocked) {
                newlyUnlocked += nextState
            }
        }

        return AchievementEvaluationResult(
            states = updatedStates,
            newlyUnlocked = newlyUnlocked,
            ledger = currentLedger
        )
    }

    companion object {
        const val ACHIEVEMENT_FIRST_LEVEL_ID = "first_level_clear"
        const val ACHIEVEMENT_NO_HINT_ID = "no_hint_clear"

        fun defaultDemoAchievements(): List<AchievementDefinition> = listOf(
            AchievementDefinition(
                id = ACHIEVEMENT_FIRST_LEVEL_ID,
                title = "Selesaikan level pertama",
                description = "Selesaikan level pertama permainan.",
                targetValue = 1,
                criterion = AchievementCriterion.CompleteFirstLevel,
                reward = RewardBundle(hints = 1, lives = 0)
            ),
            AchievementDefinition(
                id = ACHIEVEMENT_NO_HINT_ID,
                title = "Selesaikan level tanpa hint",
                description = "Menangkan level tanpa memakai bantuan hint.",
                targetValue = 1,
                criterion = AchievementCriterion.CompleteLevelWithoutHints,
                reward = RewardBundle(hints = 1, lives = 1)
            )
        )
    }
}
