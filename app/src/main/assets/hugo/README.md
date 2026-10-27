# HUGO identity face asset

- Selected source: user-provided `hugo-result.svg` from `hugo-archive`.
- Rejected alternative: `hugo-result-pro.svg`; it is approximately twice as large and its exported
  composition is cropped/offset in the declared 2048-square viewport.
- Selected SHA-256: `7d1acd1d43bb79c5212ba28f158f544939530a036f39d562006482be08046684`.
- Selected size: 369,761 bytes; viewBox `0 0 2048 2048`.
- The source SVG is retained byte-for-byte, including its embedded C2PA provenance metadata.
- Runtime rendering uses a 1024-square PNG derived locally from this exact SVG because Android
  WebView did not render the complex traced export reliably. The derivative SHA-256 is
  `c65e0601a8b3e5d143060a88086fa01f4e1a8370ecd29f0a3383b963a1c2f5a2` (252,610 bytes). The
  exact dark export field and traced lower/side perimeter are converted to alpha, leaving the hair,
  eyes, brows and mouth over HUGO's live presentation background.
  Compose overlays connect the accepted HUGO presentation states to bounded animation. No WebView,
  script, file/content access, or network surface remains; the artwork grants no identity, body,
  provider, authority, or execution semantics.
