package com.binarydev.startergame.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ResourceType {
    HINTS,
    LIVES
}

@Serializable
enum class RewardSource {
    INITIAL_GRANT,
    LEVEL_COMPLETION,
    ACHIEVEMENT,
    REWARDED_AD,
    HINT_CONSUMED,
    ATTEMPT_FAILED
}

@Serializable
data class RewardBundle(
    val hints: Int = 0,
    val lives: Int = 0
) {
    val isEmpty: Boolean get() = hints <= 0 && lives <= 0
}

@Serializable
data class LedgerTransaction(
    val transactionId: String,
    val resourceType: ResourceType,
    val delta: Int,
    val source: RewardSource,
    val timestampEpochMs: Long = 0L
)

@Serializable
data class PlayerBalance(
    val hints: Int,
    val lives: Int
)

@Serializable
data class LevelProgress(
    val levelId: String,
    val unlocked: Boolean,
    val completed: Boolean = false,
    val bestScore: Int = 0,
    val checkpointJson: String? = null
)

@Serializable
data class GameMetadataAndFeatures(
    val gameId: String = "sample-puzzle-v1",
    val title: String = "Kata Nusantara Puzzle",
    val hintsEnabled: Boolean = true,
    val livesEnabled: Boolean = true,
    val initialHints: Int = 2,
    val initialLives: Int = 3,
    val maxLives: Int = 5,
    val levelCompletionReward: RewardBundle = RewardBundle(hints = 1, lives = 0),
    val rewardedAdReward: RewardBundle = RewardBundle(hints = 1, lives = 1),
    val privacyPolicyUrl: String = "https://example.com/privacy"
)

@Serializable
enum class LifeRecoveryMethod {
    REWARDED_AD,
    ACHIEVEMENT_REWARD
}
