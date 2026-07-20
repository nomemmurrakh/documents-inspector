# On-disk format reference

The authoritative spec for the bytes the inspector's reader parses. Verified 2026-07-20 against real
files pulled from a device (`com.nomemmurrakh.documents.sample`, `files/mmkv/`) and cross-checked
with the Rust oracle ([ADR-0003](adr/0003-rust-mmkv-parser-oracle.md)). Where a fact was corrected
during that verification, the ADR that records it is linked.

This document describes *what the bytes are*. `docs/design.md` describes *how the plugin is built*.

---

## 1. Files per collection

A Documents collection is stored as **two** files in the app's `files/mmkv/` directory:

| File | Role |
| --- | --- |
| `<collectionName>` | the main file — the append-only body of key/value entries |
| `<collectionName>.crc` | the metafile — CRC32, AES-128-CFB IV, and the authoritative body size |

The default collection's `<collectionName>` is `documents.default` (the library's
`DEFAULT_STORE_ID`); the plugin labels that file "Default" and shows every other file by its raw
name. Both files are required to read a collection — the body size lives in the `.crc`
([ADR-0005](adr/0005-body-size-from-crc.md)).

## 2. The main file

```
┌──────────────┬───────────────┬───────────────────────────────┬──────────────┐
│ int32 LE @0  │ leading varint│ entries (append-only)          │ zero padding │
│ (see §2.1)   │ (skipped)     │                                │ to page size │
└──────────────┴───────────────┴───────────────────────────────┴──────────────┘
```

The file is mmap'd and padded to a memory page; bytes past the body are zero padding and ignored.

### 2.1 Size field (offset 0, int32 LE)

The Rust oracle's model treats this as the body size and parses `buf[4 .. 4 + size]`. **On the real
files this int32 was `0`** — stale, not the size. The authoritative body size is the `.crc`
`real_size` (§3). The reader resizes the main buffer to `real_size + 4`, writes `real_size` into
offset 0, and only then parses. See [ADR-0005](adr/0005-body-size-from-crc.md) for the open question
of *why* it was zero.

### 2.2 Leading varint (offset 4)

One protobuf-style base-128 varint immediately after the size field, skipped by the parser. (In the
crate's model this is a version/actual-size marker; the reader reads past it without interpreting
it.)

### 2.3 Entries

The body — from just past the leading varint to `4 + real_size` — is a sequence of entries:

```
entry = keyLen (varint) · key (UTF-8) · valueLen (varint) · value
```

- **Latest-value-wins.** The same key may appear many times as the app rewrites it; scan the whole
  body and keep the last occurrence. (Confirmed on real data: `active-download::bytesDownloaded`
  appeared six times.)
- **An empty value marks a deletion.** A key whose last write has a zero-length value is not live.

## 3. The `.crc` metafile

Layout the reader relies on (from the crate's `MmkvCrcHeader`):

| Field | Offset | Size | Notes |
| --- | --- | --- | --- |
| `crc32` | `0x00` | u32 LE | CRC32 of the (possibly enciphered) body |
| `iv` | `0x0C` | 16 bytes | AES-128-CFB IV (all-zero when the file is not MMKV-encrypted) |
| `real_size` | `0x1C` | u32 LE | **authoritative body size**, excluding the 4-byte header |

On the real files: `real_size` = 334 for `documents.default`, 175 for `session`.

## 4. Each value: MMKV length prefix, then CBOR

An entry's `value` is **not** the Documents CBOR directly. The library stores each field via
`MMKV.encode(key, byte[])`, and MMKV writes a `byte[]` in protobuf length-delimited form — a varint
length followed by that many bytes:

```
value = innerLen (varint) · cborBytes
```

The reader strips that inner length prefix (`Mmkv.unwrapByteArrayValue`) before CBOR-decoding.
Worked examples from the real files:

| Field | Stored value | Prefix | CBOR remainder | Decodes to |
| --- | --- | --- | --- | --- |
| `totalBytes` | `05 1a001e8480` | `05` (len 5) | `1a001e8480` | Integer 2,000,000 |
| `isComplete` | `01 f4` | `01` (len 1) | `f4` | Bool false |
| `theme` | `06 654c49474854` | `06` (len 6) | `65 "LIGHT"` | Text "LIGHT" |

The leading byte is MMKV's length, not CBOR. Full derivation and the bytecode trace:
[ADR-0006](adr/0006-mmkv-length-prefix-then-cbor.md).

## 5. CBOR decoding

The remainder after the prefix is [RFC 8949](https://www.rfc-editor.org/rfc/rfc8949) CBOR, decoded
schema-free into a `DocumentValue` tree (numbers, text, booleans, null, byte strings, lists, maps).
The source data-class *type name* is not stored and cannot be recovered — only field names and
values survive to disk.

Two things the decoder handles that are worth calling out:

- **Enums are two top-level items.** kotlinx-serialization-cbor encodes an enum as its ordinal
  *followed by* its name (e.g. `06 65 "LIGHT"` after the MMKV prefix is stripped: ordinal `6`, then
  the string `"LIGHT"`). The decoder reads all top-level items; a single item is returned as-is,
  multiple items as a `Listing`, so nothing is dropped and no framing is assumed.
- **Non-CBOR bytes fall back to `Opaque`.** A value written through a `FieldDecorator`
  (encryption/compression) is not CBOR after the frame is stripped; the decoder returns `Opaque`
  rather than throwing ([ADR-0004](adr/0004-opaque-decorated-and-encrypted-fields.md)).

## 6. Two independent opacity layers

Do not confuse these:

1. **MMKV-level encryption** (whole file). If the consumer initialized MMKV with a crypt key, the
   body is AES-128-CFB enciphered and nothing decodes without that key. The `.crc` IV is non-zero in
   that case. Out of scope for v1.
2. **`FieldDecorator` encryption** (per value, at the CBOR layer). Individual field values are
   transformed after CBOR encoding; the file itself is not encrypted (its `.crc` IV is all-zero).

On the sample app's `session` collection the `.crc` IV was all-zero (file **not** MMKV-encrypted),
yet the `current::authToken` / `displayName` / `userId` values were high-entropy blobs — the
per-value decorator, layer 2. Both layers surface the same way in the tree: an opaque blob.

## 7. Reader mapping summary

| On disk | Tree node |
| --- | --- |
| MMKV file `<name>` (+ `.crc`) | Collection (labeled "Default" for `documents.default`) |
| key prefix before `::` | Document |
| key suffix after `::` | Field name |
| `unwrap(value)` → CBOR → `DocumentValue` | Field value |

`::` is a separator the library forbids inside document keys, so the split is unambiguous.
