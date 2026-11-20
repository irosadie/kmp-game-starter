package com.binarydev.startergame.data.remote

import com.binarydev.startergame.core.contracts.AchievementSyncRequestDto
import com.binarydev.startergame.core.contracts.AchievementsResponseDto
import com.binarydev.startergame.core.contracts.AuthSessionResponse
import com.binarydev.startergame.core.contracts.GoogleAuthExchangeRequest
import com.binarydev.startergame.core.contracts.LeaderboardResponseDto
import com.binarydev.startergame.core.contracts.PlayerProgressSnapshotDto
import com.binarydev.startergame.core.contracts.ProgressEventsBatchRequest
import com.binarydev.startergame.core.contracts.ProgressEventsBatchResponse
import com.binarydev.startergame.core.contracts.PushTokenRegistrationRequest
import com.binarydev.startergame.core.contracts.RemoteGameConfigDto
import com.binarydev.startergame.core.contracts.RewardClaimRequestDto
import com.binarydev.startergame.core.contracts.RewardClaimResponseDto
import com.binarydev.startergame.core.contracts.ScoreSubmissionRequest
import com.binarydev.startergame.core.contracts.ScoreSubmissionResponse
import com.binarydev.startergame.core.contracts.SubscriptionVerificationRequestDto
import com.binarydev.startergame.core.contracts.SubscriptionVerificationResponseDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Go Backend v1 HTTP JSON contract client interface:
 * - POST /v1/auth/google
 * - GET  /v1/games/{gameId}/config
 * - GET  /v1/games/{gameId}/progress
 * - POST /v1/games/{gameId}/progress/events
 * - GET  /v1/games/{gameId}/leaderboard
 * - POST /v1/games/{gameId}/scores
 * - POST /v1/games/{gameId}/rewards/claims
 * - PUT  /v1/devices/{installationId}/push-token
 */
interface GoBackendApiClient {
    suspend fun exchangeGoogleToken(request: GoogleAuthExchangeRequest): Result<AuthSessionResponse>
    suspend fun getGameConfig(gameId: String): Result<RemoteGameConfigDto>
    suspend fun getProgressSnapshot(gameId: String, sessionToken: String): Result<PlayerProgressSnapshotDto>
    suspend fun postProgressEvents(
        gameId: String,
        sessionToken: String,
        request: ProgressEventsBatchRequest
    ): Result<ProgressEventsBatchResponse>

    suspend fun getLeaderboard(gameId: String): Result<LeaderboardResponseDto>
    suspend fun submitScore(
        gameId: String,
        sessionToken: String?,
        request: ScoreSubmissionRequest
    ): Result<ScoreSubmissionResponse>

    suspend fun createOrCheckRewardClaim(
        gameId: String,
        sessionToken: String?,
        request: RewardClaimRequestDto
    ): Result<RewardClaimResponseDto>

    suspend fun putPushToken(
        installationId: String,
        request: PushTokenRegistrationRequest
    ): Result<Unit>

    suspend fun getAchievements(
        gameId: String,
        sessionToken: String? = null
    ): Result<AchievementsResponseDto> =
        Result.failure(UnsupportedOperationException("Endpoint GET /v1/games/$gameId/achievements belum dikonfigurasi"))

    suspend fun syncAchievements(
        gameId: String,
        sessionToken: String? = null,
        request: AchievementSyncRequestDto
    ): Result<AchievementsResponseDto> =
        Result.failure(UnsupportedOperationException("Endpoint POST /v1/games/$gameId/achievements/sync belum dikonfigurasi"))

    suspend fun verifySubscription(
        gameId: String,
        sessionToken: String? = null,
        request: SubscriptionVerificationRequestDto
    ): Result<SubscriptionVerificationResponseDto> =
        Result.failure(UnsupportedOperationException("Endpoint POST /v1/games/$gameId/subscriptions/verify belum dikonfigurasi"))
}

