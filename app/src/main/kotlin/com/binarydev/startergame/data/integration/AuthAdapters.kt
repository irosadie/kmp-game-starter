package com.binarydev.startergame.data.integration

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.binarydev.startergame.core.contracts.AccountStatus
import com.binarydev.startergame.core.contracts.AuthActionResult
import com.binarydev.startergame.core.contracts.DevicePreferencesRepository
import com.binarydev.startergame.core.contracts.GoogleAuthExchangeRequest
import com.binarydev.startergame.core.contracts.ProgressRepository
import com.binarydev.startergame.core.contracts.PushTokenRepository
import com.binarydev.startergame.core.contracts.SessionRepository
import com.binarydev.startergame.data.local.StarterGameDao
import com.binarydev.startergame.data.remote.GoBackendApiClient
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class GooglePickerResult {
    data class Success(
        val idToken: String,
        val displayName: String?,
        val email: String?
    ) : GooglePickerResult()

    data class Cancelled(
        val message: String = "Pemilihan akun Google dibatalkan."
    ) : GooglePickerResult()

    data class Failure(
        val message: String
    ) : GooglePickerResult()
}

interface GoogleIdTokenProvider {
    suspend fun requestGoogleIdToken(): GooglePickerResult
}

/**
 * Simulated Google account picker used only by automated headless JVM/Robolectric unit tests.
 */
class SimulatedGoogleIdTokenProvider(
    var simulatedResult: GooglePickerResult = GooglePickerResult.Success(
        idToken = "sim-google-id-token-demo",
        displayName = "Demo Google Player",
        email = "demo.player@example.com"
    )
) : GoogleIdTokenProvider {
    override suspend fun requestGoogleIdToken(): GooglePickerResult = simulatedResult
}

/**
 * Production-ready Android Credential Manager implementation for Google Sign-In.
 * Uses `GetSignInWithGoogleOption` (the official Credential Manager option for explicit
 * "Sign in with Google" buttons) with fallback to `GetGoogleIdOption`, returning the real
 * Google ID token from Google Play Services to be exchanged via `POST /v1/auth/google`.
 */
class CredentialManagerAuthAdapter(
    private val contextProvider: () -> Context?,
    private val webClientId: String
) : GoogleIdTokenProvider {

    override suspend fun requestGoogleIdToken(): GooglePickerResult {
        val ctx = contextProvider()
            ?: return GooglePickerResult.Failure("Activity context belum tersedia untuk membuka Credential Manager.")
        if (ctx !is Activity) {
            return GooglePickerResult.Failure("Credential Manager membutuhkan Activity context yang aktif.")
        }
        if (webClientId.isBlank()) {
            return GooglePickerResult.Failure("GOOGLE_WEB_CLIENT_ID belum diisi di file .env.")
        }

        val credentialManager = CredentialManager.create(ctx)

        return try {
            // 1. Explicit "Sign in with Google" button flow (opens native Google Play Services account chooser)
            val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(webClientId)
                .build()
            val signInRequest = GetCredentialRequest.Builder()
                .addCredentialOption(signInWithGoogleOption)
                .build()

            val response = try {
                credentialManager.getCredential(
                    request = signInRequest,
                    context = ctx
                )
            } catch (noCred: NoCredentialException) {
                // 2. Fallback to GetGoogleIdOption with filterByAuthorizedAccounts = false
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(webClientId)
                    .setAutoSelectEnabled(false)
                    .build()
                val fallbackRequest = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()
                credentialManager.getCredential(
                    request = fallbackRequest,
                    context = ctx
                )
            }

            extractGoogleIdToken(response)
        } catch (cancelled: GetCredentialCancellationException) {
            GooglePickerResult.Cancelled("Login Google dibatalkan. Anda tetap bisa lanjut bermain sebagai Guest.")
        } catch (e: GetCredentialException) {
            GooglePickerResult.Failure(
                "Google Sign-In (${e.type}): ${e.errorMessage ?: e.message ?: "Pastikan akun Google sudah login di perangkat & GOOGLE_WEB_CLIENT_ID valid di .env."}"
            )
        } catch (e: Exception) {
            GooglePickerResult.Failure(
                "Gagal membuka Google Sign-In (${e.javaClass.simpleName}): ${e.message ?: "Periksa konfigurasi Google Play Services."}"
            )
        }
    }

    private fun extractGoogleIdToken(response: GetCredentialResponse): GooglePickerResult {
        val credential = response.credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
            return GooglePickerResult.Success(
                idToken = googleIdTokenCredential.idToken,
                displayName = googleIdTokenCredential.displayName,
                email = googleIdTokenCredential.id
            )
        }
        return GooglePickerResult.Failure("Tipe kredensial Google tidak dikenali (${credential.type}).")
    }
}

