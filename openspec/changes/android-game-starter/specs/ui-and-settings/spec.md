# Spec Delta

## Purpose

Defines the standard screen suite, reactive state flow, device-level preferences, visual theming and screen customization seams, and developer documentation for the starter.

## ADDED Requirements

### Requirement: Standard game screens and feature-flag reactivity
The system SHALL provide Home, Gameplay, Result, Leaderboard, Achievements, and Settings screens that render state from ViewModels and forward player intents without embedding score or reward rules, and SHALL hide UI controls for any feature disabled in the game configuration (such as hints or lives).

#### Scenario: Disabling hints or lives hides corresponding UI controls
- **WHEN** a game configuration sets hints or lives to disabled
- **THEN** the Gameplay, Home, and Result screens hide the corresponding hint button or life indicator/recovery controls

### Requirement: Device preferences, account status, and privacy link
The system SHALL provide toggles for music, sound effects, and vibration in Settings stored per device, along with current account/sync status and a per-game configurable privacy policy link.

#### Scenario: Device preferences persist across app restarts
- **WHEN** a player toggles music, sound effects, or vibration in Settings, closes the application, and reopens it
- **THEN** the Settings screen and audio/haptic controllers reflect the saved toggle states

### Requirement: Independent theme and screen customization
The system SHALL organize app branding (name, icon, single-accent color theme, typography, strings) and screen composables so that a developer can re-theme the app and replace an individual demo screen without modifying the headless core module.

#### Scenario: Replacing theme and a single screen leaves core untouched
- **WHEN** a developer updates the app accent color, typography, and replaces one demo screen layout
- **THEN** the application compiles and passes all core gameplay, reward, and achievement tests without changes to the headless core module

### Requirement: Developer onboarding and integration documentation
The system SHALL include a README documenting how to run the offline/simulated demo, customize game rules and visual theme, configure integrated builds (Google Sign-In, AdMob, FCM), and connect the Go backend API when available.

#### Scenario: Developer follows README to run demo and customize starter
- **WHEN** a developer reads the repository README
- **THEN** the document provides step-by-step instructions for running the demo build without credentials, swapping game logic and theme, enabling integration credentials, and wiring the Go backend endpoints
