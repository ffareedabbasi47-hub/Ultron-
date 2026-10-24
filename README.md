# ULTRON AI (Android + secure backend)

Native Kotlin / Jetpack Compose personal assistant with a Node proxy that keeps provider API keys server-side.
Configured creator record: **Fardeen** (app configuration, not proof of authorship).

## Status — read this first

| Part | Verified how |
|---|---|
| `backend/` (Node proxy, Gemini adapter, weather) | **14/14 tests pass** (`cd backend && npm test`, run in this build session) |
| Pure-Kotlin logic (`domain/*`, `Redactor`) | **19/19 unit tests pass**, compiled with Kotlin 2.0.21 outside Gradle (the Android SDK/Maven were unreachable here) |
| Android app (Compose UI, Room/SQLCipher, TTS/STT, WorkManager, ML Kit, navigation) | **Written but NOT compiled or run on a device/emulator.** First `./gradlew assembleDebug` may surface compile errors to fix. No UI/instrumentation tests exist yet. |

Nothing below marked "Working" should be trusted until you build and try it on a device.

## Requirements
- JDK 17+ (21 works), Android Studio Ladybug+ or Android SDK 35 + CLI, Gradle wrapper included (8.10.2).
- Node 20+ for the backend.

## Build with GitHub Actions (no local Android Studio needed)
1. Create an empty GitHub repo, then from this folder: `git init && git add . && git commit -m "ULTRON AI" && git branch -M main && git remote add origin <repo-url> && git push -u origin main`
2. Open the repo's **Actions** tab. **Android build** runs automatically (also via *Run workflow*).
3. When green, download **ultron-debug-apk** from the run's *Artifacts* and install the APK.
4. If it fails, download **build-reports** (or copy the red log lines) — the first build may have compile errors to fix; this project has not been compiled yet.
5. Optional: *Settings → Secrets and variables → Actions → Variables* → add `ULTRON_BACKEND_URL` (public URL only). **Never** add API keys to the app build.
Workflows: `android.yml`, `backend.yml` (Node tests), `secret-scan.yml` (gitleaks). The APK is a debug build (not release-signed).

## Build the app
```bash
# set sdk.dir in local.properties (Android Studio does this), then:
./gradlew assembleDebug
./gradlew testDebugUnitTest          # includes StreamProtocol (kotlinx.serialization) test
# optional: bake your backend URL (public, non-secret)
./gradlew assembleDebug -Pultron.backendUrl=https://your-backend.example.com
```
The URL/token can also be entered in the app: Menu → Network / Model Usage.

## Run the backend
```bash
cd backend
cp .env.example .env     # fill GEMINI_API_KEY and APP_TOKENS (long random). Never commit .env
npm test
npm start                # http://localhost:8787
```
Deploy on any Node host (Render, Fly.io, Railway, VPS) behind HTTPS; set the same variables as environment secrets.
It is **not deployed** by this project. Emulator → local backend: `http://10.0.2.2:8787` (debug builds allow cleartext only to 10.0.2.2/localhost).
Endpoints: `GET /health` (public), `GET /v1/models`, `POST /v1/chat/stream` (SSE), `GET /v1/weather?city=` (Bearer token).

## Architecture
```
Compose UI ──► ViewModels / screens ──► TurnController (one in-flight turn, derives real AssistantState)
                                          │
                                   AssistantEngine
            identity fast-path ─► CommandParser→ActionValidator→DeviceActionRunner (intents)
                                          └► ProxyProvider (OkHttp SSE) ──HTTPS──► backend ──► Gemini
Room+SQLCipher (key in Android Keystore) · DataStore settings · TtsController / SttController · WorkManager reminders · ML Kit OCR (on-device)
```

## Permission map
| Feature | Permission | When |
|---|---|---|
| Chat, weather | INTERNET | always (no prompt) |
| Live voice | RECORD_AUDIO | at first mic tap, with explanation |
| Reminders alert | POST_NOTIFICATIONS (13+) | when user sets a reminder |
| Scan | none (system camera app / photo picker) | — |
Not declared: CAMERA, location, storage, accessibility, foreground service, overlay, QUERY_ALL_PACKAGES.

