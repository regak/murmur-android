# ASR model assets

**These are no longer bundled here or in the APK.** The APK download was
~140-230MB when the models were bundled as Android assets — multiple times
larger than Wispr Flow's APK (~35-57MB), because Wispr Flow ships *zero*
model weights (it streams audio to a cloud Whisper endpoint; this app is
on-device/offline by design and has no server to call).

The fix: `ModelDownloader` (`core/ModelDownloader.kt`) fetches these files
into app-private storage (`context.filesDir/models/...`) on first launch
instead, from this repo's GitHub Release `models-v1`:
https://github.com/regak/murmur-android/releases/tag/models-v1

This keeps the APK itself small (no model weights inside it) while keeping
transcription on-device after the one-time ~120MB download — the same
trick most on-device AI apps use (ML Kit downloadable modules, etc.).

## For local dev/testing without rebuilding ModelDownloader's URLs

If you want the old bundled-in-APK behavior back (e.g. for an offline
build with no network at all), the files `ModelDownloader` expects are the
same ones previously vendored here:

- `sherpa-onnx-moonshine-tiny-en-int8/{preprocess,encode.int8,uncached_decode.int8,cached_decode.int8}.onnx` + `tokens.txt` (~119MB)
- `vad/silero_vad.onnx` (~643KB)

Source: https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-tiny-en-int8.tar.bz2
and https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx

For the higher-accuracy Moonshine Base variant (~290MB) instead of Tiny,
swap the model files and the `OfflineMoonshineModelConfig` paths in
`SherpaMoonshineEngine.kt` — same interface, no other code changes needed.
