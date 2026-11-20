# Android Game Starter (KMP Headless Core + Jetpack Compose)

Fondasi reusable untuk game casual berbasis level (puzzle, kuis, tebak kata) dengan **Kotlin Multiplatform (`:core`)** yang siap untuk **Android, iOS, dan Windows (Desktop JVM)** serta aplikasi **Android (`:app`)** berbasis MVVM, Jetpack Compose, Room, DataStore, Credential Manager (Google Sign-In), AdMob, dan FCM.

---

## 1. Struktur Modul

- **`:core`** (`kotlin("multiplatform")` — `commonMain` & `commonTest`, target `jvm()` + iOS `iosArm64`, `iosSimulatorArm64`, `iosX64`):
  - `core/src/commonMain/kotlin/com/binarydev/startergame/core/engine/GameEngine.kt`: Kontrak headless `LevelRuleEngine` dan `GameSessionCoordinator` (aturan main, skor, hint, nyawa, evaluasi pencapaian).
  - `core/src/commonMain/kotlin/com/binarydev/startergame/core/ledger/RewardLedger.kt`: Sistem reward tunggal idempotent berbasis `transactionId` untuk hint dan nyawa.
  - `core/src/commonMain/kotlin/com/binarydev/startergame/core/achievement/AchievementEvaluator.kt`: Evaluasi pencapaian (*"Selesaikan level pertama"* dan *"Selesaikan level tanpa hint"*) dengan pemberian hadiah sekali per profil.
  - `core/src/commonMain/kotlin/com/binarydev/startergame/core/sample/SamplePuzzleGame.kt`: Implementasi puzzle contoh (*Kata Nusantara Puzzle*) lengkap dengan serialisasi checkpoint.
  - `core/src/commonMain/kotlin/com/binarydev/startergame/core/contracts/BackendContractsAndPorts.kt`: Interface repository & kontrak DTO JSON v1 untuk backend Go.
- **`:app`** (`com.android.application` — flavor `demo` dan `integrated`):
  - `app/src/main/kotlin/com/binarydev/startergame/data/local/StarterGameDatabase.kt`: Penyimpanan lokal Room (profil, progress level & checkpoint, transaksi ledger idempotent, pencapaian, antrean sync event).
  - `app/src/main/kotlin/com/binarydev/startergame/data/preferences/DataStoreDevicePreferencesRepository.kt`: Preferensi perangkat DataStore (musik, efek suara, getaran, cache remote config).
  - `app/src/main/kotlin/com/binarydev/startergame/data/remote/GoBackendApiClient.kt`: Klien HTTP JSON v1 untuk backend Go.
  - `app/src/main/kotlin/com/binarydev/startergame/data/repository/`: Adapter simulasi (`SimulatedLeaderboardAdapter`, `SimulatedAdAdapter`, `SimulatedRemoteConfigAdapter`) dan repository terhubung (`RoomProgressRepository`, `ConnectedLeaderboardRepository`, `RewardClaimService`, `ConfigRepositoryImpl`).
  - `app/src/main/kotlin/com/binarydev/startergame/data/integration/`: Adapter integrasi Android (`CredentialManagerAuthAdapter`, `AdMobRewardedAdAdapter`, `FcmPushTokenAndRefreshAdapter`).
  - `app/src/main/kotlin/com/binarydev/startergame/ui/`: Tema satu warna aksen (`StarterGameTheme`), ViewModel MVVM (`GameViewModels.kt`), dan 6 layar Jetpack Compose (`HomeScreen`, `GameplayScreen`, `ResultScreen`, `LeaderboardScreen`, `AchievementsScreen`, `SettingsScreen`).

---

## 2. Cara Menjalankan Mode Demo (Tanpa Server Go / Tanpa Kredensial)

Flavor `demo` berjalan langsung secara offline dan memakai adapter simulasi untuk leaderboard (berlabel `"Demo"`), rewarded ad, Google login, dan sinyal refresh remote config.

```bash
# 1. Jalankan unit test KMP core (JVM) & kompilasi target iOS
./gradlew :core:jvmTest :core:compileKotlinIosSimulatorArm64

# 2. Build APK Demo Debug
./gradlew :app:assembleDemoDebug

# 3. Jalankan seluruh unit test Android (Demo & Integrated)
./gradlew :app:testDemoDebugUnitTest :app:testIntegratedDebugUnitTest
```

Di dalam aplikasi Demo, buka layar **Pengaturan** untuk:
- Menguji toggle Musik, Efek Suara, dan Getaran (tersimpan di DataStore).
- Memicu perubahan konfigurasi iklan/fitur (mematikan Hint atau Nyawa secara live) dan menguji fallback cache tanpa server Go.
- Mengubah skenario simulasi Rewarded Ad (berhasil, ditutup lebih awal, tidak tersedia, atau gagal).

---

## 3. Cara Mengganti Game & Tema untuk Proyek Baru

