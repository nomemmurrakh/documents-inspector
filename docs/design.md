# Documents Inspector — Design

An IntelliJ Platform plugin (targeting Android Studio) that inspects on-device
[MMKV](https://github.com/Tencent/MMKV) files written by the
[Documents](https://github.com/nomemmurrakh/documents) Kotlin Multiplatform library, presenting
them as a browsable tree of **collections → documents → typed fields**.

This is a companion tool, not part of the library. It reads the library's on-disk format; it does
not depend on the library at runtime.

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

**Chosen — a minimal pure-Kotlin reader, ported from prior art.** The read path is small and has
been independently reimplemented without the native library (see below). Pure Kotlin keeps the
plugin a single dependency-free jar that runs wherever the IDE runs.

### Attribution

The reader is a Kotlin **port** of the format work in
[`syail/mmkv-parser`](https://github.com/syail/mmkv-parser) (a read-only Rust parser of the Tencent
MMKV format, ~287 lines), cross-checked against the Tencent
[MMKV design wiki](https://github.com/Tencent/mmkv/wiki/design_eng). We reuse the *format knowledge*,
not its binary; the Rust parser is also used as a reference oracle in tests (its decoded output of a
real file is diffed against ours). Credit must appear in `README.md` and this doc; carry the
attribution forward in any future rewrite.

### On-disk format

Main file (`<collectionName>`):

| field | offset | size |
| --- | --- | --- |
| `dataLength` (int32) | 0 | 4 |
| varint region | 4 | n |
| body | 4 + n | until `dataLength` consumed |

- The file is mmap'd and padded to a memory page; bytes past `dataLength` are zero padding and are
  ignored. `dataLength` is authoritative.
- Body is a sequence of append-only entries:
  `keyLen (varint) · key (UTF-8) · valueLen (varint) · value`.
- **Latest-value-wins:** the same key may appear many times; scan the whole body and keep the last
  occurrence. An **empty value marks a deletion**.
- A sibling `<collectionName>.mmkv.crc` metafile holds the CRC32 and the AES **IV**. Not needed for
  plaintext reads; its presence with MMKV-level encryption is detected and reported, not decoded.

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
