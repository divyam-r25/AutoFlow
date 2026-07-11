# AutoFlow

AutoFlow is an Android automation app built with Kotlin + Jetpack Compose.

## Features

- Permission-guided setup flow (Accessibility, Overlay, Battery optimization)
- Dashboard with automation status and start/stop control
- Click/action model state displayed in dashboard
- Profile-based defaults with local persistence
- Foreground overlay service for on-screen automation controls

## Architecture

Feature-based package structure under `/app/src/main/java/com/autoflow`:

- `features/dashboard` - dashboard UI, ViewModel, state models
- `features/permissions` - permission flow UI and permission state models
- `features/profiles` - profile models and persistence layer
- `automation` - click engine, actions, execution models
- `core` - Android services, overlay infrastructure, permission manager
- `navigation` - app navigation graph
- `ui` - reusable components and theme/colors

## Project Setup

From repository root:

```bash
./gradlew build
```

For unit tests:

```bash
./gradlew test
```

For lint checks:

```bash
./gradlew lint
```

## Usage Guide

1. Install and open the app.
2. Complete the permission flow:
   - Enable Accessibility service
   - Grant overlay permission
   - Disable battery optimization (recommended)
3. Continue to Dashboard.
4. Review active profile and action-point summary.
5. Tap **Start Automation** to launch the overlay service.
6. Tap **Stop Automation** to end automation.

## Persistence

Profiles and action-point models are persisted in local `SharedPreferences` via `ProfileStorage`.
