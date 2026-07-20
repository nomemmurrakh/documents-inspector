# ADR-0002: Documents-only; no general MMKV "smart mode"

**Status:** Accepted
**Date:** 2026-07-20

## Context

The reader parses raw MMKV. It would be tempting to generalize it into an all-purpose MMKV viewer
that sniffs arbitrary files and guesses their structure. The plugin could then read any MMKV store,
not just those written by the Documents library.

## Decision

Assume the Documents layout everywhere and only that layout. One file is a collection; a key is
`{document}::{field}`; a value is MMKV-framed CBOR. There is no mode that inspects non-Documents
MMKV files.

## Consequences

**Positive**
- The tree model is unambiguous: `::` splits document from field, files are collections, values
  decode as CBOR. No heuristics, no "is this CBOR or a raw string?" guessing at the top level.
- The product stays focused on its one job — inspecting Documents stores — which is what its users
  need (PRD G1).

**Negative / cost**
- Files not written by Documents will render as flat, undocumented key/value pairs at best, or
  as opaque blobs. That is acceptable: they are out of scope (PRD NG3).

## Alternatives considered

- **General MMKV browser with a Documents mode.** More broadly useful in the abstract, but it
  dilutes the tool, forces format-guessing heuristics, and serves users we are not targeting. The
  Documents ecosystem is the reason this plugin exists. Rejected for v1.
