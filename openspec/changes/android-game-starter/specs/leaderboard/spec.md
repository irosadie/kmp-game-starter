# Spec Delta

## Purpose

Displays player rankings using either a clearly labeled simulated demo adapter or a connected backend leaderboard that accepts and displays only server-verified scores.

## ADDED Requirements

### Requirement: Simulated demo leaderboard
The system SHALL provide a simulated leaderboard adapter in demo mode that displays sample ranking entries and explicitly labels the view with "Demo".

#### Scenario: Opening leaderboard in demo mode
- **WHEN** a player opens the Leaderboard screen in a demo build
- **THEN** the system displays simulated ranking entries alongside a visible "Demo" label without requiring a backend connection

### Requirement: Global leaderboard and server-verified score submission
The system SHALL submit completed attempt scores in connected mode via `POST /v1/games/{gameId}/scores` (including unique attempt ID, level ID, and game validator payload) and SHALL display global rankings from `GET /v1/games/{gameId}/leaderboard` containing only scores accepted by the server.

#### Scenario: Score submission and global leaderboard retrieval
- **WHEN** a player completes a level in connected mode and opens the Leaderboard screen
- **THEN** the client submits the attempt payload to `POST /v1/games/{gameId}/scores` and displays the server-accepted rankings returned by `GET /v1/games/{gameId}/leaderboard`

#### Scenario: Unverified offline score awaits server acceptance before appearing globally
- **WHEN** a player achieves a high score while offline in connected mode
- **THEN** the local best score is saved immediately on the device, and the score appears in the global leaderboard ranking only after the server accepts the submission

### Requirement: Leaderboard loading, empty, and unavailable states
The system SHALL explicitly present loading, empty ranking list, and service unavailable/error states on the Leaderboard screen when operating with the connected backend adapter.

#### Scenario: Backend unavailable when viewing connected leaderboard
- **WHEN** a player opens the Leaderboard screen in connected mode and the backend request fails or is unreachable
- **THEN** the screen transitions from a loading indicator to a clear service-unavailable status message with a retry option
