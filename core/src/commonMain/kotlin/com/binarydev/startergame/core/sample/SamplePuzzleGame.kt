package com.binarydev.startergame.core.sample

import com.binarydev.startergame.core.achievement.AchievementDefinition
import com.binarydev.startergame.core.achievement.AchievementEvaluator
import com.binarydev.startergame.core.engine.HintApplicationResult
import com.binarydev.startergame.core.engine.LevelOutcome
import com.binarydev.startergame.core.engine.LevelRuleEngine
import com.binarydev.startergame.core.model.GameMetadataAndFeatures
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class SamplePuzzleLevel(
    val id: String,
    val number: Int,
    val title: String,
    val clue: String,
    val targetSequence: List<String>,
    val scrambledChoices: List<String>,
    val maxMistakes: Int = 2,
    val baseScore: Int = 1000
)

@Serializable
data class SamplePuzzleState(
    val levelId: String,
    val attemptId: String,
    val clue: String,
    val targetSequence: List<String>,
    val availableChoices: List<String>,
    val placedSequence: List<String> = emptyList(),
    val mistakes: Int = 0,
    val maxMistakes: Int = 2,
    val hintsUsed: Int = 0,
    val moveCount: Int = 0,
    val baseScore: Int = 1000,
    val lastFeedback: String? = null
) {
    val isComplete: Boolean get() = placedSequence == targetSequence
    val isFailed: Boolean get() = !isComplete && mistakes >= maxMistakes
}

@Serializable
sealed class SamplePuzzleMove {
    @Serializable
    data class PlaceTile(val letter: String) : SamplePuzzleMove()

    @Serializable
    data object ClearPlaced : SamplePuzzleMove()
}

/**
 * Concrete sample puzzle implementation for Android Game Starter v1.
 * Players unscramble letters in order to match the target word according to a clue.
 */
