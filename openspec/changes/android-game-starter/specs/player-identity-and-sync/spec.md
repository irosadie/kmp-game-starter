# Spec Delta

## Purpose

Enables immediate offline-capable guest gameplay with local persistence, optional Google Sign-In authentication, session token exchange, explicit guest-to-account progress merging, and event-based progress synchronization contracts.

## ADDED Requirements

### Requirement: Immediate guest play and local profile persistence
The system SHALL allow players on a clean installation to play immediately as a guest without login, external service credentials, or network connectivity, storing all progress, reward balances, and achievements locally across app restarts.

#### Scenario: Clean demo install plays and persists offline as guest
- **WHEN** a player installs the app on a device without network connectivity or external credentials, completes a level, closes the app, and reopens it
- **THEN** the player enters gameplay immediately as a guest and retains all unlocked progress, balances, and achievements after restart

### Requirement: Google Sign-In and session exchange
The system SHALL provide sign-in and sign-out controls in Settings, authenticate via Google account selection on configured builds, exchange the Google ID token via `POST /v1/auth/google`, and allow continued local gameplay if sign-in is cancelled or the backend is not connected.

#### Scenario: Configured build exchanges Google ID token for session
- **WHEN** a player selects Sign In with Google on an integrated build and chooses a Google account
- **THEN** the client obtains the Google ID token and sends it to `POST /v1/auth/google` to receive an application session and player ID

#### Scenario: Sign-in while backend is unconnected keeps local profile with status message
- **WHEN** a player signs in or views account status while the backend session service is unavailable or in simulated mode
- **THEN** local guest progress remains intact on the local profile and the UI clearly explains that cloud synchronization is not yet active

#### Scenario: Cancelled Google Sign-In allows uninterrupted guest play
- **WHEN** a player initiates Google Sign-In and cancels or dismisses the account picker
- **THEN** the system displays a clear cancellation status and allows the player to continue playing as a guest without data loss

### Requirement: Explicit guest-to-account merge and event-based progress sync
The system SHALL define a synchronization contract that explicitly merges local guest progress into an authenticated account before initial sync, fetches snapshots via `GET /v1/games/{gameId}/progress`, and sends idempotent progress events with unique event IDs via `POST /v1/games/{gameId}/progress/events`.

#### Scenario: Guest progress merges before first account synchronization
- **WHEN** a guest player with local progress completes Google Sign-In against an active backend session
- **THEN** the client executes the explicit guest-to-account progress merge step prior to pulling and syncing the account snapshot

#### Scenario: Retrying progress events reuses unique event IDs
- **WHEN** a progress update event batch fails due to a transient error and is retried against `POST /v1/games/{gameId}/progress/events`
- **THEN** the client resends the events with the exact same unique event IDs and updates local sync state using the server revision and conflict-resolution response
