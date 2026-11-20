# Spec Delta

## Purpose

Manages a unified, idempotent reward ledger for hints and lives across all reward sources and evaluates player achievements with one-time reward payouts per profile.

## ADDED Requirements

### Requirement: Unified idempotent reward ledger
The system SHALL use a single reward system to grant hints and lives from level completion, achievement unlocks, and rewarded ads, requiring a unique transaction ID for every grant so retries or duplicate callbacks credit the player's balance at most once.

#### Scenario: Reward grant with new transaction ID increases balance
- **WHEN** a reward grant for hints or lives is submitted from level completion, an achievement, or a rewarded ad with a previously unseen transaction ID
- **THEN** the system records the transaction ID and increments the player's hint or life balance by the specified amount

#### Scenario: Duplicate reward callback with existing transaction ID is ignored
- **WHEN** a reward grant is re-submitted or re-triggered with a transaction ID that has already been processed
- **THEN** the system does not increment the player's hint or life balance a second time and returns the existing applied status

### Requirement: Achievement progress tracking and display
The system SHALL track and expose each achievement's target criteria, current progress, and locked/unlocked status, and the demo configuration SHALL define at least "Selesaikan level pertama" (complete the first level) and "Selesaikan level tanpa hint" (complete a level without using a hint).

#### Scenario: Viewing locked and unlocked achievements
- **WHEN** the player opens the Achievements view before and after completing a level
- **THEN** the system displays each achievement's title, target, current progress value, and whether it is locked or unlocked

### Requirement: Automatic one-time achievement reward payout
The system SHALL evaluate achievement conditions upon level completion, immediately unlock any newly satisfied achievements, and grant their configured hint or life rewards exactly once per player profile.

#### Scenario: Completing first level without hints unlocks both demo achievements and grants rewards once
- **WHEN** a new player completes the first level without using any hints
- **THEN** the system unlocks both "Selesaikan level pertama" and "Selesaikan level tanpa hint" and credits each achievement's reward to the player's balance once

#### Scenario: Repeating level completion does not re-grant unlocked achievement rewards
- **WHEN** a player replays and completes a level after an achievement has already been unlocked on that profile
- **THEN** the achievement remains unlocked and no additional achievement reward is credited
