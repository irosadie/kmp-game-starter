package com.binarydev.startergame.data.repository

import com.binarydev.startergame.core.contracts.LeaderboardRepository
import com.binarydev.startergame.core.contracts.LeaderboardState
import com.binarydev.startergame.core.contracts.ProgressRepository
import com.binarydev.startergame.core.contracts.ScoreSubmissionRequest
import com.binarydev.startergame.core.contracts.ScoreSubmissionResponse
import com.binarydev.startergame.data.remote.GoBackendApiClient
import com.binarydev.startergame.data.remote.MockGoBackendApiClient

/**
 * Simulated Leaderboard adapter for Demo mode backed by MockGoBackendApiClient (`GET /v1/games/{gameId}/leaderboard`
 * and `POST /v1/games/{gameId}/scores`), tagging rankings with `isDemo = true` ("Demo").
 */
class SimulatedLeaderboardAdapter(
    private val progressRepository: ProgressRepository,
    private val mockBackendApiClient: GoBackendApiClient = MockGoBackendApiClient(
        gameId = "sample-puzzle-v1",
        progressRepositoryProvider = { progressRepository }
    ),
    private val gameId: String = "sample-puzzle-v1"
) : LeaderboardRepository {

    override suspend fun fetchLeaderboard(): LeaderboardState = mockBackendApiClient.getLeaderboard(gameId).fold(
        onSuccess = { response ->
            if (response.entries.isEmpty()) {
                LeaderboardState.Empty(
                    isDemo = true,
                    message = "Mock BE API (`GET /v1/games/$gameId/leaderboard`): Belum ada data ranking."
                )
            } else {
                LeaderboardState.Loaded(
                    isDemo = true,
                    entries = response.entries,
                    bannerMessage = "GET /v1/games/$gameId/leaderboard · Simulasi Response API BE (Mockup JSON)"
                )
            }
        },
        onFailure = { err ->
            LeaderboardState.Unavailable(
                message = "Simulasi API BE Gagal (${err.message}). Skor lokal tetap tersimpan di Room."
            )
        }
    )

    override suspend fun submitScore(request: ScoreSubmissionRequest): Result<ScoreSubmissionResponse> = mockBackendApiClient.submitScore(
        gameId = gameId,
        sessionToken = null,
        request = request
    )
}

/**
 * Connected Leaderboard adapter using Go backend API (`GET /v1/games/{gameId}/leaderboard` and `POST /v1/games/{gameId}/scores`).
 * Only displays server-accepted scores and clearly reports empty or unavailable service states.
 */
class ConnectedLeaderboardRepository(
    private val gameId: String,
    private val apiClient: GoBackendApiClient,
    private val sessionTokenProvider: () -> String? = { null }
) : LeaderboardRepository {

    override suspend fun fetchLeaderboard(): LeaderboardState = apiClient.getLeaderboard(gameId).fold(
        onSuccess = { response ->
            if (response.entries.isEmpty()) {
                LeaderboardState.Empty(
                    isDemo = response.isDemo,
                    message = "Leaderboard global masih kosong. Jadilah yang pertama mengirim skor terverifikasi!"
                )
            } else {
                LeaderboardState.Loaded(
                    isDemo = response.isDemo,
                    entries = response.entries,
                    bannerMessage = "GET /v1/games/$gameId/leaderboard · Hanya menampilkan skor yang diterima server."
                )
            }
        },
        onFailure = {
            LeaderboardState.Unavailable(
                message = "Layanan leaderboard global belum tersedia. Skor lokal Anda tetap tersimpan dan akan dikirim saat server aktif."
            )
        }
    )

    override suspend fun submitScore(request: ScoreSubmissionRequest): Result<ScoreSubmissionResponse> = apiClient.submitScore(
        gameId = gameId,
        sessionToken = sessionTokenProvider(),
        request = request
    )
}
