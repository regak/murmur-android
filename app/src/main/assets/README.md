# ASR model assets

Not committed to git (too large). Download before building:

```bash
cd app/src/main/assets
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-moonshine-tiny-en-int8.tar.bz2
tar xvf sherpa-onnx-moonshine-tiny-en-int8.tar.bz2
rm sherpa-onnx-moonshine-tiny-en-int8.tar.bz2
```

Expected contents of `sherpa-onnx-moonshine-tiny-en-int8/`:

- `preprocess.onnx`
- `encode.int8.onnx`
- `uncached_decode.int8.onnx`
- `cached_decode.int8.onnx`
- `tokens.txt`

Total ~119 MB. For the higher-accuracy Base variant (~290 MB), swap
`tiny` for `base` in the URL and pass `modelDir = SherpaMoonshineEngine.MOONSHINE_BASE_EN`.

## VAD model (Silero, for SpeechSegmenter)

```bash
cd app/src/main/assets
mkdir -p vad && cd vad
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
```

~2.2 MB. Used by `SpeechSegmenter` to trim leading/trailing silence from the
held-button recording before it reaches Moonshine.