class SamplePuzzleGame(
    override val metadata: GameMetadataAndFeatures = GameMetadataAndFeatures(),
    override val levels: List<SamplePuzzleLevel> = defaultLevels(),
    override val achievements: List<AchievementDefinition> = AchievementEvaluator.defaultDemoAchievements()
) : LevelRuleEngine<SamplePuzzleLevel, SamplePuzzleState, SamplePuzzleMove> {

    private val json = Json { ignoreUnknownKeys = true }

    override fun getLevelId(level: SamplePuzzleLevel): String = level.id

    override fun createInitialState(level: SamplePuzzleLevel, attemptId: String): SamplePuzzleState {
        return SamplePuzzleState(
            levelId = level.id,
            attemptId = attemptId,
            clue = level.clue,
            targetSequence = level.targetSequence,
            availableChoices = level.scrambledChoices,
            placedSequence = emptyList(),
            mistakes = 0,
            maxMistakes = level.maxMistakes,
            hintsUsed = 0,
            moveCount = 0,
            baseScore = level.baseScore,
            lastFeedback = null
        )
    }

    override fun getAttemptId(state: SamplePuzzleState): String = state.attemptId

    override fun getHintsUsedCount(state: SamplePuzzleState): Int = state.hintsUsed

    override fun applyMove(state: SamplePuzzleState, move: SamplePuzzleMove): SamplePuzzleState {
        if (state.isComplete || state.isFailed) {
            return state
        }
        return when (move) {
            is SamplePuzzleMove.ClearPlaced -> state.copy(
                placedSequence = emptyList(),
                lastFeedback = "Susunan huruf direset."
            )
            is SamplePuzzleMove.PlaceTile -> {
                val nextIndex = state.placedSequence.size
                if (nextIndex >= state.targetSequence.size) {
                    return state
                }
                val expectedLetter = state.targetSequence[nextIndex]
                if (move.letter.equals(expectedLetter, ignoreCase = true)) {
                    val newPlaced = state.placedSequence + expectedLetter
                    state.copy(
                        placedSequence = newPlaced,
                        moveCount = state.moveCount + 1,
                        lastFeedback = if (newPlaced == state.targetSequence) {
                            "Tepat! Kata '${newPlaced.joinToString("")}' berhasil disusun."
                        } else {
                            "Huruf '$expectedLetter' benar!"
                        }
                    )
                } else {
                    val newMistakes = state.mistakes + 1
                    state.copy(
                        mistakes = newMistakes,
                        moveCount = state.moveCount + 1,
                        lastFeedback = "Huruf '${move.letter}' kurang tepat ($newMistakes/${state.maxMistakes} salah)."
                    )
                }
            }
        }
    }

    override fun applyHint(state: SamplePuzzleState): HintApplicationResult<SamplePuzzleState> {
        if (state.isComplete) {
            return HintApplicationResult.NotApplicable("Puzzle sudah selesai.")
        }
        if (state.isFailed) {
            return HintApplicationResult.NotApplicable("Percobaan sudah gagal.")
        }
        val nextIndex = state.placedSequence.size
        if (nextIndex >= state.targetSequence.size) {
            return HintApplicationResult.NotApplicable("Semua slot sudah terisi.")
        }
        val revealedLetter = state.targetSequence[nextIndex]
        val newPlaced = state.placedSequence + revealedLetter
        val description = "Hint: Huruf ke-${nextIndex + 1} adalah '$revealedLetter'"
        val updatedState = state.copy(
            placedSequence = newPlaced,
            hintsUsed = state.hintsUsed + 1,
            moveCount = state.moveCount + 1,
            lastFeedback = description
        )
        return HintApplicationResult.Applied(
            newState = updatedState,
            hintDescription = description
        )
    }

    override fun evaluateOutcome(state: SamplePuzzleState): LevelOutcome {
        return when {
            state.isComplete -> {
                val score = (state.baseScore - (state.mistakes * 150) - (state.hintsUsed * 100))
                    .coerceAtLeast(100)
                LevelOutcome.Won(
                    score = score,
                    validatorPayload = mapOf(
                        "levelId" to state.levelId,
                        "attemptId" to state.attemptId,
                        "word" to state.placedSequence.joinToString(""),
                        "moves" to state.moveCount.toString(),
                        "mistakes" to state.mistakes.toString(),
                        "hintsUsed" to state.hintsUsed.toString()
                    )
                )
            }
            state.isFailed -> LevelOutcome.Lost(
                attemptId = state.attemptId,
                reason = "Batas kesalahan maksimal (${state.maxMistakes}) tercapai."
            )
            else -> LevelOutcome.InProgress
        }
    }

    override fun serializeCheckpoint(state: SamplePuzzleState): String {
        return json.encodeToString(state)
    }

    override fun deserializeCheckpoint(level: SamplePuzzleLevel, json: String): SamplePuzzleState {
        return this.json.decodeFromString<SamplePuzzleState>(json)
    }

    companion object {
        fun defaultLevels(): List<SamplePuzzleLevel> = listOf(
            SamplePuzzleLevel(
                id = "level-1",
                number = 1,
                title = "Level 1 · Bahasa Pemrograman",
                clue = "Bahasa pemrograman modern utama untuk Android & Multiplatform (6 huruf)",
                targetSequence = listOf("K", "O", "T", "L", "I", "N"),
                scrambledChoices = listOf("T", "K", "N", "O", "I", "L"),
                maxMistakes = 2,
                baseScore = 1000
            ),
            SamplePuzzleLevel(
                id = "level-2",
                number = 2,
                title = "Level 2 · Komponen UI",
                clue = "Toolkit UI deklaratif modern di Android (7 huruf)",
                targetSequence = listOf("C", "O", "M", "P", "O", "S", "E"),
                scrambledChoices = listOf("P", "C", "E", "O", "M", "S", "O"),
                maxMistakes = 2,
                baseScore = 1200
            )
        )
    }
}
