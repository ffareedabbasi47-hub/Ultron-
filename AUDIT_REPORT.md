# ULTRON forensic audit (working tree on top of b0a8779)

Evidence levels: CONFIRMED STATIC (read in source), FIXED (edited; Android compile only proven by CI after push), FALSE POSITIVE, PLAUSIBLE BUT UNVERIFIED. No Android emulator/device runtime test was run, so nothing here is "runtime verified".

| # | Finding | Status | Action |
|---|---|---|---|
| 01 | DB passphrase decrypt failure overwrote key and deleted the DB | CONFIRMED STATIC, FIXED | `DbKeyPolicy`, `SecureStore.databaseKey` (3 retries, unreadable blob preserved), `UltronDatabase.open` renames old DB to `ultron.db.unreadable-<ts>` instead of deleting; UI note. Pure policy unit-tested (6). Keystore/Room path itself untested. |
| 02 | Default Gemini model invalid | FALSE POSITIVE (web search shows `gemini-3.5-flash-lite` documented) | Added warning when chosen model is not in the key's model list. |
| 03 | Exported `LIVE_START`/ASSIST could start mic | CONFIRMED STATIC, FIXED | Public LIVE_START filter removed; auto-start needs per-process token only `UltronSession` adds (`LaunchPolicy`, 5 tests). External intents just open the app. ASSIST/VOICE_COMMAND stay exported (required for assistant role). |
| 04 | Prewarm contacts Google before consent | CONFIRMED STATIC, FIXED | prewarm and stream require `cloudConsent`; revoking consent cancels in-flight chat. |
| 05 | Screen agent sends screen text to Gemini | PARTIALLY CONFIRMED (consent for cloud was checked inside GeminiOneShot, but UI said "Screen kabhi nahi" and no separate disclosure) | New separate `screenCloudConsent` switch, checked before reading screen and each step; cloud-off withdraws it; text corrected. |
| 06 | Transcription privacy | CONFIRMED (by design): each utterance WAV goes to Gemini when cloud consent is on; sleep/wake only sends short phrases if user opted in (`wakeCloudCheck`) | Consent gate already present in `GeminiTranscriber`; wording in Privacy screens states it. Deeper wake-path review PLAUSIBLE BUT UNVERIFIED. |
| 07 | Privacy dashboard accuracy | FIXED earlier (PermRows read real state) + screen-consent row | |
| 08 | Saving API key set cloudConsent=true | CONFIRMED STATIC, FIXED | Side effect removed; `ConsentPolicy` (6 tests). |
| 09 | WeatherRepository | Not re-audited this pass; parsing unit-tested (8). PLAUSIBLE BUT UNVERIFIED for network edge cases. |
| 10 | AI Brain status | CONFIRMED STATIC, FIXED | No automatic Google call without consent; pill reads "Key accept hui" not "Connected". |
| 11 | Reset Everything incomplete | CONFIRMED STATIC, FIXED | Also wipes wake-word data, avatar files, cache, export record, quarantined DB copies; per-step real result shown (`ResetEverything`, 3 tests). Bundled models are re-copied on next app start. |
| 12 | Backup "success" before completion | CONFIRMED STATIC, FIXED | `shareText` returns Boolean, size-capped (150k chars, avoids TransactionTooLarge), UI says "share sheet opened" only. Android cannot report share completion. |
| 13 | RecognitionStub ERROR_CLIENT | By design (system requires a service; always errors honestly). No change. |
| 14 | Wake-word engine truth/battery | PLAUSIBLE BUT UNVERIFIED (not measured; no device). |
| 15 | Docs | Partly fixed (README earlier). Privacy wording updated. |
| 16 | Debuggable/exported | Release not debuggable (minify on); exported: MainActivity (needed), accessibility/voice services (system-bound permission), wallpaper (BIND_WALLPAPER). LIVE_START removed. |
| 17 | Version mismatch | CONFIRMED, FIXED: Gradle 0.3.0 / code 3 matches About/changelog. |
| 18 | Dead UI controls | Static grep found no empty onClick/TODO/disabled-forever controls; runtime tap-through NOT done.  Routes cleanup was done in the backend-removal pass (UI_UX_MAP.md). |

## Additional bugs found
- `shareText` could crash on large payloads (fixed). `GeminiDirectProvider.stream` would have leaked `ConsentRequiredException` as uncaught (handled).

## Consent / outbound request table
| Request | Data | Gate |
|---|---|---|
| Gemini chat stream | chat text/context | cloudConsent + key (engine and provider) |
| Gemini one-shot (screen agent) | on-screen text | cloudConsent + screenCloudConsent + key |
| Gemini transcription | utterance audio | cloudConsent + key |
| Cloud TTS | reply text | cloudConsent + opt-in switch |
| models.list status/Test | API key only | automatic only with consent; explicit Test button always |
| prewarm | none (HEAD) | cloudConsent |
| Open-Meteo | city name | user-set city |

## Not done / risks
No emulator tests; Keystore failure path and UI changes are compile-checked only by CI. Regression risk: users who relied on key-save enabling cloud must now switch Cloud AI on.

## Attacker / user-eye pass (second review)
| Issue | Severity | Status |
|---|---|---|
| Voice confirmation could be satisfied by the app's own echoed prompt ("...haan ya nahi bolo" matched `^haan`) or by long ambient speech starting with "ok/yes" | High (auto-confirms a risky click) | CONFIRMED STATIC, FIXED: answers must be <=4 words and must not contain both yes and no words. Tests added. |
| Risky-click list missed Subscribe, Add money, Approve, Withdraw, Share, Enable, Hindi words | Medium | FIXED (more confirmation prompts; existing test updated: "Subscribe" now risky). |
| Accessibility `type` replaced the whole field (destroyed user's text) | UX/data loss | FIXED: appends. |
| No FLAG_SECURE: chats/journal/API-key screen in recents and screenshots | Medium | FIXED: setting "Screenshot/recents block", default ON (user can turn it off). |
| Reminder text visible on lock screen | Low-Med | FIXED: VISIBILITY_PRIVATE + generic public version. |
| `startForegroundService`/`startService` could throw (Android 12+/14 background rules) and crash | Medium | FIXED: returns false / swallowed, UI message. |
| WebView avatar: JS bridge `AndroidAvatar.onEvent` only reachable by own bundled page; navigation locked, file/content access off | OK | No change. |
| HtmlPreview of AI-generated HTML: JS on, no bridge, network blocked | Low | Accepted (no bridge/network); consider disabling JS. |
| Avatar download http redirect hop contacted before detection | Low | FIXED: network interceptor blocks non-https hops before sending. |
| Wake templates file unencrypted (voice features, not audio) | Low | NOT FIXED. |
| PARTIAL_WAKE_LOCK up to 4h while sleeping with mic running | Battery | NOT MEASURED. |
Tests: JVM harness 100 passed (80 previous domain tests + new), no Android runtime.
