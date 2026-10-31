# HUGO Core

**Eine Identität. Viele mögliche Körper.**

Eine portable, lokal arbeitende Intelligenz-Laufzeit für autorisierte Roboter, Geräte und Dienste. KI darf typisierte Aktionen vorschlagen. Nur deterministischer lokaler Code darf sie autorisieren und ausführen.

[![Android CI](https://github.com/IamRedi/hugo-core-showcase/actions/workflows/android.yml/badge.svg)](https://github.com/IamRedi/hugo-core-showcase/actions/workflows/android.yml)

**Kotlin · Jetpack Compose · Android · BLE/GATT · AES-256-GCM · Pure JVM contracts**

![HUGO Android showcase: actual emulator capture, simulated body](docs/assets/hero.png)

[English](README.en.md) · [Engineering](docs/ENGINEERING.md) · [Verification](EVIDENCE.md) · [Attribution & provenance](THIRD_PARTY.md)

## Was die Ingenieurarbeit trägt

- **Autorität als Architekturgrenze:** Discovery und Manifeste gewähren kein Vertrauen. Versionierte Fähigkeiten, scoped Grants, Freigaben, Sessions und lokale Sicherheitsregeln bleiben getrennt.
- **Sicherheit ohne Modellabhängigkeit:** monotone Command-Leases, Dead-Man, gelatchter Not-Stopp, Generation-Korrelation und Stop-Priorität in einer serialisierten GATT-Queue.
- **Ein prüfbarer KI-Eingang:** begrenzter Kontext, strikt validierte Tool-Vorschläge, Replay-/Idempotenzregeln sowie Budget- und Circuit-Policies. Provider-Schlüssel gehören hinter ein Gateway.
- **Gedächtnis mit Herkunft:** Kandidaten werden nicht durch Modellbehauptungen zu Fakten. Explizite Nutzerkorrekturen, erlaubte Verwendung, Konflikte, Retention und Lineage sind Teil des Datentyps.
- **Dauerhaftigkeit als Vertrag:** AES-256-GCM/Android Keystore, authentifizierte Journale, atomare Korrektur-Paare und strikte Verzeichnis-Synchronisierung. Beschädigung, Schlüsselverlust und unzureichende Plattformprimitive schlagen geschlossen fehl.
- **Grenzen als Tests:** Dependency-Guards für reine Kotlin-Pakete, adversariale Fixtures, injizierte Zeit/Transporte und getrennte JVM-, Emulator- und Hardware-Nachweise.

## Ein kurzer Einblick

[![Actual Android application walkthrough](docs/assets/video/walkthrough-preview.webp)](docs/assets/video/walkthrough-de.mp4)

**[Video DE](docs/assets/video/walkthrough-de.mp4)** · **[Video EN](docs/assets/video/walkthrough-en.mp4)** · [Transcript](docs/assets/video/transcript.md)

Die Screenshots stammen aus einer echten Android-Emulator-Instanz. Die stille Video-Montage zeigt einen ausdrücklich simulierten Körper und geskriptete Vorschläge; kein Live-Modell, kein physischer Roboter, keine persönlichen Daten.

| Proposal, without execution | Local safety rejection | Governed memory |
|---|---|---|
| ![Pending proposal](docs/assets/screenshots/proposal.webp) | ![Overspeed rejected](docs/assets/screenshots/blocked.webp) | ![Model self-validation rejected](docs/assets/screenshots/memory-blocked.webp) |

## Zwei klar getrennte Ebenen

**Im Quellcode:** der HUGO-Kern bis H061, einschließlich eigener BLE-/LOOI-Forschungsimplementierung, Provider-Verträge, Voice/Vision, Gateway und verschlüsselter Memory-Repository-Verträge. **In der Demo:** ein separater Android-Einstieg mit simuliertem `RobotBody`, den bestehenden Safety-/E-Stop-Reducern und echten H061-Factories. Die Demo-Bestätigung ist kein Ersatz für den produktiven H030-Autorisierungsorchestrator.

Memory-Envelopes im sichtbaren Labor bleiben flüchtig; eine Korrektur erzeugt einen Entwurf und schreibt keinen kanonischen Store. Hardware-/Netzwerk-/Mikrofon-/Kamerazugriff ist im Demo-APK nicht freigegeben. Historische Hardware-Berichte sind keine neue Geräte-Abnahme.

## Lokal bauen

JDK 17+ · Android SDK Platform 35 · pinned Gradle 8.10.2

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
python scripts/check-showcase.py --built
```

Windows: `./gradlew.bat` instead of `./gradlew`.

Installieren Sie ausschließlich `showcase/build/outputs/apk/debug/showcase-debug.apk` auf einem separaten Emulator. Die App-ID ist **`io.redi.hugo.showcase`**; die private Installation wird nicht überschrieben. Keine Provider-Konfiguration oder Zugangsdaten erforderlich. [Reproduzierbare Demonstration](docs/DEMO.md).

## Nachweise und Grenzen

Die Quell-Baseline enthält **1053 JVM-Tests in 131 Suites**; die ältere README-Zahl 962 wurde durch H060/H061 überholt. Neue Showcase-Tests kommen hinzu. Exakte aktuelle Ergebnisse und plattformbedingte Auslassungen stehen in [EVIDENCE.md](EVIDENCE.md). CI baut auch die instrumentierte Suite; Kompilierung ist keine Geräteausführung.

**Stand 31.10.2026:** Im privaten Kern sind inzwischen auch H062–H066 abgeschlossen: Geräte-/Körper-Gedächtnis pro Instanz und Version, Strategie-Gedächtnis mit Versuchen und Ergebnissen, Validierung und Abgleich ohne stilles Überschreiben, begrenzter Abruf mit festen Budgets und Herkunfts-Zusammenfassungen mit Löschweitergabe. Alle fünf sind reine, noch nicht verdrahtete Schichten; die volle JVM-Suite meldet dort 1141 Tests in 136 Suites ohne Fehler. Dieser Snapshot enthält den Quellcode weiterhin nur bis H061.

H067 (Gedächtnis-Kontrolle und M6-Abnahme) ist die nächste kanonische Grenze. Offene Mehrgeräte-Orchestrierung, selbstständige Codeinstallation und allgemeine autonome Navigation werden hier nicht als implementiert dargestellt. [Umfang](docs/PUBLIC_SCOPE.md) · [Architektur](docs/ARCHITECTURE_SHOWCASE.md) · [Partnerschaft](docs/PARTNERSHIP.md).

## Author and license

**Redion Cekrezi / RediON** · [GitHub](https://github.com/IamRedi)

Source-available for local evaluation; this is not an open-source license. Production use, commercial use and redistribution require permission. [LICENSE](LICENSE).

Third-party components retain their own licenses. Community research is attributed separately from copied code; attribution is not a replacement for permission. [Provenance review](THIRD_PARTY.md).
