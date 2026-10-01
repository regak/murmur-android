# Vendored native libraries

`sherpa-onnx-1.13.8.aar` is not committed (50MB, prebuilt binary). Download
it before building:

```bash
cd app/libs
curl -fsSL -o sherpa-onnx-1.13.8.aar \
  "https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar"
```

This isn't published to Maven Central/Google's repo — k2-fsa ships it as a
GitHub release asset instead. Bundles `libonnxruntime.so` + sherpa-onnx's
own JNI/C++/C-API `.so` files for arm64-v8a, armeabi-v7a, x86, x86_64 —
no separate onnxruntime-android dependency needed.
