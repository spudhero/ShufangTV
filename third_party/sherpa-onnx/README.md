# Embedded speech runtime and Chinese model

The unmodified `c-api.h` and `LICENSE` are from [sherpa-onnx v1.12.21](https://github.com/k2-fsa/sherpa-onnx/tree/v1.12.21), Apache-2.0. `scripts/prepare-speech-runtime.py` downloads the official Android release, verifies its fixed SHA-256 and selects the C API and ONNX Runtime libraries for four ABIs. Precompiled libraries stay in ignored build cache.

`ONNXRUNTIME-LICENSE` retains Microsoft ONNX Runtime's MIT license. `MODEL-LICENSE` retains Apache-2.0 for the [icefall AISHELL-3 low VITS model](https://huggingface.co/csukuangfj/icefall-tts-aishell3-vits-low-2024-04-06). The [AISHELL-3 corpus](https://www.openslr.org/93/) is provided by Beijing Shell Shell Technology Co., Ltd. under Apache-2.0. The fixed converted archive comes from the official [sherpa-onnx model release](https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-icefall-zh-aishell3.tar.bz2). Exact archive/file lengths and SHA-256 hashes live in `app/src/main/assets/speech/aishell3-zh.json`. ShufangTV selects generic speaker 66; it does not imitate a named person. The model runs at 8 kHz, supports Chinese, downloads 30.1 MiB and installs 31.2 MiB. The unused `rule.far` is validated as an archive entry but never installed.

The build copies this attribution and all three complete licenses into APK assets (`speech/licenses/`), accessible from the voice-management panel. Models are downloaded separately; no model weights are tracked in Git.

The JNI bridge, installer, playback and TV UI are independently written for ShufangTV. No system TTS service is required for offline speech. Version 1.12.21 preserves its integrated Chinese text frontend; a runtime upgrade requires a fresh Chinese device test.
