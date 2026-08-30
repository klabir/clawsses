# Local wake-word third-party provenance

Build 131 evaluates local keyword spotting with official upstream artifacts only.

- Engine: `k2-fsa/sherpa-onnx` `v1.13.6`, release commit
  `1cb484af5e69d3c7803c1eb0b3b5ab8041e0e911`, Apache License 2.0.
- Android AAR: `sherpa-onnx-static-link-onnxruntime-1.13.6.aar`, SHA-256
  `01e87037afca2ed49085062aace5c012e60321e8e23e3a72b6d9ac02c843f66c`.
- Model: `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01`, Apache License 2.0,
  downloaded from the official `kws-models` release.
- Only the int8 encoder, decoder, joiner, token table, and generated `HEY CLAWSSES` keyword file are
  packaged. Their hashes are enforced by `verifyLocalWakeWordProvenance`.

The original fork binaries were not copied. Runtime enablement remains opt-in and experimental
until paired-device accuracy, false-accept, microphone-coexistence, and power gates pass.
Debug builds retain all upstream ABIs for emulator CI. The release variant packages only
`arm64-v8a`, matching the paired Pixel hardware and avoiding unused native runtimes.
