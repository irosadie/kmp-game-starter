package com.binarydev.startergame.core.ledger

import com.binarydev.startergame.core.model.LedgerTransaction
import com.binarydev.startergame.core.model.PlayerBalance
import com.binarydev.startergame.core.model.ResourceType
import com.binarydev.startergame.core.model.RewardBundle
import com.binarydev.startergame.core.model.RewardSource

sealed class LedgerOperationResult {
    abstract val balance: PlayerBalance

    data class Applied(
        val transactionId: String,
        override val balance: PlayerBalance,
        val transaction: LedgerTransaction
    ) : LedgerOperationResult()

    data class AlreadyApplied(
        val transactionId: String,
        override val balance: PlayerBalance
    ) : LedgerOperationResult()

    data class Rejected(
        val reason: String,
        override val balance: PlayerBalance
    ) : LedgerOperationResult()
}

data class BundleGrantResult(
    val ledger: RewardLedger,
    val results: List<LedgerOperationResult>
) {
    val anyApplied: Boolean get() = results.any { it is LedgerOperationResult.Applied }
    val allAlreadyApplied: Boolean get() = results.isNotEmpty() && results.all { it is LedgerOperationResult.AlreadyApplied }
}

/**
 * Pure Kotlin Multiplatform idempotent reward and life/hint balance ledger.
 * Guarantees that any transaction ID (reward grant, ad callback, achievement payout, or failed attempt)
 * mutates the player's balance at most once.
 */
data class RewardLedger(
    val balance: PlayerBalance,
    val appliedTransactionIds: Set<String> = emptySet(),
    val transactions: List<LedgerTransaction> = emptyList(),
    val maxLives: Int = Int.MAX_VALUE
) {
    fun hasTransaction(transactionId: String): Boolean = transactionId in appliedTransactionIds

    fun grantReward(
        transactionId: String,
        resourceType: ResourceType,
        amount: Int,
        source: RewardSource,
        timestampEpochMs: Long = 0L
    ): Pair<RewardLedger, LedgerOperationResult> {
        if (transactionId.isBlank()) {
            return this to LedgerOperationResult.Rejected("Transaction ID must not be blank", balance)
        }
        if (amount <= 0) {
            return this to LedgerOperationResult.Rejected("Reward amount must be positive", balance)
        }
        if (transactionId in appliedTransactionIds) {
            return this to LedgerOperationResult.AlreadyApplied(transactionId, balance)
        }

        val updatedBalance = when (resourceType) {
            ResourceType.HINTS -> balance.copy(hints = balance.hints + amount)
            ResourceType.LIVES -> balance.copy(lives = (balance.lives + amount).coerceAtMost(maxLives.coerceAtLeast(balance.lives + amount)))
        }
        val tx = LedgerTransaction(
            transactionId = transactionId,
            resourceType = resourceType,
            delta = amount,
            source = source,
            timestampEpochMs = timestampEpochMs
        )
        val updatedLedger = copy(
            balance = updatedBalance,
            appliedTransactionIds = appliedTransactionIds + transactionId,
            transactions = transactions + tx
        )
        return updatedLedger to LedgerOperationResult.Applied(transactionId, updatedBalance, tx)
    }

    fun grantBundle(
        baseTransactionId: String,
        bundle: RewardBundle,
        source: RewardSource,
        timestampEpochMs: Long = 0L
    ): BundleGrantResult {
        var currentLedger = this
        val results = mutableListOf<LedgerOperationResult>()

        if (bundle.hints > 0) {
            val (nextLedger, res) = currentLedger.grantReward(
                transactionId = "$baseTransactionId:hints",
                resourceType = ResourceType.HINTS,
                amount = bundle.hints,
                source = source,
                timestampEpochMs = timestampEpochMs
            )
            currentLedger = nextLedger
            results += res
        }
        if (bundle.lives > 0) {
            val (nextLedger, res) = currentLedger.grantReward(
                transactionId = "$baseTransactionId:lives",
                resourceType = ResourceType.LIVES,
                amount = bundle.lives,
                source = source,
                timestampEpochMs = timestampEpochMs
            )
            currentLedger = nextLedger
            results += res
        }
        return BundleGrantResult(currentLedger, results)
    }

    fun consumeHint(
        transactionId: String,
        timestampEpochMs: Long = 0L
    ): Pair<RewardLedger, LedgerOperationResult> {
        if (transactionId.isBlank()) {
            return this to LedgerOperationResult.Rejected("Transaction ID must not be blank", balance)
        }
        if (transactionId in appliedTransactionIds) {
            return this to LedgerOperationResult.AlreadyApplied(transactionId, balance)
        }
        if (balance.hints <= 0) {
            return this to LedgerOperationResult.Rejected("No hints available", balance)
        }

        val updatedBalance = balance.copy(hints = balance.hints - 1)
        val tx = LedgerTransaction(
            transactionId = transactionId,
            resourceType = ResourceType.HINTS,
            delta = -1,
            source = RewardSource.HINT_CONSUMED,
            timestampEpochMs = timestampEpochMs
        )
        val updatedLedger = copy(
            balance = updatedBalance,
            appliedTransactionIds = appliedTransactionIds + transactionId,
            transactions = transactions + tx
        )
        return updatedLedger to LedgerOperationResult.Applied(transactionId, updatedBalance, tx)
    }

    fun deductLifeForFailedAttempt(
        attemptId: String,
        timestampEpochMs: Long = 0L
    ): Pair<RewardLedger, LedgerOperationResult> {
        if (attemptId.isBlank()) {
            return this to LedgerOperationResult.Rejected("Attempt ID must not be blank", balance)
        }
        val transactionId = "attempt_fail:$attemptId"
        if (transactionId in appliedTransactionIds) {
            return this to LedgerOperationResult.AlreadyApplied(transactionId, balance)
        }
        if (balance.lives <= 0) {
            return this to LedgerOperationResult.Rejected("No lives remaining", balance)
        }

        val updatedBalance = balance.copy(lives = balance.lives - 1)
        val tx = LedgerTransaction(
            transactionId = transactionId,
            resourceType = ResourceType.LIVES,
            delta = -1,
            source = RewardSource.ATTEMPT_FAILED,
            timestampEpochMs = timestampEpochMs
        )
        val updatedLedger = copy(
            balance = updatedBalance,
            appliedTransactionIds = appliedTransactionIds + transactionId,
            transactions = transactions + tx
        )
        return updatedLedger to LedgerOperationResult.Applied(transactionId, updatedBalance, tx)
    }
}
