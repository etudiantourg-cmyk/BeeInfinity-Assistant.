# BeeInfinity Assistant

Android app prototype for monitoring a game screen and notifying when OCR detects **1.00X**.

## Current scope
- Requests Android's official MediaProjection screen-capture permission.
- Runs a foreground capture service while monitoring is active.
- Uses on-device ML Kit text recognition to detect the visible multiplier.
- Sends one notification when 1.00X appears, then rearms after it disappears.
- Does **not** tap the game, hide automation, bypass anti-cheat, or promise game outcomes.

## Build
Open this repository in Android Studio (recent stable version), allow Gradle sync, then build the `app` configuration. The project uses Android Gradle Plugin 8.7.3, Kotlin 2.0.21, and JDK 17.

## Use
Install the app, grant notification permission if requested, tap **Start monitoring**, and approve Android's screen-capture prompt. Stop monitoring from the app notification or by returning to the app.

Screen capture is visible to Android and requires explicit consent each session. Some games may block screen capture.
