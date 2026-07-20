# ADR-0003: Port from and test against the Rust `mmkv-parser` crate as the oracle

**Status:** Accepted
**Date:** 2026-07-20

## Context

Having chosen a pure-Kotlin reader ([ADR-0001](0001-pure-kotlin-reader.md)), we needed a reference
for the exact on-disk format and a way to know our decode was correct. An earlier note pointed at
the TypeScript project `syail/mmkv-parser`, but it is incomplete — its own README says it does not
support encryption or CRC loading — and its documented header layout does not match the files the
Documents library actually writes.

## Decision

Use the Rust crate [`mmkv-parser` 0.1.2](https://crates.io/crates/mmkv-parser) by Jixun Wu as both
the source to port from and the reference oracle in development. It is a genuinely-Rust, more
complete implementation (CRC32, AES-128-CFB, a `MmkvCrcHeader` reader, a CLI). The Kotlin reader's
function names and control flow follow the crate's `src/mmkv.rs` and `src/cipher.rs`; its decoded
output on real device files is diffed against the crate's during development.

A port means reading the actual source and validating against a real artifact — not coding from a
memo. The Kotlin `Mmkv`, `MmkvCrcHeader`, and `decrypt` functions mirror the Rust source, and the
unit tests mirror the crate's own test vectors.

## Consequences

**Positive**
- The format facts are anchored to a working, tested implementation, not to a description. This is
  how the two verified corrections were found: the body size comes from the `.crc` `real_size`, not
  the int32 at offset 0 (see [ADR-0005](0005-body-size-from-crc.md)), and each value carries MMKV's
  own length prefix (see [ADR-0006](0006-mmkv-length-prefix-then-cbor.md)).
- Regressions are catchable: re-run the oracle on a fixture and diff.

**Negative / cost**
- A Rust toolchain is needed to run the oracle during development (already present on this machine).
- We must carry the attribution forward (README Credits + `docs/design.md`) in any rewrite.

## Alternatives considered

- **Port from `syail/mmkv-parser` (TypeScript).** Incomplete, and its header layout is wrong for
  our files — every parser that trusted offset 0 read nothing. Demoted to a historical footnote.
- **Reverse-engineer from Tencent's design wiki alone.** Useful cross-check, but a prose spec is not
  executable; the two format corrections above came from running code, not reading the wiki.
