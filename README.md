# Electros EV Android Client

Modern Android companion app for Electros EV home charging stations (`electros.org.ua`).

## Highlights

- **Automated Energy Limiter**: Set target kWh (e.g., 30 kWh) and automatically shut off the station upon reaching the quota.
- **Power Outage Resilience**: Accumulates energy delivery across garage blackouts without resetting session progress.
- **Fail-Safe Hardware Fallback**: Syncs expected charge duration with station hardware timers as a dead-man safeguard if the phone loses connectivity.
- **Foreground Service**: Reliable background polling loop using `specialUse` foreground service, resilient against Android Doze mode.
- **Material 3 UI**: Jetpack Compose dark theme dashboard with real-time RMS grid voltage, live active amperage controls, voltage-sag DLB auto-derating, and charging statistics.

## Platform Requirement

- **Android 17+ only** (`minSdk = 37`, `targetSdk = 37`, `compileSdk = 37.0`)

## Tech Stack

- **UI**: Jetpack Compose + Material 3 Dark Theme (Edge-to-Edge & Predictive Back)
- **Architecture**: MVI / MVVM with Clean Architecture (Domain, Data, UI)
- **Local Persistence**: Room Database (session & slice ledger) + Android Keystore AES-256-GCM encrypted credentials in Jetpack DataStore
- **Background Work**: Coroutine-based Foreground Service + `BootReceiver` recovery
- **Networking & Parsing**: OkHttp + Jsoup DOM parser

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


## Release signing

Releases are signed with the Electros release key using APK Signature Scheme v3 key rotation
(lineage: old debug key → release key), so installs signed with the old debug key keep updating.

- One-time setup: `scripts/create-release-keystore.sh` creates `~/.electros-signing/electros-release.p12`,
  the rotation lineage and stores the password in the macOS Keychain (`electros-release-keystore`).
  **Back up `~/.electros-signing/` and the password** — without them no further updates can be signed.
- Each release: `scripts/sign-release.sh [out.apk]` builds `assembleRelease` unsigned and signs it with the lineage.
- Do not sign releases with `ELECTROS_KEYSTORE` alone: an APK signed by the release key without the lineage
  is rejected as an update by installs that are still on the debug key.
