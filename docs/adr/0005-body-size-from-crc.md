# ADR-0005: The body size comes from the `.crc` metafile, not the int32 at offset 0

**Status:** Accepted
**Date:** 2026-07-20

## Context

The Rust oracle's model — and every MMKV parser we found — treats the little-endian `int32` at
offset 0 of the main file as the body size, then parses `buf[4 .. 4 + size]`. On real files pulled
from the connected device (`documents.default` and a named `session` collection), **that int32 was
`0`**. Trusting it, the oracle, the TypeScript parser, and a first Kotlin draft all returned empty
or `UnexpectedEof` — identically.

The true size lived in the sibling `<name>.crc` metafile at offset `0x1C` (`real_size`: 334 for
`documents.default`, 175 for `session`). Patching offset 0 to that value made every parser read the
files cleanly.

## Decision

Treat the `.crc` `real_size` (u32 LE @ `0x1C`) as authoritative. `Mmkv.readFile(mainFile, crcFile)`
reads `MmkvCrcHeader.fromBytes`, resizes the main buffer to `real_size + 4`, writes that size into
offset 0, and only then parses — exactly the flow the crate's CLI uses for a plaintext file. The
`.crc` layout the reader relies on: `crc32` @ `0x00`, AES-128-CFB `iv` @ `0x0C..0x1C`, `real_size`
@ `0x1C`.

## Consequences

**Positive**
- The reader works on the files this library actually writes, which is the whole point. Verified:
  both real files decode fully once sized from the `.crc`.
- The `.crc` also yields the AES IV, so the (deferred) whole-file decrypt path has what it needs.

**Negative / cost**
- The inspector needs *two* files per collection — `<name>` and `<name>.crc`. The `DeviceSource`
  layer must pull both. A collection whose `.crc` is missing cannot be sized reliably.

## Open question

Why offset 0 was `0` on these files is unresolved. The crate's `decrypt` path asserts offset 0
equals the `.crc` `real_size`, implying MMKV normally keeps them synced. Likely causes: the app had
not flushed the mmap when the file was pulled (lazy mmap sync), or the library writes the size only
to the `.crc`. The reader treats the `.crc` size as authoritative regardless, so this does not block
correctness — but it is worth confirming before relying on offset 0 anywhere.
