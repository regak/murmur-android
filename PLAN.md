# Murmur Android — Plan

Companion Android app to `murmur-youtube` (macOS/Windows push-to-talk
dictation). Scope for v1: **English only**. On-device, no cloud.

## ASR engine decision

**sherpa-onnx + Moonshine Tiny EN (INT8)** — see README for the comparison
data. Default engine. `TranscriptionEngine` interface keeps Moonshine Base
(or a future model) a drop-in swap.

## Phase 0 — Setup ✅

- [x] Project folder created
- [x] GitHub repo created
- [x] README + this plan committed

## Phase 1 — Core pipeline (proof of concept)

Goal: hold overlay button → talk → see transcript in Logcat. No injection yet.

1. Gradle scaffold: Kotlin, Jetpack Compose, min SDK 26, target latest stable.
2. `AudioCapture` — `AudioRecord` at 16kHz mono, single-consumer `Channel<ShortArray>`
   (mirrors macOS's "buffers copied, never borrowed" + single-task ordering rule).
3. [x] Fetch sherpa-onnx VAD (Silero) + Moonshine Tiny EN INT8 model assets
   at **runtime, not APK-bundled** — `ModelDownloader` pulls them from a
   GitHub Release into app-private storage on first launch (~120MB
   one-time download), so the APK itself stays ~39MB instead of ~140MB+.
   `SpeechSegmenter` wraps Silero VAD, trims silence from the held-button
   recording before it reaches the ASR engine.
4. [x] `TranscriptionEngine` interface + `SherpaMoonshineEngine` implementation.
5. Minimal floating overlay bubble (`TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE`
   — never steals focus, same rule as the macOS HUD) — press-and-hold to record.
   (`MainActivity`'s in-app press-and-hold is the stand-in for this until the
   real floating bubble + background service land.)
6. [x] Wire mic → VAD → Moonshine → Logcat (`DictationController`). This is
   the proof-of-concept milestone — pending a real device build to confirm
   it actually runs (no Android SDK/emulator available in this environment).

## Phase 2 — Text injection

7. [x] Custom IME shell (`MurmurInputMethodService`) with a mic key — primary injection
   path; guarantees injection into any focused field because it *is* the keyboard.
   `InputConnection.commitText` does the actual injection; shares the exact same
   `DictationController` pipeline as `MainActivity` (no duplicated logic).
8. [x] `MurmurAccessibilityService` injector — fallback/general-purpose path for
   the floating bubble (Phase 3 item, built ahead of schedule — see below),
   which has no `InputConnection` of its own. Finds the system-wide focused
   node via `AccessibilityNodeInfo.FOCUS_INPUT` and splices text in at the
   cursor via `ACTION_SET_TEXT`. Falls back to clipboard+toast if the user
   hasn't enabled the service yet — a transcript is never silently lost.
9. [x] `RuleBasedFormatter` (filler stripping, punctuation, capitalization) —
   Kotlin implementation (the macOS Swift source wasn't available in this
   environment, so this re-derives the same role from the README's
   description rather than porting literal code); wired into
   `DictationController` so both `MainActivity` and the IME get formatted
   output automatically.

### Floating bubble ("hanging button") — pulled forward from Phase 3

Requested directly, with a screenshot of Wispr Flow's own floating mic
button active over WhatsApp: "Can you develop a similar button... so I
can click and hold". `FloatingBubbleService`: a draggable circular
overlay (`TYPE_APPLICATION_OVERLAY`), foreground service (survives app
switches, matches the macOS HUD's non-focus-stealing rule), press-and-hold
to dictate from literally anywhere, drag to reposition. Delivers text via
`MurmurAccessibilityService` when enabled, clipboard+toast fallback
otherwise. This was originally slated as Phase 3 polish but the user's
actual top want was clearly this trigger mechanism, not the in-app test
screen — moved up.

## Phase 3 — Parity features

10. Settings screen: engine choice (Tiny/Base), trigger mode (overlay bubble /
    volume-key / IME mic key), language (English only for now, UI ready for more).
11. Port `shared/dictionary-test-vectors.json` correction dictionary from
    murmur-youtube; Kotlin implementation tested against the same vectors.
12. HUD/waveform overlay polish; verify non-focus-stealing on real devices
    across at least Pixel + one Samsung (OEM accessibility behavior varies).

## Phase 4 — Hardening & ship

13. Foreground service + notification for the mic/overlay session (Android
    kills background mic access aggressively without this).
14. Permission onboarding: microphone, overlay ("draw over other apps"),
    "set as keyboard", optional default-assistant role.
15. Signing + distribution decision (sideload APK vs Play Store — Play
    Store review is stricter on Accessibility + overlay permissions).

## Deferred / out of scope for v1

- Swahili / multilingual ASR — explicitly deferred; revisit once the English
  pipeline is solid (Moonshine has multilingual variants if/when needed).
- LLM cleanup tier (tone, spoken corrections) — rule-based formatter first,
  matching the macOS app's own sequencing.
- Command Mode, personal dictionary, branding, onboarding polish — same
  "not built yet" list as murmur-youtube, Android gets them after parity.
