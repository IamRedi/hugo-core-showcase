# Open-Source Attribution and Reuse Decisions

Research date: 2026-08-24. M0 contains no copied upstream implementation code or packet constants. Links and factual protocol observations are attributed below.

## GrinZero/super-looi

- Source: https://github.com/GrinZero/super-looi
- Inspected commit: `9985101d840e1ec1a6bbb022bccb1b0eb369b3d6`
- Declared license: MIT; the LICENSE copyright names 650 Industries/Expo, so provenance of project-specific files must be checked before copying.
- Useful knowledge: service/characteristic candidates; a transport abstraction; handshake candidate using `FEDA/FEF0`; direction/head/light candidate values; `FED9` dock values.
- Decision: reference in M0. Later code reuse is allowed only after file-history/provenance review, minimal extraction, preserved notice, tests, and architecture fit. Do not import the whole Expo SDK.

## andrey-tut/LOOI-Robot

- Source: https://github.com/andrey-tut/LOOI-Robot
- Inspected commit: `aade49d2e94894d12a8b8c5459afcd28267aad7b`
- Declared license: GPL-3.0.
- Useful knowledge: detailed profile A initialization, two-axis movement model, reported head/light/battery behavior, `FED9` experiments, safety notes, open questions.
- Decision: documentation/reference only under the current non-GPL project plan. Do not translate or copy its Python implementation line-for-line. Independently implement behavior from test evidence.

## CozmoCyke/LOOInteract

- Source: https://github.com/CozmoCyke/LOOInteract
- Inspected commit: `27460eb1767831e388b081473a120af1cf17c00a`
- Declared license: `pyproject.toml` says `Proprietary`; no root license grant was present.
- Useful knowledge: evidence for alternate profile B (`FED4–FED8`) and contradictions with profile A.
- Decision: no code reuse. Treat public factual observations as research leads requiring independent reproduction. Do not copy its code, tables, prose, or device address.

## splattydoesstuff/sooperchargeforbots

- Source: https://github.com/splattydoesstuff/sooperchargeforbots
- Inspected commit: `6b56c616e3a1311573d64fda9b189958ee91b948`
- Declared license: none found; the README also says public sharing of modified/decompiled files is paused.
- Useful knowledge: raw `FE00` capture leads, characteristic hypotheses, handshake/disconnect observation, and explicit uncertainty.
- Decision: no code or capture copying into production. Use as a research index only; reproduce packets through owned captures before encoding. Never incorporate decompiled proprietary application material.

## Legal/technical policy

- Absence of a license means no permission to copy code, even if a repository is public.
- Protocol facts may be independently implemented, but copied expression, tables, captures, and decompiled assets require legal review.
- GPL-3.0 reuse would impose reciprocal obligations; adopting it requires an explicit ADR and distribution plan.
- Every future reused file must identify source URL, commit, license, local modifications, and retained notice.
- Security/safety architecture takes precedence over convenience: no upstream raw-write escape hatch reaches UI, AI, or skills.

## Project dependencies

AndroidX, Kotlin, Gradle, and other eventual binary dependencies will be inventoried automatically before releases. This document records source reuse decisions; generated third-party notices remain a separate release artifact.
