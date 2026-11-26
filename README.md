# ULTRON AI (Android)

Native Kotlin / Jetpack Compose personal assistant. **There is no ULTRON backend or proxy**: the app talks directly to
Google Gemini (with the user's own API key) and to the Open-Meteo weather API. Configured creator record: **Fardeen**
(application configuration, not proof of authorship).

## Status (what is actually verified)

| Level | State |
|---|---|
| Implemented in source | Everything listed under "Features" |
| Covered by JVM unit tests | Pure logic only: command parsing, validators, persona/identity, Gemini wire formats, VAD/sleep clock/echo gate, lip-sync analysis, WAV, wake-word matcher (synthetic audio), Open-Meteo parsing, avatar maths |
| Compiled | Yes, by the GitHub Actions workflow (`Android build`: unit tests + `assembleDebug`); see `TEST_REPORT.md` for the exact run |
| Run on an emulator/device | **No.** Nothing in the Android layer (mic, AEC, foreground service, accessibility, overlay, assistant role, WebView, TTS) has been exercised on a device by the authoring sessions |
| Verified against real external services | **No** (Gemini and Open-Meteo calls are unit-tested at the parsing level only) |

See `AUDIT_REPORT.md`, `UI_UX_MAP.md`, `BACKEND_REMOVAL_PLAN.md`, `TEST_REPORT.md`.

## Architecture
```
Compose UI ─► ViewModels / screens ─► TurnController (one in-flight turn)
                                          │
                                   AssistantEngine
   identity/small-talk fast path ─► CommandParser ─► ActionValidator ─► DeviceActionRunner (intents, accessibility)
                                          └► GeminiDirectProvider ──HTTPS (x-goog-api-key)──► generativelanguage.googleapis.com
Live voice: LiveSessionService ► MicCapture (one AudioRecord) ► VAD ► GeminiTranscriber ► reply ► TtsController/CloudVoice
Weather:    WeatherRepository ──HTTPS──► geocoding-api.open-meteo.com + api.open-meteo.com (no key, no location permission)
Storage:    Room + SQLCipher (key wrapped by Android Keystore) · DataStore settings · SecureStore (Gemini key) · WorkManager reminders · ML Kit OCR (on device)
```

### Gemini key (read this)
The user pastes their own key in **Menu → AI Brain**. It is stored encrypted (Android Keystore AES-GCM) and only sent in the
`x-goog-api-key` header over HTTPS. It is never in the APK, BuildConfig, Gradle files, workflows or logs.
**A key stored on a phone can still be extracted from a rooted or modified device**; encryption does not make that impossible.
Restrict the key in Google AI Studio and do not share a build in which you have saved it. There is deliberately no relay
server, serverless proxy or third party in between.

### What leaves the phone (only after "Cloud AI allow")
Chat text + recent context → Gemini. Live voice: each finished utterance's audio → Gemini for transcription. Optional Gemini
online voice: reply text → Gemini TTS. Agent screen control: the visible screen text → Gemini. Weather: the city name → Open-Meteo.
Nothing is sent without consent where consent applies; diagnostics are redacted and kept in memory.

## Build
- JDK 17, Android SDK 35, Gradle wrapper included (8.10.2). No Node.js needed.
- GitHub Actions: `android.yml` (unit tests, debug APK, reports), `secret-scan.yml` (gitleaks over the tree and history).
```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```
The APK is a debug build (not release-signed).

## Features (by code; unverified on device)
| Feature | Needs | Offline? |
|---|---|---|
| Chat, Padhai (study mode), history, export | Gemini key + consent | No |
| Live voice (persistent mic, VAD, sleep timer, barge-in option) | Mic permission, Gemini key + consent | No (transcription is cloud) |
| Phone voice (device TTS, lip-synced) and Gemini online voice (off by default, small free quota) | — / Gemini key | Phone voice: yes |
| Offline personal wake word ("Hey Ultron", speaker-dependent template matching, you record it) | Mic permission + enrollment | Yes |
| Screen control (AccessibilityService) + bounded agent, default-assistant role, overlay edge light | User enables in Android Settings | Basic commands yes; agent needs Gemini |
| App launch, YouTube search handoff, reminders, journal, mood, memory (Yaadein), documents/coding drafts, manual markets watchlist | — | Yes |
| Scan / OCR (ML Kit, on device) | — | Yes |
| Weather | Internet + a city you type | No (stale data is labelled) |
| 3D avatar (built-in), your own GLB import, live wallpaper, optional bundled models | — | Yes |
Not built: MediaProjection screen capture, live market feed, connectors (Notion etc.), PC pairing, web search, Voice Guardian, maps.

## Wake word
Record the phrase 4 times (Menu → Live aur Assistant). MFCC + DTW matching runs on the phone, free and private. It is
speaker-dependent and not a neural engine: expect misses and false triggers; tune Sensitivity and use Test.

## Quota notes
Gemini online voice is off by default (tiny free TTS quota). Every live utterance costs one transcription request plus one chat request.

## Avatar models
Import your own `.glb` in Avatar Studio (Draco/skeleton animation/normal maps unsupported). Optional models can be bundled by placing
`.glb` files in `app/src/main/assets/models/` (copied once on first launch). Check the licence of any model you add and keep the repository private if it forbids redistribution.
Lip-sync works on the built-in avatar only.

## Settings migration
Older builds stored a backend URL, a backend access token and a "selected backend model". On first launch of this version
`SettingsRepository.migrateRemovedBackend()` deletes exactly those three values; all other settings, the Gemini key and the database are untouched.
