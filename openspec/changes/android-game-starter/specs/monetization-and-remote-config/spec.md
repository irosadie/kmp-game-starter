# Spec Delta

## Purpose

Orchestrates opt-in rewarded ads, server-verified reward claims, optional interstitial and banner formats, versioned remote game configuration, push-triggered config refreshes, and offline cache fallbacks.

## ADDED Requirements

### Requirement: Opt-in rewarded ads and failure handling
The system SHALL trigger rewarded ads only upon explicit player choice, providing a simulated reward ad flow in demo mode and test ads in integrated builds, and SHALL display a clear status without incrementing balance if the ad fails to load, is unavailable, or is closed before earning the reward.

#### Scenario: Player completes simulated or test rewarded ad
- **WHEN** the player chooses to watch a rewarded ad and completes the simulated demo flow or integrated test ad reward callback
- **THEN** the system grants the configured hint or life reward once using a unique transaction ID

#### Scenario: Rewarded ad dismissed early or fails to load
- **WHEN** the player triggers a rewarded ad and the ad fails to load, is unavailable, or is closed before completion
- **THEN** the system leaves the player's hint and life balances unchanged and displays a human-readable status message explaining why no reward was granted

### Requirement: Server-verified ad reward claim contract
The system SHALL support server-authoritative ad reward verification in connected mode by submitting and polling claim IDs via `POST /v1/games/{gameId}/rewards/claims` and displaying a pending confirmation state until the backend confirms the official AdMob server-side callback.

#### Scenario: Connected rewarded ad awaits server claim verification
- **WHEN** a player completes a rewarded ad in connected server-balance mode
- **THEN** the client submits a claim via `POST /v1/games/{gameId}/rewards/claims`, displays a waiting-for-confirmation status, and updates the balance only after the claim status resolves to confirmed

### Requirement: Optional interstitial and banner formats
The system SHALL support interstitial and banner ad placements as optional configuration-driven features that are disabled by default.

#### Scenario: Default configuration suppresses interstitial and banner ads
- **WHEN** the game runs with default ad configuration
- **THEN** neither interstitial nor banner ads are requested or displayed during navigation or gameplay

### Requirement: Versioned remote config, push refresh, and multi-tier fallback
The system SHALL fetch versioned game configuration from `GET /v1/games/{gameId}/config` (containing per-format ad status, frequency caps, reward amounts, and feature flags) on app launch/resume when cache is expired or upon receiving a push refresh signal, register push tokens via `PUT /v1/devices/{installationId}/push-token`, and fall back to valid cached config or safe ads-off defaults when fetching fails.

#### Scenario: Push refresh signal triggers remote config update
- **WHEN** the app receives a configuration refresh signal (from the simulated adapter or FCM push payload containing game ID or config version)
- **THEN** the client fetches the latest configuration from `GET /v1/games/{gameId}/config` and updates active ad rules and feature flags

#### Scenario: Config fetch failure falls back to valid cache then ads-off default
- **WHEN** fetching `GET /v1/games/{gameId}/config` fails due to network or server unavailability
- **THEN** the system uses the locally cached configuration if its TTL is still valid, or falls back to the built-in default configuration with all ads disabled while keeping local gameplay fully functional

#### Scenario: Logout disassociates account from installation push token
- **WHEN** an authenticated player signs out of their account
- **THEN** the client updates or clears the account association for `PUT /v1/devices/{installationId}/push-token`
