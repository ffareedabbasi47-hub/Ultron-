# ULTRON voice: background operation, limits and test checklist

## How background listening works
- Live runs in a foreground service of type `microphone` with a visible notification (Android 14+ requires the
  service to be started while the app is visible or from a user action; it cannot be started silently from boot).
- Only one `AudioRecord` exists (`MicCapture`). Wake detection ("Ultron") is a personal MFCC/DTW template matcher
  trained on the user's own voice: it needs 3+ enrolment recordings and works only while the service runs.
- Phone-call mode (Gemini Live) streams ALL microphone audio to Google while active; it needs Cloud AI consent and a
  user-supplied Gemini key. Normal mode streams only detected speech and only with consent.

## Honest limits
- Screen off / Doze: the foreground service keeps the process alive on most devices, but audio capture can still be
  throttled or silenced by the system (the app shows "mic blocked by system" when Android reports it).
- OEM battery managers (Xiaomi/MIUI, Oppo/Realme/ColorOS, Vivo, Samsung "sleeping apps", OnePlus, Huawei) may kill
  the service. User must set battery to "No restrictions" and allow auto-start; ULTRON cannot do this for them.
- Another app using the microphone (a call, recorder) silences ours; calls take audio focus and pause Live.
- Reboot: the service does not restart by itself; the user starts Live again.
- Browser/WebView code cannot do any of this; the avatar WebView is display-only.
- Neural wake-word engines (Porcupine etc.) need a licence/key and are not included.

## What is verified by CI (JVM unit tests)
State mapping, VAD/segmenter, sleep clock, echo gate, wake matcher, wire formats (STT + call), consent policy,
builder file parsing/path safety, theme contrast.

## Device-only checklist (cannot be run in CI)
1. Permission denied -> Live shows "Microphone permission chahiye", no crash; grant -> works.
2. Incoming phone call during Live call mode -> Live pauses, message shown; mic released.
3. Headset / Bluetooth connect + disconnect during a call -> audio follows the route, no crash.
4. Screen lock for 5 minutes with Live on -> notification stays; wake phrase still works (device dependent).
5. Airplane mode mid-call -> call fails with message and falls back to normal mode; restore network -> works.
6. Swipe app from recents -> service state; reopen -> Live state is truthful (STOPPED unless service runs).
7. Repeated mic taps (10x quickly) -> never two recorders, no duplicate replies.
8. Noisy room / TV -> false activations rate; tune wake sensitivity and "reduce media interference".
9. Latency: Menu -> Diagnostics shows measured time-to-first-token / first-audio (real values only).
