package com.binarydev.startergame.data.integration

import com.binarydev.startergame.core.contracts.ActiveConfigState
import com.binarydev.startergame.core.contracts.ConfigRefreshSignal
import com.binarydev.startergame.core.contracts.ConfigRepository
import com.binarydev.startergame.core.contracts.DevicePreferencesRepository
import com.binarydev.startergame.core.contracts.PushTokenRegistrationRequest
import com.binarydev.startergame.core.contracts.PushTokenRepository
import com.binarydev.startergame.data.remote.GoBackendApiClient
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Handles `PUT /v1/devices/{installationId}/push-token` registration and account disassociation on logout,
 * and routes FCM config refresh data messages into `ConfigRepository.onRefreshSignal`.
 */
class FcmPushTokenAndRefreshAdapter(
    private val defaultGameId: String,
    private val preferencesRepository: DevicePreferencesRepository,
    private val configRepository: ConfigRepository,
    private val backendApiClient: GoBackendApiClient? = null
) : PushTokenRepository {

    var lastRegisteredToken: String? = null
        private set

    var lastLinkedPlayerId: String? = null
        private set

    private val _lastReceivedPushSummary = MutableStateFlow("Menunggu sinyal push FCM (atau simulasi Mock BE)...")
    val lastReceivedPushSummary: StateFlow<String> = _lastReceivedPushSummary.asStateFlow()

    fun fetchAndRegisterFirebaseTokenIfAvailable(linkedPlayerId: String? = lastLinkedPlayerId) {
        runCatching {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    if (!token.isNullOrBlank()) {
                        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                            registerOrUpdateToken(token, linkedPlayerId)
                        }
                    }
                }
            }
        }
    }

    override suspend fun registerOrUpdateToken(
        fcmToken: String,
        linkedPlayerId: String?
    ): Result<Unit> {
        lastRegisteredToken = fcmToken
        lastLinkedPlayerId = linkedPlayerId
        val installationId = preferencesRepository.getPreferences().installationId
        val api = backendApiClient ?: return Result.success(Unit)
        return api.putPushToken(
            installationId = installationId,
            request = PushTokenRegistrationRequest(
                installationId = installationId,
                fcmToken = fcmToken,
                linkedPlayerId = linkedPlayerId,
                platform = "android"
            )
        )
    }

    override suspend fun clearAccountAssociationOnLogout(): Result<Unit> {
        val currentToken = lastRegisteredToken ?: "unregistered-local-token"
        lastLinkedPlayerId = null
        val installationId = preferencesRepository.getPreferences().installationId
        val api = backendApiClient ?: return Result.success(Unit)
        return api.putPushToken(
            installationId = installationId,
            request = PushTokenRegistrationRequest(
                installationId = installationId,
                fcmToken = currentToken,
                linkedPlayerId = null,
                platform = "android"
            )
        )
    }

    fun parseRefreshSignalFromData(data: Map<String, String>): ConfigRefreshSignal? {
        val type = data["type"] ?: data["action"]
        val hasConfigKeys = data.containsKey("configVersion") || data.containsKey("config_version") || type == "config_refresh"
        if (!hasConfigKeys) {
            return null
        }
        val gameId = data["gameId"] ?: data["game_id"] ?: defaultGameId
        val version = (data["configVersion"] ?: data["config_version"])?.toIntOrNull()
        return ConfigRefreshSignal(
            gameId = gameId,
            targetVersion = version
        )
    }

    suspend fun handleIncomingPushData(
        data: Map<String, String>,
        currentTimeEpochMs: Long = System.currentTimeMillis()
    ): ActiveConfigState? {
        val signal = parseRefreshSignalFromData(data) ?: return null
        _lastReceivedPushSummary.value =
            "FCM Push Diterima: type=${data["type"] ?: "config_refresh"}, gameId=${signal.gameId}, targetVersion=v${signal.targetVersion ?: "latest"}"
        return configRepository.onRefreshSignal(signal, currentTimeEpochMs)
    }
}

class StarterFirebaseMessagingService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val adapter = activeAdapterProvider?.invoke() ?: return
        serviceScope.launch {
            adapter.registerOrUpdateToken(fcmToken = token, linkedPlayerId = adapter.lastLinkedPlayerId)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        if (data.isEmpty()) return
        val adapter = activeAdapterProvider?.invoke() ?: return
        serviceScope.launch {
            adapter.handleIncomingPushData(data)
        }
    }

    companion object {
        var activeAdapterProvider: (() -> FcmPushTokenAndRefreshAdapter?)? = null
    }
}
