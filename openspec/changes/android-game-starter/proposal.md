# Proposal

## Why

Building new level-based casual games (such as puzzles, quizzes, or word games) repeatedly requires recreating the same foundational systems: progress persistence, hints, lives, rewards, achievements, settings, authentication, ads, and leaderboards. Creating a reusable, headless-core game starter built on **Kotlin Multiplatform (KMP)** allows developers to focus primarily on Android in v1—launching a playable offline/simulated demo out of the box, swapping game rules or Compose UI skins independently, and connecting to a Go backend—while having a cross-platform foundation (`commonMain`) ready for iOS and Windows (Desktop JVM) expansion without rewriting core game logic.

## What Changes

- **Kotlin Multiplatform (KMP) Headless Core**: Introduce a KMP `:core` module (`commonMain` / `commonTest`) targeting Android, iOS, and Windows (JVM Desktop) that encapsulates level progression, checkpoints, scoring, configurable hint/lives rules, achievement evaluation, and idempotent reward ledger rules without Android, Java-only, Compose, Firebase, or AdMob dependencies.
- **Sample Puzzle & Customization Seams**: Provide one playable sample puzzle level with clear extension interfaces and game configuration so new games can replace rules, level content, feature flags, and UI screens independently.
- **Unified Idempotent Reward & Achievement System**: Implement a single reward system for hints and lives across level completion, achievements, and rewarded ads using unique transaction IDs to prevent duplicate grants on retries or repeated callbacks, plus demo achievements ("Selesaikan level pertama" and "Selesaikan level tanpa hint").
- **Guest Mode, Google Sign-In & Sync Contracts**: Support immediate guest play with local Room persistence, optional Google Sign-In via Android Credential Manager, explicit guest-to-account merge semantics, and event-based progress sync contracts (`GET /v1/games/{gameId}/progress`, `POST /v1/games/{gameId}/progress/events`, `POST /v1/auth/google`).
- **Leaderboard with Demo & Connected Modes**: Provide a simulated leaderboard explicitly labeled "Demo" alongside a backend adapter contract (`GET /v1/games/{gameId}/leaderboard`, `POST /v1/games/{gameId}/scores`) that displays server-accepted scores and handles loading, empty, and unavailable states.
- **Rewarded Ads, Remote Config & FCM Refresh**: Add opt-in rewarded ads (simulated demo flow + AdMob test ad integration), default-off optional interstitial/banner ads, versioned game configuration (`GET /v1/games/{gameId}/config`), FCM push-token registration (`PUT /v1/devices/{installationId}/push-token`) and config refresh signal handling, with cached and safe ads-off default fallbacks.
- **Jetpack Compose UI, MVVM & Device Settings**: Deliver Compose screens (Home, Gameplay, Result, Leaderboard, Achievements, Settings) with a single-accent customizable theme, ViewModels delegating business rules to the core, DataStore preferences (music, SFX, vibration), account status, privacy link configuration, and comprehensive README documentation.

## Capabilities

### New Capabilities
- `gameplay-core`: Headless Kotlin Multiplatform (`commonMain`) gameplay rules targeting Android, iOS, and Windows (JVM), level unlock/checkpoint persistence, scoring, configurable hint and life mechanics, and the sample puzzle implementation.
- `rewards-and-achievements`: Unified idempotent reward ledger for hints and lives (from levels, achievements, and ads) and achievement progress tracking, unlocking, and one-time reward payouts.
- `player-identity-and-sync`: Immediate guest play, local Room persistence, Google Sign-In via Credential Manager, session token exchange, explicit guest-to-account progress merge, and event-based backend progress synchronization contracts.
- `leaderboard`: Simulated "Demo" leaderboard adapter and connected global leaderboard contract with server-validated score submission and clear empty, loading, and error/unavailable states.
- `monetization-and-remote-config`: Rewarded ad flow (simulated and AdMob test ads), server-verified reward claim contract, versioned remote game/ad configuration, FCM refresh triggers, and multi-tier cache/default fallbacks.
- `ui-and-settings`: Jetpack Compose screens (Home, Gameplay, Result, Leaderboard, Achievements, Settings), MVVM state/action wiring, DataStore device preferences (music, sound effects, vibration), theme/screen customization seams, and developer documentation.

### Modified Capabilities
<!-- None — initial greenfield specification -->

## Impact

- **Project Structure & Modules**: Multi-module Gradle project split into a Kotlin Multiplatform `:core` module (`commonMain`/`commonTest` with targets for Android/JVM Windows Desktop and iOS `iosArm64`/`iosSimulatorArm64`) and an `:app` Android module delivering the v1 Android scope (Jetpack Compose UI, ViewModels, Room database, DataStore preferences, and Android service adapters).
- **Dependencies**: Kotlin Multiplatform, Kotlin Coroutines/Flow, Kotlinx Serialization (multiplatform), Jetpack Compose (Material 3), AndroidX Lifecycle/ViewModel, Room, Jetpack DataStore, Android Credential Manager (Google ID), Google Mobile Ads SDK (AdMob), Firebase Cloud Messaging (FCM), and HTTP client abstractions for Go backend contracts.
- **Build Variants / DI Configuration**: Adapter selection via dependency injection / build configuration allowing a zero-credential `demo` mode (offline + simulated online flows) and an `integrated` mode (Google Sign-In, AdMob test ads, FCM, and Go API client).
- **External API Contracts**: Establishes initial HTTP JSON v1 contracts (`/v1/auth/google`, `/v1/games/{gameId}/config`, `/v1/games/{gameId}/progress`, `/v1/games/{gameId}/progress/events`, `/v1/games/{gameId}/leaderboard`, `/v1/games/{gameId}/scores`, `/v1/games/{gameId}/rewards/claims`, `/v1/devices/{installationId}/push-token`) for the separate Go backend repository.
