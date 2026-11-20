package com.binarydev.startergame.data.repository

import com.binarydev.startergame.core.achievement.AchievementEvaluator
import com.binarydev.startergame.core.achievement.AchievementState
import com.binarydev.startergame.core.contracts.PlayerProgressSnapshotDto
import com.binarydev.startergame.core.contracts.ProgressEventsBatchRequest
import com.binarydev.startergame.core.contracts.ProgressRepository
import com.binarydev.startergame.core.contracts.ProgressSyncEventDto
import com.binarydev.startergame.core.engine.LevelRuleEngine
import com.binarydev.startergame.core.ledger.LedgerOperationResult
import com.binarydev.startergame.core.ledger.RewardLedger
import com.binarydev.startergame.core.model.LevelProgress
import com.binarydev.startergame.core.model.PlayerBalance
import com.binarydev.startergame.core.model.ResourceType
import com.binarydev.startergame.core.model.RewardBundle
import com.binarydev.startergame.core.model.RewardSource
import com.binarydev.startergame.data.local.AchievementProgressEntity
import com.binarydev.startergame.data.local.LedgerTransactionEntity
import com.binarydev.startergame.data.local.LevelProgressEntity
import com.binarydev.startergame.data.local.PendingSyncEventEntity
import com.binarydev.startergame.data.local.PlayerProfileEntity
import com.binarydev.startergame.data.local.StarterGameDao
import com.binarydev.startergame.data.remote.GoBackendApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RoomProgressRepository<LevelData, State, Move>(
    private val dao: StarterGameDao,
    private val ruleEngine: LevelRuleEngine<LevelData, State, Move>,
    private val backendApiClient: GoBackendApiClient? = null,
    private val sessionTokenProvider: () -> String? = { null }
) : ProgressRepository {

    private val mutex = Mutex()
    private val achievementEvaluator = AchievementEvaluator(ruleEngine.achievements)

    override val balanceFlow: Flow<PlayerBalance> = dao.observeProfile().map { entity ->
        if (entity != null) {
            PlayerBalance(hints = entity.hintBalance, lives = entity.lifeBalance)
        } else {
            PlayerBalance(
                hints = ruleEngine.metadata.initialHints,
                lives = ruleEngine.metadata.initialLives
            )
        }
    }

    override val levelsFlow: Flow<Map<String, LevelProgress>> = dao.observeLevels().map { entities ->
        if (entities.isEmpty()) {
            defaultInitialLevels()
        } else {
            entities.associate { entity ->
                entity.levelId to LevelProgress(
                    levelId = entity.levelId,
                    unlocked = entity.unlocked,
                    completed = entity.completed,
                    bestScore = entity.bestScore,
                    checkpointJson = entity.checkpointJson
                )
            }
        }
    }

    override val achievementsFlow: Flow<List<AchievementState>> = dao.observeAchievements().map { entities ->
        if (entities.isEmpty()) {
            achievementEvaluator.initialStates()
        } else {
            entities.map { it.toDomain() }
        }
    }

    suspend fun ensureInitialized() = mutex.withLock {
        ensureInitializedLocked()
    }

    private suspend fun ensureInitializedLocked(): PlayerProfileEntity {
        val existingProfile = dao.getProfile()
        if (existingProfile != null) {
            return existingProfile
        }

        val initialProfile = PlayerProfileEntity(
            profileId = DEFAULT_GUEST_PROFILE_ID,
            isGuest = true,
            displayName = "Guest Player",
            email = null,
            linkedPlayerId = null,
            hintBalance = ruleEngine.metadata.initialHints,
            lifeBalance = ruleEngine.metadata.initialLives,
            serverRevision = 0L
        )
        dao.upsertProfile(initialProfile)

        val existingLevels = dao.getLevels()
        if (existingLevels.isEmpty()) {
            val defaultLevels = defaultInitialLevels().values.map { it.toEntity() }
            dao.upsertLevels(defaultLevels)
        }

        val existingAchievements = dao.getAchievements()
        if (existingAchievements.isEmpty()) {
            val defaultAchievements = achievementEvaluator.initialStates().map { it.toEntity() }
            dao.upsertAchievements(defaultAchievements)
        }

        return initialProfile
    }

    override suspend fun getProfileId(): String = mutex.withLock {
        ensureInitializedLocked().profileId
    }

    override suspend fun getBalance(): PlayerBalance = mutex.withLock {
        val profile = ensureInitializedLocked()
        PlayerBalance(hints = profile.hintBalance, lives = profile.lifeBalance)
    }

    override suspend fun getLevels(): Map<String, LevelProgress> = mutex.withLock {
        ensureInitializedLocked()
        val entities = dao.getLevels()
        if (entities.isEmpty()) {
            defaultInitialLevels()
        } else {
            entities.associate {
                it.levelId to LevelProgress(
                    levelId = it.levelId,
                    unlocked = it.unlocked,
                    completed = it.completed,
                    bestScore = it.bestScore,
                    checkpointJson = it.checkpointJson
                )
            }
        }
    }

    override suspend fun getAchievements(): List<AchievementState> = mutex.withLock {
        ensureInitializedLocked()
        val entities = dao.getAchievements()
        if (entities.isEmpty()) {
            achievementEvaluator.initialStates()
        } else {
            entities.map { it.toDomain() }
        }
    }

    suspend fun loadCurrentRewardLedger(): RewardLedger = mutex.withLock {
        loadRewardLedgerLocked()
    }

    private suspend fun loadRewardLedgerLocked(): RewardLedger {
        val profile = ensureInitializedLocked()
        val txs = dao.getLedgerTransactions()
        return RewardLedger(
            balance = PlayerBalance(hints = profile.hintBalance, lives = profile.lifeBalance),
            appliedTransactionIds = txs.map { it.transactionId }.toSet(),
            maxLives = ruleEngine.metadata.maxLives
        )
    }

    override suspend fun saveGameState(
        levels: Map<String, LevelProgress>,
        achievements: List<AchievementState>,
        syncEvent: ProgressSyncEventDto?
    ) = mutex.withLock {
        ensureInitializedLocked()
        dao.upsertLevels(levels.values.map { it.toEntity() })
        dao.upsertAchievements(achievements.map { it.toEntity() })
        if (syncEvent != null) {
            dao.insertPendingSyncEvent(
                PendingSyncEventEntity(
                    eventId = syncEvent.eventId,
                    eventType = syncEvent.eventType,
                    payloadJson = syncEvent.payloadJson,
                    createdAtEpochMs = syncEvent.clientTimestampEpochMs
                )
            )
        }
    }

    suspend fun persistLedgerResults(
        updatedLedger: RewardLedger,
        timestampEpochMs: Long = System.currentTimeMillis()
    ) = mutex.withLock {
        val profile = ensureInitializedLocked()
        for (tx in updatedLedger.transactions) {
            dao.insertLedgerTransactionIgnoreConflict(
                LedgerTransactionEntity(
                    transactionId = tx.transactionId,
                    resourceType = tx.resourceType.name,
                    delta = tx.delta,
                    source = tx.source.name,
                    createdAtEpochMs = if (tx.timestampEpochMs > 0L) tx.timestampEpochMs else timestampEpochMs
                )
            )
        }
        dao.upsertProfile(
            profile.copy(
                hintBalance = updatedLedger.balance.hints,
                lifeBalance = updatedLedger.balance.lives
            )
        )
    }

    override suspend fun grantRewardIdempotent(
        transactionId: String,
        bundle: RewardBundle,
        sourceName: String,
        timestampEpochMs: Long
    ): Boolean = mutex.withLock {
        val profile = ensureInitializedLocked()
        val ledger = loadRewardLedgerLocked()
        val source = runCatching { RewardSource.valueOf(sourceName) }.getOrDefault(RewardSource.REWARDED_AD)
        val grantResult = ledger.grantBundle(
            baseTransactionId = transactionId,
            bundle = bundle,
            source = source,
            timestampEpochMs = timestampEpochMs
        )
        if (!grantResult.anyApplied) {
            return@withLock false
        }
        for (res in grantResult.results) {
            if (res is LedgerOperationResult.Applied) {
                dao.insertLedgerTransactionIgnoreConflict(
                    LedgerTransactionEntity(
                        transactionId = res.transactionId,
                        resourceType = res.transaction.resourceType.name,
                        delta = res.transaction.delta,
                        source = res.transaction.source.name,
                        createdAtEpochMs = timestampEpochMs
                    )
                )
            }
        }
        dao.upsertProfile(
            profile.copy(
                hintBalance = grantResult.ledger.balance.hints,
                lifeBalance = grantResult.ledger.balance.lives
            )
        )
        true
    }

    override suspend fun consumeHintIdempotent(
        transactionId: String,
        timestampEpochMs: Long
    ): Boolean = mutex.withLock {
        val profile = ensureInitializedLocked()
        val ledger = loadRewardLedgerLocked()
        val (updated, res) = ledger.consumeHint(transactionId, timestampEpochMs)
        if (res !is LedgerOperationResult.Applied) {
            return@withLock false
        }
        dao.insertLedgerTransactionIgnoreConflict(
            LedgerTransactionEntity(
                transactionId = res.transactionId,
                resourceType = ResourceType.HINTS.name,
                delta = -1,
                source = RewardSource.HINT_CONSUMED.name,
                createdAtEpochMs = timestampEpochMs
            )
        )
        dao.upsertProfile(
            profile.copy(
                hintBalance = updated.balance.hints,
                lifeBalance = updated.balance.lives
            )
        )
        true
    }

    override suspend fun deductLifeForFailedAttemptIdempotent(
        attemptId: String,
        timestampEpochMs: Long
    ): Boolean = mutex.withLock {
        val profile = ensureInitializedLocked()
        val ledger = loadRewardLedgerLocked()
        val (updated, res) = ledger.deductLifeForFailedAttempt(attemptId, timestampEpochMs)
        if (res !is LedgerOperationResult.Applied) {
            return@withLock false
        }
        dao.insertLedgerTransactionIgnoreConflict(
            LedgerTransactionEntity(
                transactionId = res.transactionId,
                resourceType = ResourceType.LIVES.name,
                delta = -1,
                source = RewardSource.ATTEMPT_FAILED.name,
                createdAtEpochMs = timestampEpochMs
            )
        )
        dao.upsertProfile(
            profile.copy(
                hintBalance = updated.balance.hints,
                lifeBalance = updated.balance.lives
            )
        )
        true
    }

    override suspend fun mergeGuestToAuthenticatedAccount(
        playerId: String
    ): Result<PlayerProgressSnapshotDto> = mutex.withLock {
        val profile = ensureInitializedLocked()
        val api = backendApiClient
            ?: return@withLock Result.failure(IllegalStateException("Backend Go belum dikonfigurasi"))
        val sessionToken = sessionTokenProvider()
            ?: return@withLock Result.failure(IllegalStateException("Sesi autentikasi belum tersedia"))

        val pendingEvents = dao.getPendingSyncEvents().map {
            ProgressSyncEventDto(
                eventId = it.eventId,
                eventType = it.eventType,
                payloadJson = it.payloadJson,
                clientTimestampEpochMs = it.createdAtEpochMs
            )
        }
        val mergeEventId = "guest_merge:${profile.profileId}:$playerId"
        val mergeEvents = if (pendingEvents.any { it.eventId == mergeEventId }) {
            pendingEvents
        } else {
            listOf(
                ProgressSyncEventDto(
                    eventId = mergeEventId,
                    eventType = "GUEST_MERGE",
                    payloadJson = """{"guestProfileId":"${profile.profileId}","hints":${profile.hintBalance},"lives":${profile.lifeBalance}}""",
                    clientTimestampEpochMs = System.currentTimeMillis()
                )
            ) + pendingEvents
        }

        val batchResponse = api.postProgressEvents(
            gameId = ruleEngine.metadata.gameId,
            sessionToken = sessionToken,
            request = ProgressEventsBatchRequest(
                playerId = playerId,
                baseServerRevision = profile.serverRevision,
                isGuestMerge = true,
                events = mergeEvents
            )
        )

        batchResponse.map { resp ->
            val acceptedIds = resp.eventStatuses.map { it.eventId }
            if (acceptedIds.isNotEmpty()) {
                dao.deletePendingSyncEvents(acceptedIds)
            }
            applyServerSnapshotLocked(profile, playerId, resp.mergedSnapshot)
            resp.mergedSnapshot
        }
    }

    override suspend fun flushPendingSyncEvents(): Result<Int> = mutex.withLock {
        val profile = ensureInitializedLocked()
        val api = backendApiClient
            ?: return@withLock Result.failure(IllegalStateException("Backend Go belum dikonfigurasi"))
        val sessionToken = sessionTokenProvider()
            ?: return@withLock Result.failure(IllegalStateException("Sesi belum aktif"))

        val pending = dao.getPendingSyncEvents()
        if (pending.isEmpty()) {
            return@withLock Result.success(0)
        }

        val dtoEvents = pending.map {
            ProgressSyncEventDto(
                eventId = it.eventId,
                eventType = it.eventType,
                payloadJson = it.payloadJson,
                clientTimestampEpochMs = it.createdAtEpochMs
            )
        }
        val result = api.postProgressEvents(
            gameId = ruleEngine.metadata.gameId,
            sessionToken = sessionToken,
            request = ProgressEventsBatchRequest(
                playerId = profile.linkedPlayerId ?: profile.profileId,
                baseServerRevision = profile.serverRevision,
                isGuestMerge = false,
                events = dtoEvents
            )
        )

        result.map { resp ->
            val processedIds = resp.eventStatuses.map { it.eventId }
            if (processedIds.isNotEmpty()) {
                dao.deletePendingSyncEvents(processedIds)
            }
            applyServerSnapshotLocked(
                profile = profile,
                playerId = profile.linkedPlayerId ?: profile.profileId,
                snapshot = resp.mergedSnapshot
            )
            processedIds.size
        }
    }

    private suspend fun applyServerSnapshotLocked(
        profile: PlayerProfileEntity,
        playerId: String,
        snapshot: PlayerProgressSnapshotDto
    ) {
        dao.upsertProfile(
            profile.copy(
                isGuest = false,
                linkedPlayerId = playerId,
                hintBalance = snapshot.balance.hints,
                lifeBalance = snapshot.balance.lives,
                serverRevision = snapshot.serverRevision
            )
        )
        if (snapshot.levels.isNotEmpty()) {
            dao.upsertLevels(snapshot.levels.map { it.toEntity() })
        }
        if (snapshot.achievements.isNotEmpty()) {
            dao.upsertAchievements(snapshot.achievements.map { it.toEntity() })
        }
    }

    private fun defaultInitialLevels(): Map<String, LevelProgress> {
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

    private fun LevelProgress.toEntity() = LevelProgressEntity(
        levelId = levelId,
        unlocked = unlocked,
        completed = completed,
        bestScore = bestScore,
        checkpointJson = checkpointJson
    )

    private fun AchievementState.toEntity() = AchievementProgressEntity(
        achievementId = id,
        title = title,
        description = description,
        targetValue = targetValue,
        currentProgress = currentProgress,
        unlocked = unlocked,
        rewardHints = reward.hints,
        rewardLives = reward.lives,
        rewardTransactionId = rewardTransactionId
    )

    private fun AchievementProgressEntity.toDomain() = AchievementState(
        id = achievementId,
        title = title,
        description = description,
        targetValue = targetValue,
        currentProgress = currentProgress,
        unlocked = unlocked,
        reward = RewardBundle(hints = rewardHints, lives = rewardLives),
        rewardTransactionId = rewardTransactionId
    )

    companion object {
        const val DEFAULT_GUEST_PROFILE_ID = "guest-local-profile"
    }
}
