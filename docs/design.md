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

## Open technical risk (spike first)

Reading the MMKV file format in a pure-JVM plugin (no Android runtime). Two candidate approaches:

1. Bundle Tencent's MMKV JVM/desktop artifact and read files through it.
2. Write a minimal reader for MMKV's append-only, mmap'd key/value layout.

This is the one unresolved unknown and should be spiked before committing to full estimates.

## Build

Requires JDK 21. IntelliJ Platform Gradle Plugin 2.x, targeting Android Studio.

```bash
./gradlew build      # compile + verify plugin
./gradlew runIde     # launch a sandbox IDE with the plugin loaded
```

`buildSearchableOptions` is disabled (no custom settings) to keep builds fast.
