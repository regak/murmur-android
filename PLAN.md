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
3. Bundle sherpa-onnx VAD (Silero) + Moonshine Tiny EN INT8 model assets.
4. `TranscriptionEngine` interface + `SherpaMoonshineEngine` implementation.
5. Minimal floating overlay bubble (`TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE`
   — never steals focus, same rule as the macOS HUD) — press-and-hold to record.
6. Wire mic → VAD → Moonshine → Logcat. This is the proof-of-concept milestone.

## Phase 2 — Text injection

7. Custom IME shell (`InputMethodService`) with a mic key — primary injection
   path; guarantees injection into any focused field because it *is* the keyboard.
8. `AccessibilityService` injector as a secondary/fallback path (for "works
   even when another keyboard is active" cases), using
   `ACTION_SET_TEXT` / clipboard+paste fallback — same fallback pattern as
   the macOS `TextInjector`.
9. Port `RuleBasedFormatter` (filler stripping, punctuation, capitalization)
   from the macOS Swift implementation; wire ASR output → formatter → injector.

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
