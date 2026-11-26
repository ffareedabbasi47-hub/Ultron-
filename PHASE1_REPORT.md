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
