# Design

## Context

This repository starts empty and establishes **Android Game Starter v1.0** based on `PRD-Android-Game-Starter.md` (see `proposal.md` for motivation). The starter must run out of the box in a zero-credential **Demo mode** (local persistence + simulated online/ad adapters) while providing production-ready interfaces, HTTP v1 JSON contracts, and Android SDK integration adapters (Credential Manager Google Sign-In, AdMob test ads, FCM config refresh) for a future Go backend and admin panel.

## Goals / Non-Goals

**Goals:**
- Enforce a strict compile-time multiplatform boundary between pure-Kotlin gameplay/reward/achievement logic (`:core` using Kotlin Multiplatform `commonMain` targeting Android, iOS, and Windows Desktop JVM) and Android v1 application/UI concerns (`:app`).
- Allow swapping game mechanics (rules, level data, hint effects, score calculation, feature flags) independently from visual presentation (Compose theme, screens) and platform adapters.
- Guarantee idempotency on all balance-mutating operations (hint usage, life deduction per failed attempt ID, reward grants per transaction ID, one-time achievement payouts, and event-based progress sync).
- Support two runtime configurations (`demo` and `integrated`) selectable via build flavors or dependency injection without changing ViewModels or UI screens.

**Non-Goals:**
- Implementing the Go backend server, database migrations, or admin panel (those belong to a separate repository).
- Shipping full production iOS or Windows UI shells in v1 (v1 UI and platform SDK adapters focus on Android per PRD, while `:core` provides the KMP multiplatform foundation compiled for Android, iOS, and JVM/Windows).
- Real-time multiplayer, complex physics engines, or custom 2D/3D OpenGL rendering engines (target is level-based casual games rendered with Jetpack Compose).
- Live production AdMob/Google/Firebase credentials committed to the repository (`demo` runs without credentials; `integrated` uses AdMob test ad unit IDs and developer-supplied local config).

## Decisions

### 1. Kotlin Multiplatform (KMP) Architecture (`:core` KMP + `:app` Android)
- **Decision**: Split the project into two Gradle modules with a KMP foundation:
  1. `:core` (`kotlin("multiplatform")` + `org.jetbrains.kotlin.plugin.serialization`):
     - Targets: `jvm()` (covers fast JVM unit testing and Windows/Desktop JVM target) and iOS targets (`iosArm64()`, `iosSimulatorArm64()`, `iosX64()`).
     - All domain code resides in `commonMain` and tests in `commonTest`, using only multiplatform-safe APIs (`kotlinx.coroutines`, `kotlinx.serialization`, `kotlin.uuid.Uuid` or injected ID/clock providers—strictly no `android.*` or `java.*`-only APIs).
     - Contains `GameEngine`, `LevelRuleEngine`, sample puzzle implementation, `RewardLedger`, `AchievementEvaluator`, `GameConfig` models, Go v1 contract models, and repository/service port interfaces.
  2. `:app` (`com.android.application` + Jetpack Compose):
     - Consumes `:core` and implements the v1 Android scope: Android adapters (Room database, DataStore preferences, HTTP client for Go v1 endpoints, Credential Manager, AdMob, FCM), MVVM ViewModels, Compose screens, and Dependency Injection wiring.
- **Rationale**: Using `kotlin("multiplatform")` in `:core` satisfies the PRD's JVM-testable headless core requirement while guaranteeing that zero Java-only (`java.util.*`, `java.time.*`) or Android-only dependencies leak into the core domain. When the project expands to iOS or Windows Desktop later, the entire gameplay, progression, reward ledger, achievement engine, and API contracts are already compiled and ready for those targets.
- **Alternatives considered**:
  - `:core` as `kotlin("jvm")` only: Rejected because `kotlin("jvm")` permits `java.*` APIs that fail when compiling for iOS Kotlin/Native later.
  - Full iOS & Windows UI in v1: Deferred beyond v1 so Android delivery stays tightly aligned with the PRD scope (Credential Manager, AdMob, FCM, Jetpack Compose) without premature platform-SDK wrapper overhead.

### 2. Pluggable Game Rules via `GameDefinition<State, Move, LevelData>`
- **Decision**: Define a generic headless contract in `:core`:
  - `GameMetadataAndFeatures`: gameId, display flags (`hintsEnabled`, `livesEnabled`, maxLives, initialHints), reward amounts, and achievement definitions.
  - `LevelRuleEngine<State, Move>`: `createInitialState(level)`, `applyMove(state, move)`, `applyHint(state): HintApplicationResult<State>`, `evaluateOutcome(state): LevelOutcome` (InProgress, Won with score & validator payload, Lost with attemptId).
  - Ship a concrete sample puzzle (e.g., a 1-level number/word tile or logic grid puzzle) implementing this interface.
- **Rationale**: Ensures hints are only deducted when `applyHint` returns `Applied(newState)` and lives are only deducted once per `Lost(attemptId)` through the core coordinator.
- **Alternatives considered**: Hardcoding puzzle state inside `GameplayViewModel`. Rejected because it violates the headless requirement and prevents swapping the game without touching Android code.

