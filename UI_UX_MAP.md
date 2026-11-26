# UI / UX map (static reading of source)

**Evidence level for everything below: source reading + a successful compile in CI. No screen was run on an emulator or device and
no screenshot was captured, so visual verification is INCOMPLETE** (layout, contrast, touch targets, TalkBack, landscape, IME and
insets are NOT verified). The only rendered check done in this project was the avatar WebGL page in headless Chromium on a PC
(it loaded two GLB models and drew them), which says nothing about the Android WebView.

Classification used: IMPLEMENTED BUT NOT VERIFIED (handler exists and calls a real repository/service, never run) unless stated.
"Interactive controls" is a rough count of `onClick`/switch/slider/chip/button occurrences inside the screen function, not a verified list.

## 1. Navigation structure
* Entry: `MainActivity` → `AppRoot` (`ui/AppRoot.kt`): `ModalNavigationDrawer` (gestures only on Home/when open), `NavHost` (start `home`), bottom bar on Home/Scan/Memory/Chat (Home · Scan · centre Live mic · Yaadein · Chat), a global `LiveEdgeLight` overlay when live.
* Drawer (after this change): Home, Yaadein, Chat · KAAM: Markets, Marketing, Documents, Website/Coding, Live aur Assistant, Scan/Camera, Apps kholna · AI STUDIO: AI Brain, Awaaz Studio, Avatar aur Models · SYSTEM: Settings, About. Items duplicated on Home (Gaane, Padhai, Journal, Mausam, Aaj, Mood) or inside Settings (Rules, Privacy, Activity, Diagnostics) were removed from the drawer (still reachable, see table).
* Home also links: bell → Notifications, ULTRON title → General, composer send → Chat (pending prompt), attachment → OCR into the composer.

## 2. Route inventory (29 routes + chat argument)
`Entry points` = places in source that navigate to it. All 29 have at least one.
| Route | Screen (file) | Entry points | Controls | Network | Permissions | Status |
|---|---|---|---|---|---|---|
| `home` | HomeScreen (`ui/home`) | start, bottom bar, drawer | 7 + dock mic | weather (Open-Meteo), Gemini via live | RECORD_AUDIO (+POST_NOTIFICATIONS on 33+) on mic tap | NOT VERIFIED |
| `chat?mode=chat|study` | ChatScreen/ChatViewModel | bottom bar, drawer, Home composer, Padhai card | send/stop/retry/history/copy/clear/consent dialog | Gemini | — | NOT VERIFIED |
| `scan` | ScanScreen (`GaaneScanScreens.kt`) | bottom bar, drawer | 12 | none (ML Kit on device, system picker/camera app) | none | NOT VERIFIED |
| `memory` (Yaadein) | MemoryScreen | bottom bar, drawer | 15 | none | none | NOT VERIFIED |
| `journal`, `mood` | JournalScreen, MoodScreen | Home quick action / Mood card | 13 / 10 | none | none | NOT VERIFIED |
| `today` | TodayScreen (`TodayWeatherScreens.kt`) | Home "Aaj" card, Notifications | 8 (reminders) | none | POST_NOTIFICATIONS | NOT VERIFIED |
| `weather` | WeatherScreen | Home "Mausam" card | 4 (retry, city field, save) | Open-Meteo (direct) | none | NOT VERIFIED; parsing unit-tested |
| `gaane` | GaaneScreen | Home quick action | 4 (YouTube search handoff) | none (opens YouTube app/web) | none | NOT VERIFIED (never claims playback) |
| `voice` (Awaaz Studio) | VoiceStudioScreen | drawer, Settings, Voice engine | 22 (engine/voice lists, preview, rate/pitch/volume, online voice, lip-sync switch) | Gemini TTS only if enabled | none | NOT VERIFIED |
| `ai` (AI Brain) | AiScreen | drawer, Settings, Chat error, Notifications | 18 (key save/show/delete, model radio, style, history slider, streaming, timeout, retries, rules, consent) | Gemini `models.list` | none | NOT VERIFIED |
| `connect` | ConnectScreen | AI Brain → "Connectivity kholo" | 2 (Gemini connection test) | Gemini | none | NOT VERIFIED |
| `general` | GeneralScreen | Settings, Home title | 11 (theme, font, text size, motion, low-performance, language, name…) | none | none | NOT VERIFIED |
| `privacy` | PrivacyScreen | Settings | 7 (rules, consent, delete chats, reset all) | none | shows real mic/notification/accessibility/overlay/live state | NOT VERIFIED; stale rows fixed |
| `access` (Live aur Assistant) | AccessibilityScreen (`HubScreens.kt`) | drawer, Settings | 12 + `WakeEnrollSection` (record/test/delete, sensitivity) | none | RECORD_AUDIO, system toggles | NOT VERIFIED |
| `avatar` | AvatarStudioScreen | drawer, Settings | 14 (select, import GLB, https download, delete, wallpaper) | https GLB download (user URL) | none | NOT VERIFIED |
| `settings` | SettingsHubScreen | drawer | search + 12 rows | none | none | NOT VERIFIED |
| `rules`, `backup`, `policy`, `voiceengine`, `activity`, `notifs` | RulesScreen, BackupScreen, PolicyScreen, VoiceEngineScreen, ActivityScreen, NotificationsScreen | Settings rows / Home bell | 0–4 each | none | none | NOT VERIFIED |
| `diag`, `about`, `automation` | DiagnosticsScreen, AboutScreen, AutomationScreen | Settings / drawer | 4 / 0 / 2 | Gemini test in diagnostics (none else) | none | NOT VERIFIED |
| `markets`, `marketing`, `docs`, `coding` | MarketsScreen, MarketingScreen, WorkScreen ×2 (`WorkScreens.kt`) | drawer | 22–24 each | none (no live feed; manual prices) | none | NOT VERIFIED |