## Feature matrix (by code; unverified on device)
| Feature | State |
|---|---|
| Chat: history, rename/delete/export, streaming, cancel, retry, bounded retries, consent | Implemented — needs backend + token |
| Live voice (STT→AI→TTS, barge-in, stop) | Implemented — needs mic permission |
| Awaaz Studio (real engine/voice list, preview, persisted voice/rate/pitch/volume, missing-voice warning) | Implemented |
| Home / drawer / bottom nav (screenshot layout) | Implemented |
| Journal, Mood, Yaadein CRUD + export + delete-all | Implemented |
| Aaj + reminders (WorkManager, approximate timing) | Implemented — needs notification permission |
| Mausam | Implemented — needs backend + city; honest unavailable state otherwise |
| Scan OCR (Latin/Devanagari, on-device) | Implemented |
| App launch, YouTube search handoff, settings screens, remember, stop | Implemented (never claims playback) |
| Padhai (study mode) | Implemented — needs backend |
| Wake word / background listening | **Not built** (needs real engine + foreground service) |
| Screen Samjho (MediaProjection) | **Not built** |
| 3D avatar renderer, model catalog/downloads | **Not built** (animated core only) |
| Accessibility automation, PC↔Phone, QR scan, smart model routing, web search, Dream Journal | **Not built** |
| Emergency SOS | **Not built** |

## 3D avatar, custom models and live wallpaper

| Piece | Where | Notes |
|---|---|---|
| Browser/WebView avatar | `app/src/main/assets/avatar/ultron-avatar.html` | Raw WebGL2, no libraries. Original procedural ULTRON bust + a GLB loader (embedded textures, base-colour + metallic/roughness maps, node transforms, auto-fit). No skeleton animation, no Draco/meshopt/KTX2, no normal maps. |
| In-app view | `ui/avatar/AvatarView.kt`, `AvatarHero.kt` | Home screen shows it when an avatar other than the 2D core is selected; falls back to the core if it fails or Low performance is on. WebView is locked down (own https origin only, no file access, other requests answered 403). |
| Avatar Studio | `ui/avatar/AvatarStudioScreen.kt` (menu → Avatar Studio) | Select avatar, import a `.glb` with the system picker, or download from an `https://` link with real progress/cancel. Files are validated (GLB header, declared length, 50 MB cap) and stored in app-private storage. |
| Live wallpaper | `platform/wallpaper/UltronWallpaperService.kt` + `avatar/gl/AvatarGlRenderer.kt` | Native OpenGL ES 3.0 port of the built-in avatar (no MSAA/bloom, ~30 fps, renders only while visible). Built-in model only. |
| Pure-Kotlin core | `avatar/{Mat4,Geometry,UltronScene}.kt` | Unit-tested on the JVM (`AvatarTest`). |

Not verified: nothing in the Android layer (WebView embedding, GLSurfaceView wallpaper, downloads) has run on a device or emulator.
There is deliberately **no model catalog**: licences are the user's responsibility (CC0 / CC-BY / own work); ripped film/game models must not be used.
Standalone viewer: open `ultron-avatar.html` in a browser (⚙ opens the controls; `?embed=1` hides them).

## Known limitations / next steps
1. Build, fix compile issues, add Compose UI + instrumentation tests.
2. TTS voice quality depends on device engines; Hinglish in Latin script is read by an `en-IN` voice.
3. Pick a licensed wake-word engine, then add a microphone foreground service.
4. Add MediaProjection + vision flow with visible indicator and consent.
5. Choose a GLB renderer and verify licences before any avatar catalog.

## Gemini key, Home voice, new screens (latest)

- **AI Brain → Gemini API key**: paste your own key (stored Keystore-encrypted, never in APK/repo/logs). With a key saved, chat streams straight from Gemini (fastest route); without it, the backend proxy is used. Model list comes from your key. A key on a rooted phone can be extracted — the proxy is safer.
- **Speed**: direct route, minimal thinking, token caps, connection prewarm, shorter history default (8), speech starts after the first ~12-character sentence.
- **Live page removed.** Voice happens on Home: tap the core or the dock mic; the reply/transcript shows on Home. Typing on Home also answers on Home.
- **New screens** (all local data, no fake feeds): Markets (manual prices only — no live market feed), Marketing drafts, Documents, Website/Coding (locked-down HTML preview), Settings hub, ULTRON ke rules, Accessibility/Battery shortcuts, Activity, Notifications.
- Unavailable (shown honestly in-app): live market prices, Notion/Todoist/Linear connectors, PC pairing, restore-from-backup, AccessibilityService automation.
- Status: backend tests and pure-Kotlin tests were run; the Android/Compose layer has **not** been compiled or run on a device — expect small first-build fixes in GitHub Actions.

