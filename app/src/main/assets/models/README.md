# FL012 model provenance

- Model: EfficientDet-Lite0 int8, version 1
- Runtime: MediaPipe Tasks Vision Object Detector `1.0.0`
- Official source: https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite
- Official model guide: https://developers.google.com/edge/mediapipe/solutions/vision/object_detector
- Retrieved: 2026-08-27
- Size: 4,602,795 bytes
- SHA-256: `0720bf247bd76e6594ea28fa9c6f7c5242be774818997dbbeffc4da460c723bb`
- License: Apache License 2.0, retained at `third_party/licenses/Apache-2.0.txt`

The model is bundled and used only on device. HUGO does not download, replace, upload input to,
or identify people with this asset. FL012 allows only the `person` category and retains no image.

MediaPipe Tasks `1.0.0` transitively declares Google Data Transport for SDK utilization metrics.
HUGO's merged manifest explicitly removes `INTERNET` and `ACCESS_NETWORK_STATE`, so this First Life
slice has no network authority. Any future task that needs network access must re-review this
boundary; it must not silently make FL012 telemetry upload-capable.