class SessionRepositoryImpl(
    private val dao: StarterGameDao,
    private val progressRepository: ProgressRepository,
    private val preferencesRepository: DevicePreferencesRepository,
    private val googleIdTokenProvider: GoogleIdTokenProvider,
    private val backendApiClient: GoBackendApiClient? = null,
    private val pushTokenRepository: PushTokenRepository? = null
) : SessionRepository {

    var currentSessionToken: String? = null
        private set

    private val _accountStatus = MutableStateFlow(
        AccountStatus(
            profileId = "guest-local-profile",
            isGuest = true,
            displayName = "Guest Player",
            isBackendSyncActive = false,
            syncStatusMessage = "Bermain sebagai Guest. Progress tersimpan lokal di perangkat."
        )
    )
    override val accountStatus: Flow<AccountStatus> = _accountStatus.asStateFlow()

    override suspend fun getCurrentAccountStatus(): AccountStatus {
        val profile = dao.getProfile()
        if (profile != null && _accountStatus.value.profileId != profile.profileId) {
            _accountStatus.value = _accountStatus.value.copy(
                profileId = profile.profileId,
                isGuest = profile.isGuest,
                displayName = profile.displayName,
                email = profile.email,
                linkedPlayerId = profile.linkedPlayerId
            )
        }
        return _accountStatus.value
    }

    override suspend fun signInWithGoogle(idTokenOverride: String?): AuthActionResult {
        val pickerResult = if (!idTokenOverride.isNullOrBlank()) {
            GooglePickerResult.Success(
                idToken = idTokenOverride,
                displayName = "Google Player",
                email = "player@example.com"
            )
        } else {
            googleIdTokenProvider.requestGoogleIdToken()
        }

        return when (pickerResult) {
            is GooglePickerResult.Cancelled -> {
                val current = getCurrentAccountStatus().copy(
                    syncStatusMessage = "Login Google dibatalkan. Anda tetap bisa lanjut bermain sebagai Guest."
                )
                _accountStatus.value = current
                AuthActionResult.Cancelled(current.syncStatusMessage)
            }
            is GooglePickerResult.Failure -> {
                val current = getCurrentAccountStatus().copy(
                    syncStatusMessage = "${pickerResult.message} Progress lokal tetap aman."
                )
                _accountStatus.value = current
                AuthActionResult.Error(pickerResult.message)
            }
            is GooglePickerResult.Success -> exchangeTokenWithBackendOrFallback(pickerResult)
        }
    }

    private suspend fun exchangeTokenWithBackendOrFallback(
        picker: GooglePickerResult.Success
    ): AuthActionResult {
        val profileId = progressRepository.getProfileId()
        val installationId = preferencesRepository.getPreferences().installationId
        val api = backendApiClient

        if (api == null) {
            val localStatus = AccountStatus(
                profileId = profileId,
                isGuest = true,
                displayName = picker.displayName ?: "Google Player",
                email = picker.email,
                linkedPlayerId = null,
                isBackendSyncActive = false,
                syncStatusMessage = "Akun ${picker.email ?: picker.displayName} dipilih. Server Go belum terhubung; progress tetap milik profil lokal dan sinkronisasi belum aktif."
            )
            _accountStatus.value = localStatus
            return AuthActionResult.LocalOnlyFallback(
                status = localStatus,
                message = localStatus.syncStatusMessage
            )
        }

        val exchangeResult = api.exchangeGoogleToken(
            GoogleAuthExchangeRequest(
                idToken = picker.idToken,
                installationId = installationId,
                guestProfileId = profileId
            )
        )

        return exchangeResult.fold(
            onSuccess = { session ->
                currentSessionToken = session.sessionToken
                val mergeRes = if (session.requiresGuestMerge) {
                    progressRepository.mergeGuestToAuthenticatedAccount(session.playerId)
                } else {
                    Result.success(null)
                }
                val mergedOk = mergeRes.isSuccess

                val resolvedName = picker.displayName ?: session.displayName
                val resolvedEmail = picker.email ?: session.email

                val existingProfile = dao.getProfile()
                if (existingProfile != null) {
                    dao.upsertProfile(
                        existingProfile.copy(
                            isGuest = false,
                            displayName = resolvedName,
                            email = resolvedEmail,
                            linkedPlayerId = session.playerId
                        )
                    )
                }

                pushTokenRepository?.registerOrUpdateToken(
                    fcmToken = "fcm-token-$installationId",
                    linkedPlayerId = session.playerId
                )

                val connectedStatus = AccountStatus(
                    profileId = profileId,
                    isGuest = false,
                    displayName = resolvedName,
                    email = resolvedEmail,
                    linkedPlayerId = session.playerId,
                    isBackendSyncActive = mergedOk,
                    syncStatusMessage = if (mergedOk) {
                        "Masuk sebagai $resolvedName (${resolvedEmail ?: session.playerId}). Token ditukar via POST /v1/auth/google & progress guest telah digabungkan."
                    } else {
                        "Masuk sebagai $resolvedName, namun sinkronisasi awal tertunda. Progress lokal tetap terjaga."
                    }
                )
                _accountStatus.value = connectedStatus
                AuthActionResult.SignedIn(
                    status = connectedStatus,
                    guestMerged = mergedOk
                )
            },
            onFailure = {
                val fallbackStatus = AccountStatus(
                    profileId = profileId,
                    isGuest = true,
                    displayName = picker.displayName ?: "Guest Player",
                    email = picker.email,
                    linkedPlayerId = null,
                    isBackendSyncActive = false,
                    syncStatusMessage = "Akun ${picker.email ?: picker.displayName} dipilih, namun Server Go belum terhubung. Progress tetap milik profil lokal dan sinkronisasi belum aktif."
                )
                _accountStatus.value = fallbackStatus
                AuthActionResult.LocalOnlyFallback(
                    status = fallbackStatus,
                    message = fallbackStatus.syncStatusMessage
                )
            }
        )
    }

    override suspend fun signOut() {
        currentSessionToken = null
        pushTokenRepository?.clearAccountAssociationOnLogout()
        val profile = dao.getProfile()
        if (profile != null) {
            dao.upsertProfile(
                profile.copy(
                    isGuest = true,
                    displayName = "Guest Player",
                    email = null,
                    linkedPlayerId = null
                )
            )
        }
        _accountStatus.value = AccountStatus(
            profileId = profile?.profileId ?: "guest-local-profile",
            isGuest = true,
            displayName = "Guest Player",
            email = null,
            linkedPlayerId = null,
            isBackendSyncActive = false,
            syncStatusMessage = "Telah keluar akun. Bermain menggunakan profil lokal; sinkronisasi tidak aktif."
        )
    }
}