### Guide-alignment pass
Markets now has Watchlist/Portfolio/Analysis (NSE/BSE label, validated symbols, manual prices only). Marketing has Business/Content/Calendar. Documents/Coding can import text files. Settings hub is searchable and grouped (Personal, Assistant, Connectors, Backup, Advanced, Optional, Battery saver, Appearance, Activity); new Voice engine, Connectors, Backup, Privacy Policy and Accessibility/Battery screens report real status only. Yaadein has source filters and named delete confirmation; Chat has copy and clear chat. Drawer/dock/top bar follow the reference screenshots. Not available (stated in-app): live market data, connectors, PC pairing, Voice Guardian, Screen Samjho (MediaProjection), QR scanning, wake word. Android layer is still uncompiled here (no Google Maven access in this sandbox).

## Latest changes
- Home fits one screen (MAYA-style layout); conversation shows only in Chat.
- 7 colour themes (Midnight navy, Deep purple, Ultron red, Emerald, Gold, Ice blue, AMOLED), 6 system font styles and a text-size slider (Settings > General).
- Voice list shows only the best online/high-quality voices; best one is auto-picked.
- Original menacing-calm persona (no film dialogue, no actor voice).
- Transparent, forward-facing 3D avatar with expressions (lip-sync is approximate; TTS gives no phoneme data).
- Removed rows for features that do not exist (connectors, guardian, sub-agents, maps, screen understanding, web search, PC link).
- Tests: `backend` `npm test`; Kotlin unit tests under `app/src/test`. The Android layer has not been compiled in this environment; build via GitHub Actions / Android Studio.

## Live conversation (v0.2)
**Root cause of the old mic open/close loop:** every utterance created a new `SpeechRecognizer` session (utterance-oriented, ends by itself) and Home cancelled the mic when you left the screen.
**Now:** one `AudioRecord` (VOICE_COMMUNICATION, 16 kHz) owned by `MicCapture`, kept open by `LiveSessionService` (foreground, microphone type, notification with Stop/Pause/Wake). VAD (`domain/live/Vad.kt`) decides speech boundaries in memory with hysteresis, pre-roll and an adaptive noise floor; the recorder is never reopened for that. Each finished utterance (not continuous audio) is sent to Gemini for transcription with your own key and cloud consent, then the normal reply pipeline runs; after ULTRON speaks, it returns to LISTENING by itself.
- **Sleep timer** (Off/5/10/15/20 min): monotonic clock, paused while thinking/speaking, reset only by verified user speech (a non-empty transcript).
- **Echo:** AcousticEchoCanceler + NoiseSuppressor are attached when the device offers them, and their real enabled state is shown in Diagnostics. Default "safe" mode ignores speech that starts while ULTRON talks (plus a short tail); "Full-duplex" allows barge-in and relies on the device AEC. Video/music from other apps cannot be perfectly removed; use a headset for best results.
- **Wake phrase:** no offline wake-word engine is bundled. Optional opt-in cloud check in SLEEP (short phrases are sent to Gemini). Otherwise sleep releases the mic and the notification/Home button wakes it.
- **Voice:** Gemini TTS online voices (your key), played through one AudioTrack, sentence-pipelined; failures fall back to the device voice. Model name is editable.
- **Screen control:** optional Accessibility service (click/scroll/type/back/home/read, plus a bounded 12-step agent). Risky taps (send/pay/delete/call/confirm…) ask "haan/nahi"; password fields and the package installer are never touched.
- **Default assistant:** `VoiceInteractionService` so ULTRON can be picked as digital assistant; the assist gesture opens ULTRON and starts live.
- **Edge light:** rotating light around the screen while live (in-app, or over other apps with the overlay permission).

**Verified here:** JVM unit tests for VAD, sleep clock, state transitions, echo gate, wake phrase, command parsing, agent JSON, Gemini wire formats. **Not verified:** everything that needs Android/a device (AudioRecord, AEC behaviour on Oppo, foreground service, accessibility, assistant role, overlay, Gemini TTS model availability).

### Avatar on Home and lip-sync
The avatar is drawn behind the Home cards (no waveform/label on it). With the online voice, the mouth follows the real playback position of the audio (`domain/live/LipSync.kt`: loudness = jaw opening, brightness = round/wide). With the device voice no audio is available, so the page's approximate motion is used. Your own GLB models can be imported in Avatar Studio; game-ripped models (e.g. Marvel Ultron `.blend` files) are not bundled in this repo.