### 3. Unified Idempotent Ledger & Room Persistence Schema
- **Decision**: Model all hint and life mutations as immutable ledger transactions identified by `transactionId: String` (e.g., `level_complete:{levelId}:{attemptId}`, `achievement:{profileId}:{achievementId}`, `ad_reward:{claimId}`, `attempt_fail:{attemptId}`).
  - In `:core`, `RewardLedger` validates whether a `transactionId` has already been applied before mutating balances.
  - In `:app`, Room stores:
    - `PlayerProfileEntity` (profileId, isGuest, linkedAccountId, hintBalance, lifeBalance, serverRevision)
    - `LevelProgressEntity` (levelId, unlocked, completed, bestScore, checkpointJson)
    - `LedgerTransactionEntity` (`transactionId` PRIMARY KEY, type, resourceType, delta, source, createdAt, syncStatus)
    - `AchievementProgressEntity` (`achievementId` PRIMARY KEY, progress, unlocked, rewardTransactionId)
    - `PendingSyncEventEntity` (`eventId` PRIMARY KEY, eventType, payloadJson, createdAt)
- **Rationale**: A primary-key constraint on `transactionId` (backed by an in-memory/domain check in `:core`) guarantees that duplicate ad callbacks, retried failed attempts, or repeated achievement evaluations never double-credit or double-deduct balances.

### 4. Adapter Selection (`Demo` vs `Integrated` Build Flavors / DI Container)
- **Decision**: Provide two product flavors (or a manual DI container switchable via `BuildConfig.STARTER_MODE`):
  - `demo`: Binds `SimulatedAuthAdapter`, `SimulatedLeaderboardAdapter` (returns rankings tagged with `isDemo = true`), `SimulatedAdAdapter` (simulates rewarded ad completion/dismissal/failure), and `SimulatedRemoteConfigAdapter` (allows triggering simulated config updates and cache expiry in-app/via debug controls). Requires zero external keys or `google-services.json`.
  - `integrated`: Binds `CredentialManagerAuthAdapter`, `GoBackendApiAdapter` (for `/v1/*` endpoints), `AdMobRewardedAdAdapter` (configured with Google's official test rewarded ad unit ID during development), and `FcmConfigRefreshService`.
- **Rationale**: Satisfies both "clean demo install works offline without credentials" and "integrated build tests Google Sign-In, AdMob test ads, FCM refresh routing, and Go backend error/loading states."
- **Alternatives considered**: Runtime-only toggle with all SDKs initialized unconditionally. Rejected because Firebase/AdMob auto-init crashes or logs errors on clean clones when config files (`google-services.json`) are absent.

### 5. Remote Config Cache & FCM Refresh Pipeline
- **Decision**: Implement `ConfigRepository` with a 3-tier resolution order:
  1. Fetch `GET /v1/games/{gameId}/config` on app start/resume if cached config TTL is expired, OR immediately when `ConfigRefreshSignal` arrives (from FCM data message `{ "type": "config_refresh", "gameId": "...", "configVersion": "..." }` or `SimulatedRemoteConfigAdapter`).
  2. If the network call fails, check DataStore/Room cached config. If present and within max stale validity window, use cached config.
  3. If cache is missing or expired beyond validity, fall back to `GameConfig.safeDefault()` where all ads (`rewarded`, `interstitial`, `banner`) are disabled while local gameplay features (`hintsEnabled`, `livesEnabled`) use the game's static defaults.
- **Rationale**: Prevents bad network conditions or an unlaunched Go backend from breaking local gameplay or serving unauthorized ads.

### 6. UI Customization Seams in Jetpack Compose
- **Decision**: Use standard Material 3 components styled through a single `StarterGameTheme(accentColor = ...)` wrapper and organize the 6 screens (`HomeScreen`, `GameplayScreen`, `ResultScreen`, `LeaderboardScreen`, `AchievementsScreen`, `SettingsScreen`) as stateless composables taking `(uiState, onAction)`. `GameplayScreen` delegates rendering of the puzzle board to a pluggable `@Composable fun PuzzleBoardContent(state, onMove)` slot so developers can swap either the board layout or an entire screen without altering navigation or ViewModels.

## Risks / Trade-offs

- **[Risk] Missing `google-services.json` or OAuth client ID on fresh developer checkout breaks build** → **Mitigation**: Keep `demo` as the default build variant with no required Google/Firebase plugin application (or conditional plugin activation only when `google-services.json` exists / in `integrated` variant), and document credential setup in `README.md`.
- **[Risk] Guest-to-account progress merge conflicts when signing into an existing backend account** → **Mitigation**: Keep guest progress isolated until `POST /v1/auth/google` succeeds, then invoke an explicit merge flow sending local guest snapshot/events before the first pull, letting the server return the authoritative merged revision. When backend is unreachable, keep progress strictly on the local profile and surface a clear "Sync not active" indicator in Settings.
- **[Risk] Server-side AdMob reward verification latency** → **Mitigation**: In `demo` (and offline local-balance mode), grant rewards immediately via local idempotent transaction ID. In `integrated` server-balance mode, `POST /v1/games/{gameId}/rewards/claims` returns a `PENDING` claim status and the UI displays a clear pending state with polling/retry until confirmed.

## Migration Plan

1. Initialize multi-module Gradle build (`settings.gradle.kts`, root `build.gradle.kts`, `gradle/libs.versions.toml`, `:core`, `:app`).
2. Implement and unit-test `:core` domain models, idempotent ledger, achievement engine, and sample puzzle rules.
3. Implement `:app` Room database, DataStore preferences, HTTP v1 contract models, and both `demo` (simulated) and `integrated` adapters.
4. Build MVVM ViewModels and the 6 Jetpack Compose screens with feature-flag reactivity and theme customization seams.
5. Write `README.md` covering demo execution, game/theme customization, integrated build setup, and Go backend wiring.
