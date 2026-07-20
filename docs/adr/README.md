# Architecture Decision Records

ADRs are numbered sequentially and never renumbered or deleted — a superseded ADR stays in place
with its status updated to point at the ADR that replaced it.

| # | Title | Status | Date |
|---|---|---|---|
| [0001](0001-pure-kotlin-reader.md) | Read MMKV with a pure-Kotlin reader, not a bundled native library | Accepted | 2026-07-20 |
| [0002](0002-documents-only-no-smart-mode.md) | Documents-only; no general MMKV "smart mode" | Accepted | 2026-07-20 |
| [0003](0003-rust-mmkv-parser-oracle.md) | Port from and test against the Rust `mmkv-parser` crate as the oracle | Accepted | 2026-07-20 |
| [0004](0004-opaque-decorated-and-encrypted-fields.md) | Decorated and MMKV-encrypted values render as opaque blobs | Accepted | 2026-07-20 |
| [0005](0005-body-size-from-crc.md) | The body size comes from the `.crc` metafile, not the int32 at offset 0 | Accepted | 2026-07-20 |
| [0006](0006-mmkv-length-prefix-then-cbor.md) | Each stored value is MMKV-length-prefixed, then CBOR — strip the prefix before decoding | Accepted | 2026-07-20 |
| [0007](0007-device-source-per-platform.md) | One `DeviceSource` interface per platform; the reader is shared | Accepted | 2026-07-20 |