class HttpUrlConnectionGoBackendApiClient(
    private val baseUrl: String,
    private val apiKey: String = "",
    private val timeoutMs: Int = 5_000,
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
) : GoBackendApiClient {

    override suspend fun exchangeGoogleToken(request: GoogleAuthExchangeRequest): Result<AuthSessionResponse> =
        executeJsonRequest(
            method = "POST",
            path = "/v1/auth/google",
            requestBody = json.encodeToString(request),
            decodeResponse = { json.decodeFromString(it) }
        )

    override suspend fun getGameConfig(gameId: String): Result<RemoteGameConfigDto> =
        executeJsonRequest(
            method = "GET",
            path = "/v1/games/$gameId/config",
            requestBody = null,
            decodeResponse = { json.decodeFromString(it) }
        )

    override suspend fun getProgressSnapshot(
        gameId: String,
        sessionToken: String
    ): Result<PlayerProgressSnapshotDto> =
        executeJsonRequest(
            method = "GET",
            path = "/v1/games/$gameId/progress",
            sessionToken = sessionToken,
            requestBody = null,
            decodeResponse = { json.decodeFromString(it) }
        )

    override suspend fun postProgressEvents(
        gameId: String,
        sessionToken: String,
        request: ProgressEventsBatchRequest
    ): Result<ProgressEventsBatchResponse> =
        executeJsonRequest(
            method = "POST",
            path = "/v1/games/$gameId/progress/events",
            sessionToken = sessionToken,
            requestBody = json.encodeToString(request),
            decodeResponse = { json.decodeFromString(it) }
        )

    override suspend fun getLeaderboard(gameId: String): Result<LeaderboardResponseDto> =
        executeJsonRequest(
            method = "GET",
            path = "/v1/games/$gameId/leaderboard",
            requestBody = null,
            decodeResponse = { json.decodeFromString(it) }
        )

    override suspend fun submitScore(
        gameId: String,
        sessionToken: String?,
        request: ScoreSubmissionRequest
    ): Result<ScoreSubmissionResponse> =
        executeJsonRequest(
            method = "POST",
            path = "/v1/games/$gameId/scores",
            sessionToken = sessionToken,
            requestBody = json.encodeToString(request),
            decodeResponse = { json.decodeFromString(it) }
        )

    override suspend fun createOrCheckRewardClaim(
        gameId: String,
        sessionToken: String?,
        request: RewardClaimRequestDto
    ): Result<RewardClaimResponseDto> =
        executeJsonRequest(
            method = "POST",
            path = "/v1/games/$gameId/rewards/claims",
            sessionToken = sessionToken,
            requestBody = json.encodeToString(request),
            decodeResponse = { json.decodeFromString(it) }
        )

    override suspend fun putPushToken(
        installationId: String,
        request: PushTokenRegistrationRequest
    ): Result<Unit> =
        executeJsonRequest(
            method = "PUT",
            path = "/v1/devices/$installationId/push-token",
            requestBody = json.encodeToString(request),
            decodeResponse = { }
        )

    override suspend fun getAchievements(
        gameId: String,
        sessionToken: String?
    ): Result<AchievementsResponseDto> =
        executeJsonRequest(
            method = "GET",
            path = "/v1/games/$gameId/achievements",
            sessionToken = sessionToken,
            requestBody = null,
            decodeResponse = { json.decodeFromString(it) }
        )

    override suspend fun syncAchievements(
        gameId: String,
        sessionToken: String?,
        request: AchievementSyncRequestDto
    ): Result<AchievementsResponseDto> =
        executeJsonRequest(
            method = "POST",
            path = "/v1/games/$gameId/achievements/sync",
            sessionToken = sessionToken,
            requestBody = json.encodeToString(request),
            decodeResponse = { json.decodeFromString(it) }
        )

    override suspend fun verifySubscription(
        gameId: String,
        sessionToken: String?,
        request: SubscriptionVerificationRequestDto
    ): Result<SubscriptionVerificationResponseDto> =
        executeJsonRequest(
            method = "POST",
            path = "/v1/games/$gameId/subscriptions/verify",
            sessionToken = sessionToken,
            requestBody = json.encodeToString(request),
            decodeResponse = { json.decodeFromString(it) }
        )

    private suspend fun <T> executeJsonRequest(
        method: String,
        path: String,
        sessionToken: String? = null,
        requestBody: String?,
        decodeResponse: (String) -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanBase = baseUrl.trimEnd('/')
            val conn = (URL("$cleanBase$path").openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/json")
                if (apiKey.isNotBlank()) {
                    setRequestProperty("X-API-Key", apiKey)
                }
                if (!sessionToken.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $sessionToken")
                }
                if (requestBody != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
            }
            try {
                if (requestBody != null) {
                    conn.outputStream.use { out ->
                        out.write(requestBody.toByteArray(Charsets.UTF_8))
                    }
                }
                val code = conn.responseCode
                val body = if (code in 200..299) {
                    conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } else {
                    val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    throw IOException("HTTP $code on $method $path: $err")
                }
                decodeResponse(body)
            } finally {
                conn.disconnect()
            }
        }
    }
}
