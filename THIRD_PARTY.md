# Third-party licenses and provenance

The evaluation license applies to first-party HUGO work only. It does not
relicense dependencies or community contributions. Noncommercial intent and
attribution do not create permission to copy unlicensed code. GitHub explains
the default copyright rule in its [licensing documentation](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository).

## LOOI research: distinguish facts from implementation

The source repository's ADR-008 and [research decisions](docs/OPEN_SOURCE_ATTRIBUTION.md)
record no-copy restrictions: unknown-license material is a research lead;
GPL implementation is reference-only under the project's license choice.
The project has its own Kotlin transport, encoders, state machines and tests.
Its file history records the serialized queue under T014 and drive encoder
under T025, followed by its own device evidence. This preparation retains that
first-party code rather than importing an upstream implementation.

| Research source | Source snapshot referenced by HUGO | Observed licensing posture | Treatment |
|---|---|---|---|
| [GrinZero/super-looi](https://github.com/GrinZero/super-looi) | `9985101d840e1ec1a6bbb022bccb1b0eb369b3d6` | MIT declared; project-specific file provenance still matters | Citation/research only; no SDK imported |
| [andrey-tut/LOOI-Robot](https://github.com/andrey-tut/LOOI-Robot) | `aade49d2e94894d12a8b8c5459afcd28267aad7b` | GPL-3.0 | No Python implementation copied or translated into this showcase |
| [CozmoCyke/LOOInteract](https://github.com/CozmoCyke/LOOInteract) | `27460eb1767831e388b081473a120af1cf17c00a` | Source snapshot declared proprietary; no root grant found in current metadata | No code/table/capture import |
| [splattydoesstuff/sooperchargeforbots](https://github.com/splattydoesstuff/sooperchargeforbots) | `6b56c616e3a1311573d64fda9b189958ee91b948` | No root license found in current metadata | No implementation, capture or decompiled-app asset import |

License metadata was rechecked on 27 October 2026. These are documented
provenance/reuse decisions, not blanket legal clearance of a vendor protocol.
LOOI is a third-party product and trademark; this is not an official SDK or an
endorsement. The evaluation APK registers no real LOOI activity/service and
requests no Bluetooth permission. No physical device is demonstrated.

## Build and library dependencies

Dependencies are resolved from Google Maven/Maven Central/Gradle's official
distribution and retain their licenses. No dependency cache, APK or vendor model
weight is committed. MediaPipe notices and license texts are retained under
[`third_party/`](third_party/MEDIAPIPE_TASKS_NOTICE.md). The Gradle wrapper JAR
was checked against the official 8.10.2 wrapper checksum; the distribution
checksum is pinned in `gradle-wrapper.properties`.

AndroidX/Compose/CameraX, Kotlin/coroutines/serialization and MediaPipe are
separate components; Google Play Services remains subject to Google's terms,
and JUnit is a test dependency. See the generated dependency inventory for
resolved coordinates and POM license metadata; absence of metadata is reported,
not silently treated as permission.

## Artwork and media

The HUGO face is an owner-provided product asset. Its retained SVG/provenance
and unchanged raster hashes are documented in
[`app/src/main/assets/hugo/README.md`](app/src/main/assets/hugo/README.md).
Android screenshots are new captures from the isolated emulator. They are not
the original household/device screenshots, which are omitted from this copy.
Videos are labeled captioned montages of those actual captures. Any architecture
or social card is an explanatory graphic, not fabricated application UI.
