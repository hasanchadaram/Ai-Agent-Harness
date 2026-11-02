# Silero VAD model

Bundled file: `silero_vad_16k_op15.onnx`, 1,289,603 bytes.

- Source: https://github.com/snakers4/silero-vad/blob/066409f3d9db45a30d21dfcbf9147826ffdd7052/src/silero_vad/data/silero_vad_16k_op15.onnx
- Upstream revision: `066409f3d9db45a30d21dfcbf9147826ffdd7052`.
- SHA-256: `7ed98ddbad84ccac4cd0aeb3099049280713df825c610a8ed34543318f1b2c49`.
- License: MIT; bundled in [LICENSE.silero.txt](./LICENSE.silero.txt).
- Runtime: ONNX Runtime Android 1.24.3, CPU, one inference thread.

The wrapper follows upstream's 512-sample frames, 64-sample context, and recurrent state for 16 kHz audio. The model is packaged with the app; recording does not download it. Detector state is new for each recording and released when capture ends. Only speech probabilities and pause counts are exposed in diagnostics; no captured speech is written here.
