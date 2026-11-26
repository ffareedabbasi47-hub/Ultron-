# Backend removal plan and result

Status key: **DONE** = changed in the commit "Remove custom backend…", compiled by the `Android build` workflow (green).
Nothing here was run on a device.

## 1. Dependency / reference map (taken before deleting anything)
| Reference | Where | Used by | Action |
|---|---|---|---|
| `ProxyProvider` (SSE client for the Node proxy) | `data/ai/ProxyProvider.kt`; built in `AppContainer`, routed by `RoutingProvider` | chat when no Gemini key | **DONE** deleted; `RoutingProvider` deleted; `AppContainer.provider = geminiDirect` |
| `StreamProtocol` / `StreamEvent` (SSE schema of the proxy) | `data/ai/StreamProtocol.kt`; also its `json` was reused by `WeatherRepository`; one unit test | proxy + weather | **DONE** deleted; `Usage` data class kept (moved into `AIProvider.kt`, Gemini reports it); weather has its own parser; SSE unit test removed (its subject no longer exists) |
| Backend weather (`GET /v1/weather`) | `data/weather/WeatherRepository.kt` | Home card, Weather screen | **DONE** rewritten to call Open-Meteo directly (see §3) |
| `backendUrl` setting + `BuildConfig.BACKEND_URL` + `-Pultron.backendUrl` + repo variable `ULTRON_BACKEND_URL` | `SettingsRepository`, `app/build.gradle.kts`, `gradle.properties`, `android.yml` | proxy, weather, Connectivity screen | **DONE** all removed |
| access token (`backend_access_token`, SecureStore) | `SettingsRepository.accessToken()/setAccessToken()` | proxy, weather, Connectivity screen | **DONE** accessors removed; stored value deleted by migration (§4) |
| `selectedModel` setting (backend model picker) | `SettingsRepository`, `ChatRequest.model`, AI Brain screen | proxy only (the direct provider uses `geminiModel`) | **DONE** removed; stored value deleted by migration |
| Connectivity screen (backend URL, token, reachability) | `ConnectScreen` | user | **DONE** now: Gemini connection test, weather note, offline note |
| Debug cleartext config for an emulator-hosted backend | `app/src/debug/res/xml/network_security_config.xml` | debug builds | **DONE** deleted; all builds are HTTPS-only (`src/main/res/xml/network_security_config.xml`) |
| `backend/` (Node: server, gemini adapter, persona, ratelimit, validate, weather, tests, `.env.example`) | repo root | nothing in the app after the above | **DONE** deleted (11 source/config files + 1 test file) |
| `.github/workflows/backend.yml` | CI | backend tests only | **DONE** deleted |
| Text mentioning "backend/proxy" in UI | Privacy, Policy, Chat consent dialog, AI Brain, Notifications, Web Search info text, changelog | user-visible claims | **DONE** rewritten to say the real route (direct to Gemini) |
| Persona comment "mirrors backend/src/persona.js", SecureStore comment, manifest comment | comments | — | **DONE** reworded |
| `.gitleaks.toml` allowlist entry `backend/test/server\.test\.js` | secret scan | the removed test file still exists in Git **history**, which gitleaks scans | **KEPT on purpose**, with an explanatory description. Removing it would make the history scan fail |

### Remaining textual matches for "backend|proxy" (each explained)
* `SettingsRepository.kt` (`migrateRemovedBackend`, `OLD_TOKEN_NAME`, `K_BACKEND_OLD`, `K_MODEL_OLD`): the migration that deletes the three obsolete stored values.
* `GeminiDirectProvider.kt`, `WeatherRepository.kt`, `AppContainer.kt`: comments / the call to the migration saying there is no backend.
* `MiscScreens.kt` changelog line "0.3.0 — custom backend hata diya".
* `README.md`, `BACKEND_REMOVAL_PLAN.md`, `AUDIT_REPORT.md`: documentation of the removal.
* `.gitleaks.toml`: see above.
* Android framework text "proxy" does not occur in app code.

