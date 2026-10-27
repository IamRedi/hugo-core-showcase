# H012 - Adapter identity and integrity

## Status

**DONE** - 2026-08-25

## Contract shape

H012 adds a versioned, provider-neutral `AdapterDescriptor` in `adapter/api`. Its identity is the
pair `AdapterId` plus `AdapterVersion`; neither value identifies a provider definition, a provider
instance, a host, a transport/session, a UI component, credentials, or HUGO itself. A descriptor
also declares its package digest, provenance reference, supported provider/manifest families,
semantic capability references, and optional HUGO runtime-version compatibility.

`AdapterId` is deliberately the same semantic namespace/name shape used by the H010 manifest-side
adapter reference, but it is a separate project-owned adapter contract. H012's pure matcher compares
the two values, checks the H010 adapter version range, provider family, common/specialized schema
support, H011 common binding, and declared semantic capabilities. It does not choose, load, install,
trust, authorize, connect, or execute an adapter.

## Integrity and provenance

`AdapterPackageDigest` is strict lowercase SHA-256 text. `AdapterIntegrityVerifier` deterministically
compares an expected identity/version/digest tuple to an observed tuple and reports typed ID,
version, or digest mismatch. A match proves only that these values match: it is not trust,
signature validation, publisher proof, installation approval, permission, safety approval, or
execution authority.

Provenance distinguishes built-in, official-vendor, HUGO-maintained, local-development,
Developer-Mode-candidate, and future installable-package claims. Publisher and source fields are
opaque references, not credentials or trust grants. Future signature/trust-chain work can attach an
independently verified expectation to this comparison without changing adapter identity semantics.

## Version and compatibility behavior

The descriptor codec has a closed `1.x` envelope; unsupported major versions, malformed IDs,
versions, digests, duplicate/conflicting manifest declarations, duplicate capability claims, and
unknown ordinary fields fail closed. Each manifest-support declaration states a provider family,
common manifest schema major, and optional specialized-manifest kind/schema major. Adapter runtime
compatibility is optional but, when supplied, is an explicit inclusive/exclusive semantic range.

H010/H011 remain canonical for provider claims. A manifest adapter reference is a compatibility
claim, not proof that an adapter package is present or intact. Adapter compatibility is likewise not
provider trust, authorization, runtime availability, connection state, physical feasibility, or
safety approval.

## Developer Mode boundary

H012 provides the future comparison target for a sandbox-produced candidate:

```text
expected adapter ID/version/digest <-> observed candidate/package ID/version/digest
```

It adds no sandbox, code generation, dynamic loading, registry, installation, downloading,
marketplace, or enablement behavior. Such behavior remains subject to ADR-017 and later H073-H077.

## Evidence

`AdapterIdentityIntegrityTest` covers valid/invalid identity, semantic versions and ranges,
deterministic digest comparison, manifest adapter-reference/family/schema/capability compatibility,
duplicate/conflicting declarations, strict codec round trip/unknown fields, and public-type coupling
checks. H012 is JVM-only: it makes no LOOI, Android, device, or hardware claim and does not alter the
verified LOOI runtime path.
