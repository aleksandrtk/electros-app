# Electros EV Android Client

Modern Android companion app for Electros EV home charging stations (`electros.org.ua`).

## Highlights

- **Automated Energy Limiter**: Set target kWh (e.g., 30 kWh) and automatically shut off the station upon reaching the quota.
- **Power Outage Resilience**: Accumulates energy delivery across garage blackouts without resetting session progress.
- **Fail-Safe Hardware Fallback**: Syncs expected charge duration with station hardware timers as a dead-man safeguard if the phone loses connectivity.
- **Foreground Service**: Reliable background polling loop using `specialUse` foreground service, resilient against Android Doze mode.
- **Material 3 UI**: Jetpack Compose dark theme dashboard with real-time voltage, neutral line drift, amperage controls, and charging statistics.

## Tech Stack

- **UI**: Jetpack Compose + Material 3 Dark Theme
- **Architecture**: MVI / MVVM with Clean Architecture (Domain, Data, UI)
- **Local Persistence**: Room Database (with session and slice entities) + EncryptedSharedPreferences (credentials)
- **Background Work**: Coroutine-based Foreground Service + WorkManager (reboot recovery)
- **Networking & Parsing**: OkHttp 4 + Jsoup DOM parser

## Building from Source

```bash
# Clone the repository
git clone https://github.com/aleksandrtk/electros-app.git
cd electros-app

# Run unit tests
./gradlew test

# Assemble debug APK
./gradlew assembleDebug
```

The APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

## License

MIT License

