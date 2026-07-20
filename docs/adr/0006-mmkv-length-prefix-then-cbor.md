# ADR-0006: Each stored value is MMKV-length-prefixed, then CBOR — strip the prefix before decoding

**Status:** Accepted
**Date:** 2026-07-20

## Context

After sizing the file correctly ([ADR-0005](0005-body-size-from-crc.md)), each entry's value still
would not CBOR-decode cleanly: every value began with a stray leading byte (`05`, `01`, `06` on the
real files) that CBOR did not explain. The Rust oracle returns this raw stored value as-is and does
not strip it, so the oracle alone did not reveal what the byte was.

Tracing it end to end through the shipped `documents-android 0.1.0` bytecode (decompiled with
`javap`): `DocumentEncoder` → `cbor.encodeToByteArray` (plain CBOR, **no** prefix) → `applyWrap`
(identity when no decorators) → `MmkvStorage.putBytes` → **`MMKV.encode(key, byte[])`**. MMKV writes
a `byte[]` in protobuf length-delimited form — a varint length followed by that many bytes. The
leading byte is MMKV's own length prefix. It is not CBOR and not a Documents wrapper.

On the real files: `totalBytes` (Long 2,000,000) is `05 · 1a001e8480`; `isComplete` (false) is
`01 · f4`; `theme` (enum `LIGHT`) is `06 · 65 "LIGHT"` — the leading byte is the length (5, 1, 6),
and the remainder is exactly the standalone kotlinx-serialization-cbor encoding.

## Decision

Model each on-disk value as `varint(len) · cborBytes` and strip the MMKV length prefix before CBOR
decoding. `Mmkv.unwrapByteArrayValue(value)` reads the leading varint; if `afterLen + len` equals
the value's length it returns the inner payload, otherwise it returns the bytes unchanged (so a
value that is somehow not a well-formed container is passed through rather than corrupted).

This unwrap is one layer **above** the ported oracle — the oracle parses the MMKV entry; the
inspector then un-frames the `byte[]`.

## Consequences

**Positive**
- Real values decode correctly: `theme → "LIGHT"`, `totalBytes → 2,000,000`, `isComplete → false`,
  all verified against the sample app's writes.
- The finding is anchored in shipped bytecode, not a guess — reproducible from the 0.1.0 AAR.

**Negative / cost**
- The reader is coupled to the fact that Documents stores fields via `MMKV.encode(key, byte[])`. If
  the library ever stored values through a different MMKV type (string, int), the framing would
  differ. It does not today; the unwrap's "return unchanged if not a clean container" fallback is
  the guard against surprises.

## Alternatives considered

- **Assume raw CBOR (no prefix).** What the first draft did; it fails on every real value. Wrong.
- **Special-case the observed leading bytes (05/01/06).** Brittle — those are just the lengths of
  those particular values. Reading the varint generally is correct and no harder.
