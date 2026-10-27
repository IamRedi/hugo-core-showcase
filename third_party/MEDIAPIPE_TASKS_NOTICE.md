# MediaPipe Tasks / FL012 third-party notice

FL012 adds `com.google.mediapipe:tasks-vision:1.0.0` and the official EfficientDet-Lite0 int8 v1
model. The Google Maven POMs and upstream repositories were checked on 2026-08-27.

| Component family | Version | License retained |
|---|---:|---|
| MediaPipe Tasks Vision/Core and EfficientDet-Lite0 model | 1.0.0 / model v1 | Apache-2.0 |
| Google Flogger, Error Prone annotations, J2ObjC annotations | resolved by Tasks 1.0.0 | Apache-2.0 |
| Google Data Transport and Firebase Encoders | resolved by Tasks 1.0.0 | Apache-2.0 |
| Guava and FailureAccess | 27.0.1-android / 1.0.1 | Apache-2.0 |
| protobuf-javalite | 4.26.1 | BSD-3-Clause |
| Checker compatibility qualifiers | 2.5.3 | MIT option |
| JSR-305 / Animal Sniffer annotations | 3.0.2 / 1.17 | BSD-3-Clause / MIT |
| `javax.inject` | 1 | Apache-2.0 |

Retained texts:

- [`Apache-2.0.txt`](licenses/Apache-2.0.txt)
- [`BSD-3-Clause.txt`](licenses/BSD-3-Clause.txt)
- [`MIT.txt`](licenses/MIT.txt)

Primary provenance:

- https://developers.google.com/edge/mediapipe/solutions/vision/object_detector
- https://github.com/google-ai-edge/mediapipe
- https://maven.google.com/web/index.html#com.google.mediapipe:tasks-vision:1.0.0
- https://github.com/protocolbuffers/protobuf
- https://github.com/google/guava

MediaPipe's privacy notice says task inputs remain on device while API performance/utilization
metrics may be sent to Google. HUGO does not grant FL012 network authority: the application manifest
removes the transitive `INTERNET` and `ACCESS_NETWORK_STATE` declarations. The detector receives no
HUGO identity, provider/body state, name, transcript, permission, or execution authority.
