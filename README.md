# Murmur Android

Push-to-talk dictation for Android — on-device, Wispr Flow-shaped. Sibling
project to [murmur-youtube](https://github.com/per-simmons/murmur-youtube)
(macOS/Windows), sharing the same product shape but built around Android's
very different hotkey, text-injection, and ASR primitives.

**Status:** Phase 1 — proof of concept (mic capture → VAD → ASR → logcat).
Not yet feature-complete. See `PLAN.md` for the full roadmap.

## Why it's not a straight port

| Concern | macOS (murmur-youtube) | Android (this project) |
|---|---|---|
| Trigger | `CGEventTap` on Right ⌥ | Floating overlay bubble (press-and-hold); IME mic key |
| Text injection | AX API insert into focused field | Custom IME (primary) + `AccessibilityService` (fallback) |
| ASR | Apple `SpeechAnalyzer` / Parakeet v3 (FluidAudio) | sherpa-onnx + **Moonshine Tiny EN (INT8)** |

## ASR engine choice

**Moonshine Tiny EN, INT8, via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)**
(Apache-2.0). Picked for speed over accuracy ceiling, matching Wispr Flow's
snappy feel:

- 27M params, ~125 MB on-device footprint
- RTF ≈ 0.05 on a Samsung Galaxy S10 (Android 12) — fastest of 16 models
  benchmarked publicly (voiceping.net offline ASR benchmark, 2026)
- English-only (by design, for now — scope is English first)
- Drop-in upgrade path to Moonshine Base (~290 MB, RTF ≈ 0.08) behind the
  same `TranscriptionEngine` interface if accuracy needs to improve later

## Project layout

```
app/                        Android app module (Kotlin, Jetpack Compose)
  src/main/java/.../core/       DictationController, HotkeyTrigger (overlay), AudioCapture
  src/main/java/.../asr/        TranscriptionEngine interface + SherpaMoonshineEngine
  src/main/java/.../format/     TextFormatter interface + RuleBasedFormatter
  src/main/java/.../inject/     TextInjector (IME) + AccessibilityInjector (fallback)
  src/main/java/.../ui/         Overlay bubble, waveform HUD, Settings
  src/main/assets/              sherpa-onnx Moonshine model files (downloaded, not committed)
shared/                      Cross-platform correction-dictionary contract (ported from murmur-youtube)
PLAN.md                      Full phased roadmap
```

## Building

Requires Android Studio (Ladybird+) / JDK 17 / Android SDK 34+, min SDK 26.

```
./gradlew assembleDebug
```

Model files are not committed to git (too large) — see
`app/src/main/assets/README.md` for the download step.