## 2. AI architecture
`AppContainer.provider` is now `GeminiDirectProvider` directly. Preserved (code inspected, not changed): streaming (SSE `streamGenerateContent`), cancellation (coroutine cancel → `Call.cancel`), connect/read timeouts from the Timeout setting, bounded retry (`AssistantEngine.streamWithRetry`, 0–4, only before any text was emitted and only for retryable errors), normalised errors (`ErrorKind`), model discovery (`models.list` via the user's key), consent enforcement in the request path (`ConsentRequiredException` / `needsConsent`), history limit, redacted diagnostics.
No key → `ProviderException(NOT_CONFIGURED, "Gemini API key set nahi hai…")` and the AI Brain screen shows the setup state; there is no fallback to another endpoint and no fabricated answer.
No Gemini key is in source, BuildConfig, Gradle files, workflows, assets or the repo (secret scan green; a grep for `AIza` finds only the Redactor regex and one fake placeholder in `RedactorAndValidatorTest.kt`).
Documented limitation (README + AI Brain screen): a key stored on a device can be extracted from a rooted/modified phone.

## 3. Weather
Official docs followed: Open-Meteo forecast `v1/forecast?current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m&timezone=auto` and geocoding `v1/search?name=…&count=1&format=json`.
* `domain/weather/OpenMeteo.kt` (pure, unit-tested): city validation, response parsing (null on missing/implausible temperature — nothing is invented), WMO code → text, attribution string.
* `data/weather/WeatherRepository.kt`: https only, 12 s timeout, one in-memory geocode cache per city, a mutex against concurrent refreshes, no automatic retries (Retry button instead).
* States: Idle, Loading, Unavailable(reason, canRetry) for no city / invalid city / city not found, Available with `fetchedAtMs` (time of the successful fetch), `stale` + reason when a later refresh failed, and a "Purana data" notice when a reading is ≥ 60 min old. Offline and timeout are distinct messages. Attribution "Weather data by Open-Meteo.com" is shown with the update time and the provider's own observation time.
* Not routed through Gemini. No location permission (city typed by the user).
* Limitation: Open-Meteo's free API is for non-commercial use; check their terms before distributing the app.

## 4. Settings / storage migration
`SettingsRepository.migrateRemovedBackend()` (called once per process start from `AppContainer`, idempotent): removes DataStore keys `backend_url` and `selected_model` and the encrypted `backend_access_token`. It does not touch other settings, the Gemini key (`GEMINI_KEY_NAME`), the SQLCipher passphrase or any Room table. `UltronSettings` lost two fields; DataStore tolerates the missing keys. No database schema change, so no Room migration is needed, and no user data is erased.
The token was deleted only after confirming every reader of it (`ProxyProvider`, `WeatherRepository`, `ConnectScreen`) was gone.
Not verified: the migration has not run on a device with real old data.

## 5. Additional cleanup found while mapping references
Unreachable routes with no caller anywhere (checked with a reference search): `Routes.PC`, `Routes.CONNECTORS`, `Routes.INFO` and their placeholder screens (`ConnectorsScreen`, `InfoScreen`, `infoPages`). **DONE** removed; they were information-only "not available" pages that no control could open.

## 6. Acceptance criteria
| # | Criterion | Result |
|---|---|---|
| 1 | Builds | `Android build` workflow green (unit tests + `assembleDebug`) on the commit; see TEST_REPORT |
| 2–3 | Tests pass / preserved | yes (the SSE-parsing test was removed with its class; everything else kept; 8 new weather tests) |
| 4–6 | No backend needed, ProxyProvider gone, no active dependency | yes by source search |
| 7 | Weather direct | yes (unit-tested parsing; no live call was made in this session) |
| 8 | Backend-only settings/controls removed | yes |
| 9 | Local data preserved | yes by design (not device-verified) |
| 10 | Honest setup/network errors | yes by source reading |
| 11 | No hardcoded key | yes |
| 12 | Routes reachable | yes after removing the 3 dead ones (UI_UX_MAP.md) |
| 14 | UI does not claim missing capabilities | stale Privacy/Policy/Chat claims fixed; see AUDIT_REPORT for what remains unverified |
| 15 | README/CI accurate | rewritten |
