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
