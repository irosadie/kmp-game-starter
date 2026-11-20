package com.binarydev.startergame.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "player_profiles")
data class PlayerProfileEntity(
    @PrimaryKey val profileId: String,
    val isGuest: Boolean,
    val displayName: String,
    val email: String? = null,
    val linkedPlayerId: String? = null,
    val hintBalance: Int,
    val lifeBalance: Int,
    val serverRevision: Long = 0L
)

@Entity(tableName = "level_progress")
data class LevelProgressEntity(
    @PrimaryKey val levelId: String,
    val unlocked: Boolean,
    val completed: Boolean,
    val bestScore: Int,
    val checkpointJson: String?
)

@Entity(tableName = "ledger_transactions")
data class LedgerTransactionEntity(
    @PrimaryKey val transactionId: String,
    val resourceType: String,
    val delta: Int,
    val source: String,
    val createdAtEpochMs: Long,
    val synced: Boolean = false
)

@Entity(tableName = "achievement_progress")
data class AchievementProgressEntity(
    @PrimaryKey val achievementId: String,
    val title: String,
    val description: String,
    val targetValue: Int,
    val currentProgress: Int,
    val unlocked: Boolean,
    val rewardHints: Int,
    val rewardLives: Int,
    val rewardTransactionId: String?
)

@Entity(tableName = "pending_sync_events")
data class PendingSyncEventEntity(
    @PrimaryKey val eventId: String,
    val eventType: String,
    val payloadJson: String,
    val createdAtEpochMs: Long
)

@Dao
interface StarterGameDao {
    @Query("SELECT * FROM player_profiles LIMIT 1")
    fun observeProfile(): Flow<PlayerProfileEntity?>

    @Query("SELECT * FROM player_profiles LIMIT 1")
    suspend fun getProfile(): PlayerProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: PlayerProfileEntity)

    @Query("SELECT * FROM level_progress ORDER BY levelId ASC")
    fun observeLevels(): Flow<List<LevelProgressEntity>>

    @Query("SELECT * FROM level_progress ORDER BY levelId ASC")
    suspend fun getLevels(): List<LevelProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLevels(levels: List<LevelProgressEntity>)

    @Query("SELECT * FROM achievement_progress ORDER BY achievementId ASC")
    fun observeAchievements(): Flow<List<AchievementProgressEntity>>

    @Query("SELECT * FROM achievement_progress ORDER BY achievementId ASC")
    suspend fun getAchievements(): List<AchievementProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAchievements(achievements: List<AchievementProgressEntity>)

    @Query("SELECT * FROM ledger_transactions ORDER BY createdAtEpochMs ASC")
    suspend fun getLedgerTransactions(): List<LedgerTransactionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLedgerTransactionIgnoreConflict(tx: LedgerTransactionEntity): Long

    @Query("SELECT * FROM pending_sync_events ORDER BY createdAtEpochMs ASC")
    suspend fun getPendingSyncEvents(): List<PendingSyncEventEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPendingSyncEvent(event: PendingSyncEventEntity): Long

    @Query("DELETE FROM pending_sync_events WHERE eventId IN (:eventIds)")
    suspend fun deletePendingSyncEvents(eventIds: List<String>)
}

@Database(
    entities = [
        PlayerProfileEntity::class,
        LevelProgressEntity::class,
        LedgerTransactionEntity::class,
        AchievementProgressEntity::class,
        PendingSyncEventEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class StarterGameDatabase : RoomDatabase() {
    abstract fun starterGameDao(): StarterGameDao
}
