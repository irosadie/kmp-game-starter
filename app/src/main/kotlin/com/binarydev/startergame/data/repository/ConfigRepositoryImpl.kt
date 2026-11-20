package com.binarydev.startergame.data.repository

import com.binarydev.startergame.core.contracts.ActiveConfigState
import com.binarydev.startergame.core.contracts.AdFormatConfig
import com.binarydev.startergame.core.contracts.ConfigRefreshSignal
import com.binarydev.startergame.core.contracts.ConfigRepository
import com.binarydev.startergame.core.contracts.ConfigSource
import com.binarydev.startergame.core.contracts.RemoteGameConfigDto
import com.binarydev.startergame.core.model.GameMetadataAndFeatures
import com.binarydev.startergame.data.preferences.ConfigCacheStore
import com.binarydev.startergame.data.remote.GoBackendApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Simulated Remote Config provider for Demo mode so developers can trigger config changes
 * (enabling/disabling hints, lives, ads, or simulating remote failure to test cache & safe ads-off fallback)
 * without a running Go server.
 */
class SimulatedRemoteConfigAdapter(
    private val metadata: GameMetadataAndFeatures
) {
    var shouldSimulateNetworkFailure: Boolean = false
    var simulatedConfig: RemoteGameConfigDto = RemoteGameConfigDto(
        gameId = metadata.gameId,
        configVersion = 1,
        ttlSeconds = 3600L,
        hintsEnabled = metadata.hintsEnabled,
        livesEnabled = metadata.livesEnabled,
        rewardedAd = AdFormatConfig(enabled = true, maxImpressionsPerSession = 10),
        interstitialAd = AdFormatConfig(enabled = false, maxImpressionsPerSession = 0),
        bannerAd = AdFormatConfig(enabled = true, maxImpressionsPerSession = 100),
        rewardedAdGrant = metadata.rewardedAdReward,
        levelCompletionGrant = metadata.levelCompletionReward
    )

    fun fetchSimulatedConfig(): Result<RemoteGameConfigDto> = if (shouldSimulateNetworkFailure) {
        Result.failure(IllegalStateException("Simulasi: API konfigurasi Go tidak dapat dihubungi"))
    } else {
        Result.success(simulatedConfig)
    }
}

/**
 * Implements the 3-tier remote configuration resolution pipeline:
 * 1. Pulls from remote API (or SimulatedRemoteConfigAdapter in demo mode) on refresh signal or when cache is expired.
 * 2. On API failure, falls back to locally cached configuration if still valid within TTL.
 * 3. If cache is expired or missing, falls back to safe default with all ads disabled while local gameplay continues.
 */
class ConfigRepositoryImpl(
    private val metadata: GameMetadataAndFeatures,
    private val cacheStore: ConfigCacheStore,
    private val backendApiClient: GoBackendApiClient? = null,
    private val simulatedAdapter: SimulatedRemoteConfigAdapter? = null
) : ConfigRepository {

    private val _activeConfig = MutableStateFlow(
        ActiveConfigState(
            config = RemoteGameConfigDto.safeAdsOffDefault(metadata),
            source = ConfigSource.SAFE_DEFAULT_ADS_OFF,
            lastUpdatedEpochMs = 0L,
            statusMessage = "Konfigurasi awal default (iklan mati)."
        )
    )
    override val activeConfig: Flow<ActiveConfigState> = _activeConfig.asStateFlow()

    override suspend fun getActiveConfig(): ActiveConfigState = _activeConfig.value

    override suspend fun refreshIfNeeded(currentTimeEpochMs: Long): ActiveConfigState {
        val cached = cacheStore.getCachedConfig()
        if (cached != null && cached.isValidAt(currentTimeEpochMs)) {
            val state = ActiveConfigState(
                config = cached.config,
                source = ConfigSource.LOCAL_CACHE,
                lastUpdatedEpochMs = cached.fetchedAtEpochMs,
                statusMessage = "Memakai konfigurasi cache lokal (v${cached.config.configVersion})."
            )
            _activeConfig.value = state
            return state
        }
        return pullRemoteWithFallback(currentTimeEpochMs, reason = "Cache kedaluwarsa atau belum ada")
    }

    override suspend fun onRefreshSignal(
        signal: ConfigRefreshSignal,
        currentTimeEpochMs: Long
    ): ActiveConfigState {
        if (signal.gameId.isNotBlank() && signal.gameId != metadata.gameId) {
            return _activeConfig.value
        }
        return pullRemoteWithFallback(
            currentTimeEpochMs = currentTimeEpochMs,
            reason = "Sinyal refresh diterima (target v${signal.targetVersion ?: "latest"})"
        )
    }

    private suspend fun pullRemoteWithFallback(
        currentTimeEpochMs: Long,
        reason: String
    ): ActiveConfigState {
        val remoteResult = when {
            simulatedAdapter != null -> simulatedAdapter.fetchSimulatedConfig()
            backendApiClient != null -> backendApiClient.getGameConfig(metadata.gameId)
            else -> Result.failure(IllegalStateException("Tidak ada sumber konfigurasi remote"))
        }

        return remoteResult.fold(
            onSuccess = { remoteDto ->
                cacheStore.saveCachedConfig(remoteDto, currentTimeEpochMs)
                val state = ActiveConfigState(
                    config = remoteDto,
                    source = ConfigSource.REMOTE_LIVE,
                    lastUpdatedEpochMs = currentTimeEpochMs,
                    statusMessage = "Konfigurasi v${remoteDto.configVersion} aktif ($reason)."
                )
                _activeConfig.value = state
                state
            },
            onFailure = {
                val cached = cacheStore.getCachedConfig()
                if (cached != null && cached.isValidAt(currentTimeEpochMs)) {
                    val cachedState = ActiveConfigState(
                        config = cached.config,
                        source = ConfigSource.LOCAL_CACHE,
                        lastUpdatedEpochMs = cached.fetchedAtEpochMs,
                        statusMessage = "API gagal dihubungi; memakai cache konfigurasi v${cached.config.configVersion} yang masih valid."
                    )
                    _activeConfig.value = cachedState
                    cachedState
                } else {
                    val safeDefault = RemoteGameConfigDto.safeAdsOffDefault(metadata)
                    val fallbackState = ActiveConfigState(
                        config = safeDefault,
                        source = ConfigSource.SAFE_DEFAULT_ADS_OFF,
                        lastUpdatedEpochMs = currentTimeEpochMs,
                        statusMessage = "API gagal & cache kedaluwarsa; kembali ke default aman (semua iklan mati, gameplay lokal tetap jalan)."
                    )
                    _activeConfig.value = fallbackState
                    fallbackState
                }
            }
        )
    }
}