### Routes removed in this change (unreachable, found by reference search)
`pc`, `connectors`, `info/{id}` and their placeholder screens: no control in the app navigated to them.

## 3. Home screen (HomeScreen.kt) in detail
Hierarchy: `Box` { avatar (`AvatarHero`, behind) ; `Column` { TopBar (menu, title, bell) ; greeting + user name ; free space (2D core only when 3D is off/failed) ; Notice (permission/consent/error only) ; confirmation card (only while a risky action awaits yes/no) ; quick actions (Gaane, Padhai, Journal) ; info cards (Mausam, Aaj, Mood) ; composer } }.
* Changed in this pass: the "Agla reminder" box was removed (reminders are on the Aaj card/screen); quick actions 52→44 dp, info cards 104→88 dp.
* States: Notice shows only denied mic, STT/live message, consent needed, AI error (Retry / AI Brain / Allow). Weather card: loading "…", unavailable "—/Data nahi", available temperature with description, or "Purana data" when stale.
* Accessibility: the core and the title have content descriptions; other cards rely on their text. Not verified with TalkBack.
* Known limitations: avatar placement behind cards depends on device aspect ratio (not tested); the WebView avatar can fail on devices without WebGL2 → falls back to the 2D core.

## 4. Button / action audit — highlights traced to their final effect
| Control | Traced effect | Class |
|---|---|---|
| Dock mic / avatar tap (Home) | `onMicTap` → permission request → `LiveSessionService.start` → `LiveSessionEngine.start` → `MicCapture.start`; tap while live → `LiveSessionService.stop` | IMPLEMENTED BUT NOT VERIFIED |
| Composer send (Home) | stores `PendingPrompt`, navigates to Chat which submits via `TurnController` → `AssistantEngine` → `GeminiDirectProvider` | IMPLEMENTED BUT NOT VERIFIED |
| AI Brain → Save key | `SettingsRepository.setGeminiKey` (Keystore AES-GCM) + sets `cloudConsent = true` | IMPLEMENTED BUT NOT VERIFIED |
| Connectivity → Gemini test | `provider.status()` → `GET models` with key header | IMPLEMENTED BUT NOT VERIFIED |
| Weather → Retry / Save city | `WeatherRepository.refresh()` / `settings.update(weatherCity)` (Home and Weather observe the same state) | IMPLEMENTED BUT NOT VERIFIED |
| Wake word → Record / Test / Delete | `PersonalWake.record` (opens its own AudioRecord only when live is stopped) / `test` / `clear` | IMPLEMENTED BUT NOT VERIFIED; accuracy unknown |
| Privacy → delete chats / reset all | Room `deleteAll` + `settings.resetAll` after a confirm dialog | IMPLEMENTED BUT NOT VERIFIED |
| Avatar Studio → GLB import / download | `AvatarRepository` validates header, length, 50 MB cap; https only | IMPLEMENTED BUT NOT VERIFIED (logic partly unit-tested: `GlbFiles`) |
| Avatar Studio → Wallpaper | `ACTION_CHANGE_LIVE_WALLPAPER` intent, falls back to chooser | PARTIAL: built-in model only |
| Accessibility screen toggles | open Android settings pages; ULTRON cannot enable the service itself | IMPLEMENTED BUT NOT VERIFIED |
| Any control that depends on Gemini without a key | shows the "set key" state | by source reading |
No control was found with an empty handler or a hard-coded "success" in the files read. Controls on Markets/Marketing/Documents/Coding/Journal/Mood/Yaadein/Scan were NOT individually traced in this pass: they call local Room DAOs or on-device OCR by their imports, but that is not the same as proof; treat them as UNKNOWN-UNTIL-RUN.

## 5. Visual / interaction audit status
Everything in the requested visual checklist (colours, glass, typography/scaling, avatar placement, drawer, composer, IME/insets, landscape, small screens, TalkBack, contrast, touch targets, reduced-motion behaviour) is **not verified**: no emulator or screenshots were available. Decisions made without visual proof: only box-count reductions on Home/menu/settings; the visual identity was kept.
Concrete things to check first on a device: Home overlap of cards over the avatar on short screens; drawer length (now 13 items); 44 dp quick-action height vs the 48 dp touch-target guideline (**likely too small** — see AUDIT_REPORT P3-2); text scale 1.35 on the 3 info cards.
