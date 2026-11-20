# Spec Delta

## Purpose

Provides a headless, platform-independent gameplay engine for level-based casual games, managing level progression, checkpoints, scoring, configurable hint and life rules, and customizable puzzle logic.

## ADDED Requirements

### Requirement: Headless gameplay evaluation
The system SHALL evaluate gameplay actions, win/loss conditions, score calculations, hint applications, and life deductions in a platform-independent Kotlin Multiplatform (`commonMain`) core module that compiles for Android, iOS, and Windows (Desktop JVM) and executes unit tests on the JVM without Android, UI, authentication, or ad SDK dependencies.

#### Scenario: Core rules execute in pure multiplatform/JVM test environment
- **WHEN** player moves, hint requests, and level completion events are submitted to the core gameplay engine in a `commonTest` / JVM unit test
- **THEN** the engine deterministically updates the game state, score, and progression without requiring an Android runtime or platform-specific SDKs

### Requirement: Level unlock, best score, and checkpoint persistence
The system SHALL allow players to start and resume gameplay immediately, persisting unlocked levels, best score per level, and active level checkpoint state across application restarts.

#### Scenario: Player resumes mid-level checkpoint after app restart
- **WHEN** a player makes progress in an active level, closes the application, and reopens it
- **THEN** the system restores the saved level checkpoint, unlocked levels, and previously recorded best score

#### Scenario: Completing a level updates best score and unlocks next progression
- **WHEN** a player completes a level with a score higher than their previous best
- **THEN** the system records the new best score, clears the in-progress checkpoint, and marks the level completed

### Requirement: Configurable hint mechanics
The system SHALL allow each game configuration to enable or disable hints independently, and SHALL deduct a hint from the player's balance only when the game rules confirm that the hint effect was successfully applied to the current state.

#### Scenario: Successful hint application consumes one hint
- **WHEN** hints are enabled, the player has at least 1 hint, and the current puzzle state accepts a hint
- **THEN** the system applies the hint effect to the gameplay state and deducts exactly 1 hint from the player's balance

#### Scenario: Unapplicable hint does not deduct balance
- **WHEN** hints are enabled and the player requests a hint in a puzzle state where no hint can be applied (or hint balance is 0)
- **THEN** the system leaves the hint balance unchanged and returns a non-applied status

#### Scenario: Hint action rejected when hints are disabled
- **WHEN** the game configuration has hints disabled and a hint action is invoked on the core engine
- **THEN** the core engine rejects the action and leaves gameplay state and balance unchanged

### Requirement: Configurable life mechanics and idempotent deduction
The system SHALL allow each game configuration to enable or disable lives independently, SHALL deduct at most one life per unique failed attempt ID, and SHALL expose available life-recovery options when lives reach zero.

#### Scenario: Failed attempt deducts one life idempotently
- **WHEN** lives are enabled and a failed level attempt with a unique attempt ID is processed one or more times
- **THEN** the player's life balance decreases by exactly 1 for that attempt ID

#### Scenario: Out-of-lives state blocks new attempt and offers recovery
- **WHEN** lives are enabled and the player's life balance reaches 0
- **THEN** the system prevents starting a new attempt and presents available ways to obtain lives (such as rewarded ad or reward claim)

#### Scenario: Life action and deduction bypassed when lives are disabled
- **WHEN** the game configuration has lives disabled and a player fails a level attempt
- **THEN** the system records the failed attempt without tracking or deducting lives

### Requirement: Pluggable game rules and sample puzzle level
The system SHALL include at least one playable sample puzzle level and SHALL isolate game-specific rules (level content, score calculation, win/loss conditions, and hint effect) behind a game contract so a developer can replace the game without modifying account, ad, achievement, or settings flows.

#### Scenario: Sample puzzle level playable from start to finish
- **WHEN** a player launches the starter demo and plays the sample puzzle level to completion
- **THEN** the sample puzzle evaluates moves, supports applying a hint, determines win or loss, and produces a final score

#### Scenario: Custom game implementation replaces sample puzzle
- **WHEN** a developer registers a new game rules implementation with custom level data, win/loss logic, scoring, and hint behavior
- **THEN** the existing progression, reward, achievement, leaderboard, and settings flows operate with the new game rules without modification
