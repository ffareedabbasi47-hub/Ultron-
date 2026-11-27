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

---
## Update 4 (visual polish, overlay, capture)
- **Edge light colours** (`OverlayLight`): listening = cyan/blue comets, thinking = purple/blue, speaking/wake = multicolour ring (cyan, blue, purple, magenta, pink; brightness x0.8). Soft 300 ms fade-in, 350 ms fade-out; sleep = OFF. A new show() during a fade removes the old window first (never two).
- **Global overlay root cause (code review, not reproduced on device):** the "allow Display over other apps" dialog was shown only ONCE EVER (`overlay_asked` pref), so one dismissal disabled the edge light outside the app permanently. Now it is asked again each app run until granted. Overlay window config (TYPE_APPLICATION_OVERLAY / accessibility overlay, NOT_TOUCHABLE, alpha 0.8, single instance) was already correct. Limits: no overlay on lock screen, secure/FLAG_SECURE screens, or where an OEM blocks it.
- **3D size:** `SIZE_BOOST=1.15`, `FIT_MARGIN` 1.12→1.05 in `ultron-avatar.html`. Fit rule still keeps head-to-feet inside the screen, so when the fit rule (not the base distance) decides, the gain is ~7%, not 15%. Tune the two constants on the device. Models/textures untouched.
- **Screenshot blocked root cause:** `secureScreen` defaulted to true => FLAG_SECURE on the whole app. Now default false (new pref key `secure_screen_v2`, so an old saved "true" does not stick). The switch remains for users who want it.
- **Screenshot:** `ScreenshotSaver` (PixelCopy of ULTRON's own window → Pictures/ULTRON). **Recording:** `ScreenRecordService` (MediaProjection consent, mediaProjection foreground service + notification with Stop, MediaRecorder H.264 → Movies/ULTRON, optional MIC audio, device audio NOT recorded, 200 MB free-space check, projection/recorder/display released on stop/interrupt/task-removed). Buttons are in Menu → Privacy aur Permissions.
- Verified: JVM logic tests pass. NOT verified: any of the Android/OpenGL/overlay/capture behaviour (no SDK/device here) - run the device checklist.

---
## Update 5 (Critical Repair V2)
**1. Voice-mode conflict.** Root cause (code trace + pure-logic reproduction): `LiveSessionEngine.runStart` computed `armed = wakeOn && templatesReady && (...)`; with "Offline wake word" ON but NO recordings, `armed=false` silently selected the Gemini phone-call mode (`useCall`). Fix: single decision object `VoiceModePolicy` (ARMED / CALL / NORMAL / NEEDS_WAKE_SETUP). Wake ON + no recordings now starts nothing and tells the user (no fallback mode). Wake ON + recordings = ARMED (one AudioRecord, conversation engine only after a wake phrase). Wake OFF or "Wake-first" OFF = old behaviour unchanged. Tests: `VoiceModePolicyTest` (reproduces the old fallback case). Only one foreground mic service exists (`LiveSessionService`); `SttController` is the old per-button recogniser used by non-live screens (not removed).
**Wake word** is acoustic (MFCC+DTW on the user's own recordings, speaker-dependent), not STT text search; it is not a trained neural spotter, so false triggers/misses are possible. Not measured on real audio.
**2. Calling.** `CallContact/CallNumber/OpenDialer/EndCall`: Contacts lookup (`ContactMatcher`: never picks between different people, asks for full name; multiple numbers -> asks), spoken haan/nahi confirmation before every call, ACTION_CALL only if CALL_PHONE granted else ACTION_DIAL (reply says the call is NOT started yet), call state verified with TelecomManager.isInCall only if READ_PHONE_STATE granted. Hang up via TelecomManager.endCall (needs ANSWER_PHONE_CALLS). Permission button: Menu -> Privacy -> Calling. Dialer opening is never reported as a successful call.
**3. YouTube.** Results page is parsed into candidates (title, channel, length, age, views) and ranked (`YouTubeRanker`: topic match, recency only if "latest/new/news" is said, shorts penalised, mild views bonus). Spoken title/channel are real page data. Playback claim only if Android reports audio active afterwards. Scraping YouTube's public page can break if YouTube changes it (falls back to results page).
**4. 3D audit (both GLBs):** InfinityUltron.glb = 1 static mesh, 0 skins, 0 bones, 0 animation clips, 0 morph targets. UltronXPS.glb = 3 static meshes, same. => NO arms to lower, NO facial rig, NO lip-sync possible on these models, and they have no animation of their own. Not faked. Done instead: sharper rendering (pixel ratio cap 2 -> 3 within a 2.6 MP budget, anisotropic texture filtering; MSAA/mipmaps already existed) and honest whole-body motion on GLB models (breathing, weight shift, listening lean/tilt, thinking tilt, speaking nod driven by the speech envelope). Only the built-in procedural robot has a real jaw; its mouth follows an approximate speech envelope (TTS exposes no phonemes), not true visemes. For true lip sync/animated arms a rigged GLB (skeleton + blendshapes/ARKit visemes) must be supplied.
Tests: 87 JVM logic tests + 27 parser/call/YouTube tests pass. Nothing Android-side was compiled or run on a device.

---
## Update 6 (Personal Intelligence round 1)
Done (code + logic tests; NOT device-tested):
- **Call-aware silent mode** (`CallSilence` state machine + 1 s poller in `LiveSessionService`): call ringing/active (TelephonyManager state if READ_PHONE_STATE granted, else AudioManager MODE_IN_CALL/RINGTONE) => TTS stopped, engine paused (mic released: no call audio is ever captured, no wake reaction, no light). After the call: resumes ONLY if it was running before; if the user pressed Stop during the call it stays stopped. Resume re-opens the mic once (unavoidable: Android takes the mic during calls). Edge light stays OFF until a new wake word.
- **Alarms/timers** via the system Clock app (`AlarmClock` intents: survives reboot, correct timezone). Parser: subah/shaam/raat/AM/PM/24h, "10 minute baad", roz, weekdays. Ambiguous AM/PM => asks first. Verified with `AlarmManager.nextAlarmClock`; if an earlier alarm exists it says it cannot verify. Limits (Android): intent has no date => "kal 7:30" is refused when 7:30 would ring today; timers cannot be verified; other apps' alarms cannot be listed (only next alarm + opens Clock); dismiss only the NEXT alarm and only if the Clock app supports it.
- **Memory voice save**: "yaad rakhna ki ..." saves to the existing Room `memory` table, duplicate check, success spoken only after read-back from the DB. Existing Memories screen (edit/delete) untouched.
- **SMS / WhatsApp**: one contact resolver shared with calling (never picks between people/numbers), spoken preview + haan/nahi before sending. SMS: SmsManager when SEND_SMS granted, result per part from the sent-broadcast (RESULT_OK = handed to the radio, delivery is NOT claimed); else compose screen + optional Accessibility "Send" press. WhatsApp: wa.me link into the installed WhatsApp, then Accessibility "Send" press if enabled; WhatsApp gives no send result, so it says so.
NOT done yet (next round): media-aware audio focus audit/echo (Part 1), Fardeen owner protection + biometric/PIN (Part 4), manual memory fields (title/category/tags: needs a DB migration), unified Contact UI.

## Build fix (CI run #47)
Root cause: `val P = android.provider.ContactsContract.CommonDataKinds.Phone` - `Phone` is a class, not an object, so it cannot be stored in a variable and `P.CONTENT_URI` etc. do not resolve (8 compiler errors, DeviceActionRunner.kt:189-194). Fix: fully qualified `ContactsContract.CommonDataKinds.Phone.*`. Also made `toggleIntents` type-safe (was an `emptyArray()` inside a spread `when`, a likely second compile error). CI compile of the rest is still unverified.

---
## Update 7 (Real functionality restoration - local mode without Gemini key)
Audit finding (root cause of "needs Gemini key"): wake detection was already key-free (MFCC/DTW templates), BUT after the wake the spoken command AND the haan/nahi confirmations were transcribed ONLY by `GeminiTranscriber` (cloud). Without a key the user could wake ULTRON but not give any command.
Fix: `LocalTranscriber` (Android SpeechRecognizer fed with the already-captured utterance via `EXTRA_AUDIO_SOURCE`, Android 13+; single mic pipeline kept) is used whenever there is no Gemini key/consent (`transcribeAny`, both the command path and the confirmation path). Local commands (`CommandParser` -> `ActionValidator` -> `DeviceActionRunner`) already ran before any cloud check in `AssistantEngine.respond`. General-knowledge questions without a key now say honestly that advanced AI is not configured.
Limits: Android < 13 has no local transcription of captured audio (message says so); Google's recogniser may need internet or an offline language pack; en-IN Roman output suits Hinglish commands best (Devanagari output is not parsed); no on-device LLM exists in this build (none claimed).
Not device-tested. Calling / messaging / YouTube / alarm code is the v5-v7 code; the old APK the user tested before these versions did not contain them.

## Update 8 - Neural offline wake (ONNX) scaffold
Files: platform/live/OnnxWake.kt (new), domain/wake/WakeScoreGate.kt (new), LiveSessionEngine.kt, AppContainer.kt, gradle/libs.versions.toml, app/build.gradle.kts (+onnxruntime-android 1.19.2), assets/wake/README.md, test WakeScoreGateTest (6 tests).
Status: unit tests 89/89 OK (JVM harness). NOT compiled with Android SDK/ORT here (Maven/GitHub blocked in sandbox), NOT run on device, and NO hey_ultron.onnx exists - without the 3 model files the engine stays off and the older template matcher keeps working.
Mic ownership: one AudioRecord (MicCapture) always; frames are fed to OnnxWake only in SLEEP. No second reader exists. Live call/STT use the same recorder after wake.
Not done: separate Live-consent revoke UI, ColorOS battery guide screen, Live state-machine rename (existing LiveState kept), emulator/device tests.

## Update 9 - Gemini Live as the ONE primary AI (voice + typed chat)
Finding: LiveCall/LiveSessionEngine (Gemini Live WebSocket) were never deleted. Real causes: (1) wake-first default routed to the old STT->chat path, so Live never started after wake; (2) a failed call silently fell back to the old mode; (3) typed Chat went to TurnController/AssistantEngine (normal chat model); (4) Live transcripts were never saved to chat history.
Changes: LiveCall (+sendText, attach, Sink, muteAudio, history restore), LiveWire (+text, +history), LiveChat (new: typed->same Live session, transcripts->chat DB, honest errors), LiveTurnAssembler (new, pure), ChatViewModel.send -> liveChat (sendViaNormalChat = explicit user-chosen alternative only), TurnController (liveBusy/liveError), LiveSessionEngine (wake -> Live call, reuse running session, no silent fallback), AppContainer, AssistantEngine.touchConversation.
Tests: 95/95 JVM logic tests OK (assembler: one user+one assistant bubble, no duplicates, no invented text; wire: realtimeInput text, history not a turn). NOT verified: compile with Android SDK, real Gemini Live responses, realtimeInput text / clientContent history acceptance by the live model, device audio. Those need a CI build + phone.

## Update 10 - Visual/UX + capabilities
Found from the video: Text without explicit colour was BLACK on navy (no LocalContentColor provided) -> fixed globally in UltronTheme. Avatar box was 66% height so legs were hidden behind cards -> now spans header..nav (glass cards in front of the legs). Greeting 17sp / name 27sp semi-bold.
Themes: +Deep space, Cyber blue, Aurora, Silver glass (LIGHT palette with dark text; onPrimary chosen by luminance). Existing: Midnight navy, Deep purple, Ultron red, Emerald, Gold, Ice, AMOLED. NOT done: Custom theme/accent picker, character-scale + background-appearance settings, geometric/waveform background lines.
Web search: WebSearch.kt + WebSearchWire (Gemini Google Search grounding, user's own key, nothing hardcoded); Live tool web_search; sources written into chat as a local note; failures reported, "no sources" flagged.
Capability Registry: domain/Capabilities.kt (+ CapabilityEnv built from real permission/app/key checks in AppContainer); Live tool my_capabilities answers from it. WhatsApp package visibility added to manifest queries.
Tests: 103/103 JVM logic OK. NOT verified: Android compile, any on-device check, real search results, contrast measured by eye only on the video frames.

Fix (CI run 52): explicit types on liveCall/live/liveChat/webSearch/onnxWake/personalWake (recursive type inference error).

## Update 11 - Calling / WhatsApp / YouTube English commands
Root causes found in the parser: (1) "call my father" searched a contact literally named "my father" (possessive + relation words never matched "Papa"); (2) "WhatsApp ... message bhejo: <text>" and "Send WhatsApp message to my father: <text>" did not parse (colon form / 'whatsapp' before 'message'); (3) "Play a video about X" searched "a video about X".
Fix: ContactMatcher strips my/mere/..., expands relation groups (father/papa/dad/abbu..., mother/mummy/maa..., etc.), literal name first, never picks between different people. CommandParser: colon/saying message forms, `whatsapp <name>: <text>`, `play/show a video about X`.
Tests: 142/142 JVM logic (parser + domain) OK, 8 new in EnglishCommandTest. Not verified: Android compile (CI #52 failed before the v12 fix), any device behaviour.
Unchanged/blocked, stated plainly: lip-sync and arm pose need rigged models (both GLBs have 0 skins/bones/morph targets; whole-body motion only); neural wake needs a trained hey_ultron.onnx; full per-screen theme audit not done.
