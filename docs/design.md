# Documents Inspector — Design

An IntelliJ Platform plugin (targeting Android Studio) that inspects on-device
[MMKV](https://github.com/Tencent/MMKV) files written by the
[Documents](https://github.com/nomemmurrakh/documents) Kotlin Multiplatform library, presenting
them as a browsable tree of **collections → documents → typed fields**.

This is a companion tool, not part of the library. It reads the library's on-disk format; it does
not depend on the library at runtime.

## The docs

- [`PRD.md`](PRD.md) — why the inspector exists, goals, and **non-goals**.
- [`design.md`](design.md) — this file: how the pieces fit (architecture, platform matrix).
- [`format.md`](format.md) — the authoritative on-disk byte format the reader parses.
- [`tasks.md`](tasks.md) — the ordered build plan and what is done.
- [`adr/`](adr/) — why specific decisions were made.

## Why this is feasible

The Documents on-disk format is almost entirely self-describing, which is what makes an inspector
possible without any library changes or data migration:

- **Collection ⇔ MMKV file.** Each collection is one MMKV instance whose id *is* the collection
  name. `Documents.document<T>(key)` uses the default collection, stored in the file
  `documents.default`. `Documents.collection(name)` uses a file named exactly `name`. So the
  collection level of the tree is simply the list of MMKV files in the store directory.
- **Documents ⇔ key prefix.** Within a file, keys are `"{documentKey}::{fieldName}"`. Grouping a
  file's keys by the substring before `::` reconstructs the documents and their fields. `::` is a
  reserved separator the library forbids inside document keys, so the split is unambiguous.
- **Field values ⇔ CBOR.** Each value is a CBOR-encoded field. CBOR is self-describing (type tags
  travel with the bytes), so values decode to a renderable tree — primitives, nested structures,
  lists, nulls — **without** the original data class. The type *name* (e.g. `Note`) is not stored
  and cannot be recovered; only field names and values are.

### Constant the reader must know

- Default collection file id: `documents.default` (the library's `DEFAULT_STORE_ID`). The plugin
  labels this file "Default"; all other files are shown by their raw name.
- Key separator: `::`.

## Scope

- **Documents-only.** The plugin assumes the Documents layout. It is not a general MMKV viewer and
  has no "smart mode" that sniffs arbitrary MMKV files.
- Read-only. No editing of on-device values in v1.

## Non-goals (v1)

- Editing / writing values back to the device.
- Decoding encrypted fields (see Limitations).
- A general-purpose MMKV browser for non-Documents files.
- Recovering the original data-class *type* of a document.

## Architecture

Three layers. The lower two are platform-agnostic; only the top (acquisition) is
platform-specific.

```
┌─────────────────────────────────────────────┐
│ IntelliJ plugin shell                        │  Tool window, tree UI, actions,
│ (InspectorToolWindowFactory, tree model)     │  value/detail panel
├─────────────────────────────────────────────┤
│ Reader core  (pure Kotlin/JVM)               │  MMKV file → keys → group by "::"
│                                              │  → CBOR-decode → render tree
├─────────────────────────────────────────────┤
│ DeviceSource  (acquisition, per platform)    │  "give me a local copy of the
│  ├─ AndroidAdbSource                         │   mmkv files for this app"
│  ├─ IosSimulatorSource                       │
│  └─ IosDeviceSource   (later)                │
└─────────────────────────────────────────────┘
```

### DeviceSource

A single interface abstracts *how the bytes are obtained*. Every backend does the same job — copy
an app's mmkv files to a local temp dir — and everything below the interface is shared. Adding a
platform is adding a backend, not a second parser.

## Platform support

Android and both iOS forms are all handled by different `DeviceSource` implementations feeding the
same reader.

| Source | Mechanism | MMKV dir on target | Effort | Host requirement |
| --- | --- | --- | --- | --- |
| Android device / emulator | `adb exec-out run-as <pkg> cat files/mmkv/<file>` | `/data/data/<pkg>/files/mmkv/` | baseline | any OS |
| **iOS Simulator** | `xcrun simctl get_app_container <udid> <bundleId> data` → plain file reads | `<sandbox>/Documents/mmkv/` | low | macOS + Xcode |
| iOS physical device | `xcrun devicectl device copy from …` (Xcode 15+) | app container `Documents/mmkv/` | medium | macOS + Xcode, paired dev-signed device |

The iOS simulator is just a directory on the Mac's filesystem, so once the sandbox path is
resolved it is plain file I/O — identical to Android once the bytes are local. There is no
"adb for iOS"; physical-device access requires Apple's `devicectl` and a development-signed app.

### Planned order

1. **Android (`adb run-as`)** — baseline, proves the reader end to end.
2. **iOS Simulator (`simctl`)** — low cost, covers most real inspection needs, proves the
   cross-platform tree.
3. **iOS physical device (`devicectl`)** — deferred; a clean additional `DeviceSource`, not a
   redesign.

## Access constraints

Inspecting app-private storage requires the target app be **debuggable / development-signed**:

- Android: `run-as` only works on `debuggable` apps (or a rooted device).
- iOS: `simctl`/`devicectl` reach the container only for development-signed builds.

This is inherent to reading app-private files, not a limitation we can engineer away.

## Limitations

- **Encrypted fields.** Fields written through a `FieldDecorator` (e.g. encryption, compression)
  are transformed *after* CBOR encoding, so on disk they are opaque. The inspector renders them as
  blobs (e.g. "🔒 encrypted, N bytes"); it is a plaintext tool by design.
- **MMKV-level encryption.** If a consumer initialized MMKV with a crypt key, the whole file is
  opaque without that key. Out of scope for v1.
- **No type name.** Documents show field names and values, never the source data-class name.

## MMKV reader (resolved)

The plugin runs on a desktop JVM with no Android/iOS runtime, so once a collection file is on the
local disk it must parse MMKV's on-disk bytes itself. Two approaches were considered and one is
chosen.

**Rejected — bundle a native MMKV.** Tencent MMKV has a POSIX/desktop C++ core, but publishes no
standalone desktop-JVM/JNI binding; the Kotlin Multiplatform port (`ctripcorp/mmkv-kotlin`) targets
only Android/iOS/macOS and wraps the native library. Either route means shipping and loading a
per-OS native binary inside the IntelliJ sandbox plus hand-written JNI — heavy and fragile for what
is a small parser.

**Chosen — a minimal pure-Kotlin reader.** The read path is small and has been independently
reimplemented without the native library by others (see Attribution). Pure Kotlin keeps the plugin a
single dependency-free jar that runs wherever the IDE runs.

### Attribution

The reader reuses the *format knowledge* of the Rust crate
[`mmkv-parser` 0.1.2](https://crates.io/crates/mmkv-parser) (an independent, more complete
implementation of the Tencent MMKV format, with CRC and AES-CFB support), cross-checked against the
Tencent [MMKV design wiki](https://github.com/Tencent/mmkv/wiki/design_eng). That crate is also used
as the reference oracle in development: its decoded output of a real device file is diffed against
ours. We reuse knowledge, not binaries. The earlier TypeScript parser
[`syail/mmkv-parser`](https://github.com/syail/mmkv-parser) was consulted first but is incomplete
(its own README notes no CRC/encryption support) and its documented header layout does not match the
files this library writes — see below. Credit must appear in `README.md` and this doc; carry the
attribution forward in any future rewrite.

### On-disk format

The byte-level spec — main file, `.crc` metafile, the MMKV length prefix around each value, and the
CBOR mapping — is the authoritative reference in [`format.md`](format.md), with the decisions behind
it recorded as ADRs ([0005](adr/0005-body-size-from-crc.md) size-from-`.crc`,
[0006](adr/0006-mmkv-length-prefix-then-cbor.md) length-prefix-then-CBOR). The summary that follows
is kept here for context; `format.md` governs if the two ever drift.

Verified 2026-07-20 against real files pulled from a device (`documents.default` and a named
`session` collection) and cross-checked with the Rust oracle.

Main file (`<collectionName>`):

| field | offset | size | notes |
| --- | --- | --- | --- |
| size field (int32 LE) | 0 | 4 | **not reliably the body size — see below** |
| leading varint | 4 | 1+ | skipped |
| body | after the varint | `realSize` bytes | append-only entries |

- **The body size comes from the sibling `.crc` metafile, not the int32 at offset 0.** On the real
  device files that int32 was `0`, while the true size lived in the `.crc` file at offset `0x1C`
  (`realSize`: 334 for `documents.default`, 175 for `session`). A reader that trusts offset 0 reads
  nothing — confirmed: the Rust oracle, the TypeScript parser, and a first Kotlin draft all failed
  identically until fed the `.crc` size.
- The `.crc` metafile layout (from the Rust crate's `MmkvCrcHeader`): `crc32` at 0, the AES-128-CFB
  **IV** at `0x0C..0x1C`, and `realSize` (int32 LE) at `0x1C`.
- The file is mmap'd and padded to a memory page; bytes past the body are zero padding and ignored.
- Body is a sequence of append-only entries:
  `keyLen (varint) · key (UTF-8) · valueLen (varint) · value`.
- **Latest-value-wins:** the same key may appear many times; scan the whole body and keep the last
  occurrence. An **empty value marks a deletion**. (Confirmed on real data:
  `active-download::bytesDownloaded` appeared six times; the last write wins.)
- **Open question:** why offset 0 was `0` on these files is unresolved — the Rust crate's `decrypt`
  path asserts offset 0 equals the `.crc` `realSize`, implying MMKV normally keeps them in sync.
  Possible causes: the app had not flushed the mmap when the file was pulled, or the library writes
  the size only to the `.crc`. The reader treats the `.crc` `realSize` as authoritative regardless.

### Each value is MMKV-length-prefixed, then CBOR

The `value` of an entry is **not** the Documents CBOR directly. The library stores each field via
`MMKV.encode(key, byte[])`, and MMKV writes a `byte[]` in protobuf length-delimited form — a varint
length followed by that many bytes. So the on-disk value is:

```
value = innerLen (varint) · cborBytes
```

The reader strips that inner length prefix before CBOR-decoding. On the real files, `totalBytes`
(Long 2,000,000) is stored as `05 · 1a001e8480`, `isComplete` (false) as `01 · f4`, and `theme`
(enum `LIGHT`) as `06 · 65"LIGHT"` — the leading byte is MMKV's length (5, 1, 6), and the remainder
is exactly the standalone kotlinx-serialization-cbor encoding.

This was traced end to end through the shipped `0.1.0` bytecode: `DocumentEncoder` →
`cbor.encodeToByteArray` (plain CBOR, no prefix) → `applyWrap` (identity when no decorators) →
`MmkvStorage.putBytes` → `MMKV.encode(key, byte[])`, where MMKV adds the length prefix. The Rust
oracle does **not** strip this (it returns MMKV's raw stored value); unwrapping the `byte[]`
container is one layer above what the oracle parses.

### Effort

Small — a varint decoder, the header + entry loop, and the latest-wins dedupe: on the order of
150–300 lines of Kotlin, matching the Rust reference. No native dependencies; the previously-open
technical risk is closed.

Note this MMKV-level encryption is distinct from Documents' `FieldDecorator` encryption (per-value,
at the CBOR layer) covered under Limitations — two independent layers.

## Build

Requires JDK 21. IntelliJ Platform Gradle Plugin 2.x, targeting Android Studio.

```bash
./gradlew build      # compile + verify plugin
./gradlew runIde     # launch a sandbox IDE with the plugin loaded
```

`buildSearchableOptions` is disabled (no custom settings) to keep builds fast.
