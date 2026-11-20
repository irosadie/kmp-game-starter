package com.binarydev.startergame.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.binarydev.startergame.core.contracts.DevicePreferences
import com.binarydev.startergame.core.contracts.DevicePreferencesRepository
import com.binarydev.startergame.core.contracts.RemoteGameConfigDto
import com.binarydev.startergame.core.contracts.SubscriptionEntitlementState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

data class CachedRemoteConfig(
    val config: RemoteGameConfigDto,
    val fetchedAtEpochMs: Long
) {
    fun isValidAt(currentTimeEpochMs: Long): Boolean {
        if (config.ttlSeconds <= 0L) return false
        val expiresAt = fetchedAtEpochMs + (config.ttlSeconds * 1000L)
        return currentTimeEpochMs <= expiresAt
    }
}

interface ConfigCacheStore {
    suspend fun getCachedConfig(): CachedRemoteConfig?
    suspend fun saveCachedConfig(config: RemoteGameConfigDto, fetchedAtEpochMs: Long)
    suspend fun clearCachedConfig()
}

class DataStoreDevicePreferencesRepository(
    private val dataStore: DataStore<Preferences>
) : DevicePreferencesRepository, ConfigCacheStore {

    private val json = Json { ignoreUnknownKeys = true }

    override val preferencesFlow: Flow<DevicePreferences> = dataStore.data.map { prefs ->
        DevicePreferences(
            musicEnabled = prefs[KEY_MUSIC_ENABLED] ?: true,
            soundEffectsEnabled = prefs[KEY_SFX_ENABLED] ?: true,
            vibrationEnabled = prefs[KEY_VIBRATION_ENABLED] ?: true,
            installationId = prefs[KEY_INSTALLATION_ID] ?: DEFAULT_INSTALLATION_ID
        )
    }

    override suspend fun getPreferences(): DevicePreferences {
        val current = dataStore.data.first()
        val existingInstallId = current[KEY_INSTALLATION_ID]
        if (existingInstallId.isNullOrBlank()) {
            val generated = "inst-${UUID.randomUUID()}"
            dataStore.edit { it[KEY_INSTALLATION_ID] = generated }
        }
        return preferencesFlow.first()
    }

    override suspend fun setMusicEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_MUSIC_ENABLED] = enabled }
    }

    override suspend fun setSoundEffectsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_SFX_ENABLED] = enabled }
    }

    override suspend fun setVibrationEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_VIBRATION_ENABLED] = enabled }
    }

    override suspend fun getCachedConfig(): CachedRemoteConfig? {
        val prefs = dataStore.data.first()
        val rawJson = prefs[KEY_CACHED_CONFIG_JSON] ?: return null
        val fetchedAt = prefs[KEY_CACHED_CONFIG_FETCHED_AT] ?: 0L
        return runCatching {
            val dto = json.decodeFromString<RemoteGameConfigDto>(rawJson)
            CachedRemoteConfig(dto, fetchedAt)
        }.getOrNull()
    }

    override suspend fun saveCachedConfig(config: RemoteGameConfigDto, fetchedAtEpochMs: Long) {
        dataStore.edit { prefs ->
            prefs[KEY_CACHED_CONFIG_JSON] = json.encodeToString(config)
            prefs[KEY_CACHED_CONFIG_FETCHED_AT] = fetchedAtEpochMs
        }
    }

    override suspend fun clearCachedConfig() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_CACHED_CONFIG_JSON)
            prefs.remove(KEY_CACHED_CONFIG_FETCHED_AT)
        }
    }

    val subscriptionEntitlementFlow: Flow<SubscriptionEntitlementState?> = dataStore.data.map { prefs ->
        val raw = prefs[KEY_SUBSCRIPTION_ENTITLEMENT_JSON] ?: return@map null
        runCatching { json.decodeFromString<SubscriptionEntitlementState>(raw) }.getOrNull()
    }

    suspend fun getSavedSubscriptionEntitlement(): SubscriptionEntitlementState? {
        val prefs = dataStore.data.first()
        val raw = prefs[KEY_SUBSCRIPTION_ENTITLEMENT_JSON] ?: return null
        return runCatching { json.decodeFromString<SubscriptionEntitlementState>(raw) }.getOrNull()
    }

    suspend fun saveSubscriptionEntitlement(state: SubscriptionEntitlementState) {
        dataStore.edit { prefs ->
            prefs[KEY_SUBSCRIPTION_ENTITLEMENT_JSON] = json.encodeToString(state)
        }
    }

    companion object {
        private const val DEFAULT_INSTALLATION_ID = "local-install-default"
        private val KEY_MUSIC_ENABLED = booleanPreferencesKey("music_enabled")
        private val KEY_SFX_ENABLED = booleanPreferencesKey("sfx_enabled")
        private val KEY_VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
        private val KEY_INSTALLATION_ID = stringPreferencesKey("installation_id")
        private val KEY_CACHED_CONFIG_JSON = stringPreferencesKey("cached_config_json")
        private val KEY_CACHED_CONFIG_FETCHED_AT = longPreferencesKey("cached_config_fetched_at_ms")
        private val KEY_SUBSCRIPTION_ENTITLEMENT_JSON = stringPreferencesKey("subscription_entitlement_json")
    }
}