### A. Mengganti Tema & Satu Layar Compose (Tanpa Mengubah `:core`)
1. Ubah warna aksen tunggal, font, dan nama aplikasi melalui `StarterThemeConfig` di `app/src/main/kotlin/com/binarydev/startergame/ui/theme/StarterGameTheme.kt` atau saat memanggil `StarterGameTheme` di `app/src/main/kotlin/com/binarydev/startergame/MainActivity.kt`.
2. Untuk mengganti tampilan papan permainan saja, berikan composable baru pada parameter `puzzleBoardContent` di `StarterGameApp(...)`.
3. Untuk mengganti satu layar penuh (misalnya layar Home kustom), berikan composable pada parameter `customHomeScreen` di `StarterGameApp(...)` di `app/src/main/kotlin/com/binarydev/startergame/ui/navigation/StarterGameNavHost.kt`.

### B. Mengganti Aturan Game, Konten Level, Hint, dan Pencapaian
1. Buat implementasi `LevelRuleEngine<LevelData, State, Move>` baru di `:core` (lihat contoh `SamplePuzzleGame` di `core/src/commonMain/kotlin/com/binarydev/startergame/core/sample/SamplePuzzleGame.kt`).
2. Atur fitur aktif (`hintsEnabled`, `livesEnabled`), jumlah reward, dan daftar pencapaian di `GameMetadataAndFeatures` dan `AchievementDefinition`.
3. Suntikkan `LevelRuleEngine` baru tersebut ke `StarterAppContainer(context, ruleEngine = MyNewGameEngine())` tanpa mengubah kode akun, iklan, leaderboard, maupun pengaturan.

---

## 4. Mengatur Build Integrasi (`integrated`) & Menyambungkan API Go

Flavor `integrated` menghubungkan `CredentialManagerAuthAdapter`, `AdMobRewardedAdAdapter` (menggunakan test ad unit ID resmi Google `ca-app-pub-3940256099942544/5224354917` saat pengembangan), `FcmPushTokenAndRefreshAdapter`, dan `HttpUrlConnectionGoBackendApiClient`.

```bash
# Build APK Integrated Debug
./gradlew :app:assembleIntegratedDebug
```

Konfigurasi endpoint, token, dan kredensial dibaca otomatis oleh `app/build.gradle.kts` dari file **[.env](file:///Users/binarydev/Program/Binarydev/starter-game/.env)** (template tersedia di **[.env.example](file:///Users/binarydev/Program/Binarydev/starter-game/.env.example)**):

```bash
cp .env.example .env
```

Variabel yang tersedia di `.env`:
- `GAME_ID` & `PRIVACY_POLICY_URL`: ID unik game untuk endpoint `/v1/games/{gameId}/*` dan URL kebijakan privasi di layar Pengaturan.
- `BACKEND_BASE_URL`: URL server Go (default emulator: `http://10.0.2.2:8080`).
- `BACKEND_API_KEY`: API key / static token opsional (dikirim sebagai header `X-API-Key`).
- `BACKEND_TIMEOUT_MS`: Batas waktu koneksi HTTP dalam milidetik (default: `5000`).
- `USE_SERVER_REWARD_VERIFICATION`: Set `true` bila klaim rewarded ad di mode terhubung wajib menunggu verifikasi callback server Go (`POST /v1/games/{gameId}/rewards/claims`).
- `GOOGLE_WEB_CLIENT_ID`: OAuth 2.0 Web Client ID untuk pertukaran Google ID Token ke `POST /v1/auth/google`.
- `ADMOB_APP_ID`, `ADMOB_REWARDED_AD_UNIT_ID`, `ADMOB_INTERSTITIAL_AD_UNIT_ID`, `ADMOB_BANNER_AD_UNIT_ID`: App ID AdMob (disuntikkan ke `AndroidManifest.xml`) dan Ad Unit ID per format (default menggunakan test ad ID resmi Google).
- `FCM_PROJECT_ID`, `FCM_APP_ID`, `FCM_SENDER_ID`, `FCM_API_KEY`: Kredensial Firebase Cloud Messaging opsional.

### Kontrak HTTP JSON v1 yang Disiapkan untuk Server Go
| Endpoint | Implementasi Client |
|---|---|
| `POST /v1/auth/google` | `GoBackendApiClient.exchangeGoogleToken` & `SessionRepositoryImpl` (eksplisit menggabungkan progress guest sebelum sinkronisasi pertama) |
| `GET /v1/games/{gameId}/config` | `GoBackendApiClient.getGameConfig` & `ConfigRepositoryImpl` (cache TTL + fallback default iklan mati) |
| `GET /v1/games/{gameId}/progress` & `POST /v1/games/{gameId}/progress/events` | `GoBackendApiClient.getProgressSnapshot` / `postProgressEvents` & `RoomProgressRepository` (event ber-ID unik & idempotent retry) |
| `GET /v1/games/{gameId}/leaderboard` & `POST /v1/games/{gameId}/scores` | `GoBackendApiClient.getLeaderboard` / `submitScore` & `ConnectedLeaderboardRepository` |
| `POST /v1/games/{gameId}/rewards/claims` | `GoBackendApiClient.createOrCheckRewardClaim` & `RewardClaimService` (mendukung status `PENDING` sampai callback AdMob diverifikasi server) |
| `PUT /v1/devices/{installationId}/push-token` | `GoBackendApiClient.putPushToken` & `FcmPushTokenAndRefreshAdapter` (lepas kaitan akun saat logout & arahkan sinyal refresh FCM ke `ConfigRepository`) |
