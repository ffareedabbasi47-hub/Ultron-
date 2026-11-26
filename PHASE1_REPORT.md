# ULTRON Phase 1 — change record (controlled upgrade, no rewrite)

Backup of the untouched original: `ultron_backup_original/` (kept next to the project when delivered as zip).

## Modified files (14) / new files (5)
Modified: MicCapture, LiveSessionEngine, LiveSessionService, OverlayLight, PersonalWake, WakeWord, Mfcc, WakeMatcher,
SettingsRepository (+wakeFirst), HomeScreen (tap logic), AppRoot (no double ring), WakeEnroll (3 phrases UI), CoreSettingsScreens (reset step).
New: domain/wake/WakePhrase.kt, domain/live/EdgeLightMode.kt, domain/live/MicTapPolicy.kt, tests PhraseWakeMatcherTest, MicAndEdgeTest.

## Bugs found -> fixed
1. Service observer saw STOPPED while start was still loading settings -> service could stop itself / zombie mic ("ON->OFF"). Fix: STARTING claimed synchronously.
2. Stop during STARTING left an orphan recorder. Fix: re-check after mic.start, release.
3. MicCapture.recover() could re-open a recorder after Stop (duplicate AudioRecord). Fix: lock + running check, stop() flips flag first.
4. Rapid taps toggled start/stop. Fix: TapGuard (700 ms) + MicTapPolicy (ignore during STARTING); real permission re-checked on tap.
5. Cloud-call fallback set STOPPED (service shutdown). Fix: PAUSED + carried message.
6. Wake: one phrase only, no per-phrase reply, trailing command broke the match (global CMVN), own TTS could wake in AEC mode, no offline start without cloud key. Fixed (see below).
7. Overlay: ~120 path draws/frame, no state modes, alpha>0.8 can block touches on Android 12+, in-app + system ring doubled. Fixed.
8. Wake lock never re-acquired after its 4 h timeout. Fixed. Notification now shows "mic blocked by Android" when silenced.

## New behaviour
- Wake phrases: Hey ULTRON / Hello ULTRON -> "Yes Boss?", Wake up ULTRON -> "I'm awake, Boss." Offline MFCC+DTW on the user's own recordings
  (old "Hey Ultron" recordings migrate automatically). Per-phrase thresholds, ambiguity rule, cooldown, sensitivity slider.
- Wake-first option (default off): mic starts armed, nothing uploaded until the wake phrase. Without Gemini key/consent but with recordings: offline wake-only.
- Edge light modes: IDLE / LISTENING / USER_SPEECH / THINKING (breathing) / SPEAKING / WAKE burst; per-state FPS cap; stops when screen off.

## Honest limits
- Wake is speaker-dependent template matching, NOT a trained neural keyword spotter. Expect some misses/false triggers; accuracy must be measured on a phone.
- After the wake, the command still needs Gemini (existing design). Offline = wake only.
- Overlay cannot show on lock screen, secure/FLAG_SECURE screens, or where the OEM revokes the permission.
- Android does not allow hidden mic: notification stays; Android may silence the mic while another app/call uses it.

## Oppo / ColorOS survival test
1. Settings -> Battery -> ULTRON -> Allow background activity + Allow auto-launch; Settings in-app "Battery" -> Unrestricted.
2. Recents: lock ULTRON (pull-down/lock icon on the card).
3. Start mic, press Home, open YouTube 10 min, lock screen 15 min, say each wake phrase; check notification still "Microphone active/Armed".
4. If it dies: `adb shell dumpsys activity services com.ultron.ai` and Diagnostics screen log.

## Device checklist still TO DO on a real phone
Items 1-15 of the brief that need hardware: real one-tap/10-tap behaviour, 3 real-voice phrases, command after wake, TTS self-trigger, music/video,
app switching, lock/unlock, service survival, overlay over YouTube/games, permission revoke, airplane-mode wake, AI chat/avatar regression.

---
## Update 2 (user feedback round)

1. **Mic dabane ke baad light nahi**: armed/SLEEP state = `EdgeMode.OFF` (system overlay + in-app light). Light + ULTRON sirf wake word hit hone par (wake flash, phir normal modes). "Wake-first" default ON; iske liye user ki wake recordings (min 3 per phrase) chahiye, warna purana LISTENING behaviour (light ke saath) chalta hai. Mic phir bhi Android ke recording indicator (green dot) ke saath hi chalta hai: hidden mic nahi.
2. **3D models**: root cause `AvatarView.Bridge` me constructor lambda `onEvent` ka member function `onEvent` se shadow hona (infinite self-call, avatar ready event kabhi nahi pahunchta). Fix: lambda ka naam `deliverEvent`. Device par verify nahi hua.
3. **Phone switches** (`UltronAction.SetToggle`): Wi-Fi, hotspot, Bluetooth, airplane, mobile data, location, NFC = Settings screen/panel kholta hai, phir user-enabled Accessibility service se switch dabata hai aur state dobara padhke confirm karta hai. Torch = seedha CameraManager (koi permission/accessibility nahi). Airplane ON se pehle haan/nahi confirmation. Limits: Android 10+ par koi app ye switches seedha flip nahi kar sakta; Accessibility off ho to sirf screen khulti hai; OEM layout alag ho to "switch nahi mila" bolta hai; background se activity launch block ho to notification-tap route. Hotspot/mobile data me "single switch" guess band hai (galat switch na dabe).
   Extra settings screens: hotspot, airplane, data, location, battery, apps, storage, NFC, date, language, accessibility, security, network, DND. Voice-call tool `phone_toggle` bhi add.
4. **YouTube play**: query ka YouTube search page fetch karke pehla `videoId` nikalta hai aur `watch?v=` link kholta hai (app autoplay karta hai). Na mile to results page + clear message. Page structure badle to fallback chalega. Playback shuru hua ya nahi verify nahi hota.

Tests: pure logic 79 + 14 (parser/toggle/YouTube) pass. Android code compile/run nahi hua (sandbox me SDK nahi): pehle build karke test karo.

---
## Update 3 (Master Task, round 1)

**Audit (partial, honest):** GLB integrity ok for both models (header/length, buffers, embedded JPEG textures, no external refs). Mic pipeline: single AudioRecord, armed SLEEP keeps mic running when wake recordings exist (no stop/start per command). Echo: TtsEchoGate strict mode on wake path. Not yet reviewed: AssistantEngine, TurnController, full Manifest/dependency/lint audit. No bug was *reproduced* on a device (no device/SDK here).

**New actions** (typed, validated, parsed offline):
- `AdjustVolume` — AudioManager.adjustStreamVolume(MUSIC), verified by reading the volume back. Ringtone volume untouched.
- `SetBrightness` — needs WRITE_SETTINGS (user grants via Settings screen; ULTRON opens it). Value read back after writing. Turns auto-brightness off.
- `SetDnd` — needs notification-policy access (user grants). State read back from NotificationManager.
- `MediaControl` PAUSE/PLAY/NEXT/PREVIOUS/CLOSE_YOUTUBE — public media-key dispatch. Pause/play verified with AudioManager.isMusicActive. NEXT/PREVIOUS cannot be verified (Android does not report track change) and the reply says so. "YouTube band karo" = pause + Home; an app cannot kill another app.
- YouTube play: after opening the video waits 5 s and checks isMusicActive; reports playing only if audio became active, and says so when it cannot tell (audio already playing before / nothing detected).

Manifest: added WRITE_SETTINGS, ACCESS_NOTIFICATION_POLICY (both user-granted special accesses).
Tests: parser/validator 20 pass + 79 domain/wake/live pass. Android-side code compiled/run on device: NO.
