# Documents Inspector

An IntelliJ / Android Studio plugin that inspects on-device [MMKV](https://github.com/Tencent/MMKV)
files written by the [Documents](https://github.com/nomemmurrakh/documents) Kotlin Multiplatform
library, rendering them as a browsable tree of **collections → documents → typed fields**.

## Status

Working on **Android, the iOS simulator, and physical iOS devices**, each verified live against the
Documents sample app. Reads a store as a three-pane tree (collections → documents → fields),
rendering each document as syntax-highlighted JSON or plain text. Marketplace publishing is the
remaining item — see [`docs/tasks.md`](docs/tasks.md).

## How it works

Each Documents *collection* is one MMKV file (filename == collection name; the default collection
is `documents.default`). The plugin acquires those files from a running, debuggable app, reads
their `{document}::{field}` keys, and CBOR-decodes each field value for display. No native code.

Documents is Kotlin Multiplatform, so the inspector targets every Documents platform through one
shared reader behind per-target acquisition backends:

- **Android** device / emulator — `adb exec-out run-as <pkg> …`
- **iOS Simulator** — `xcrun simctl` (the simulator sandbox is a directory on the Mac)
- **iOS physical device** — `xcrun devicectl` (paired, development-signed device)

Encrypted fields (written via a `FieldDecorator`) render as opaque blobs — the inspector is a
plaintext tool.

## Documentation

- [`docs/PRD.md`](docs/PRD.md) — why it exists, goals, and non-goals.
- [`docs/design.md`](docs/design.md) — full design, platform matrix, limitations.
- [`docs/format.md`](docs/format.md) — the authoritative on-disk byte format.
- [`docs/tasks.md`](docs/tasks.md) — ordered build plan and progress.
- [`docs/adr/`](docs/adr/) — the decisions behind the design.

## Install (side-load)

The plugin is not yet on the JetBrains Marketplace; install the built zip from disk.

1. Build the artifact: `./gradlew buildPlugin` → `build/distributions/documents-inspector-<version>.zip`.
2. In IntelliJ IDEA or Android Studio: **Settings → Plugins → ⚙ → Install Plugin from Disk…**,
   choose that zip, and restart.
3. The **Documents Inspector** tool window appears at the bottom of the IDE.

## Usage

1. Run a **debuggable** (Android) or **development-signed** (iOS) build of your app on a device,
   emulator, or booted simulator.
2. Open the **Documents Inspector** tool window and click **Connect**.
3. Choose the platform (Android device / iOS simulator / iOS device) and pick the target. The app id
   prompt is **prefilled from the open project** — the Android application id of each app module, or
   the bundle id from the Xcode project (falling back to the apps installed on the selected
   simulator or device). Confirm it, pick another from the dropdown, or type any id to inspect an
   app whose sources are not open.
4. Browse: pick a collection, then a document, and read its fields as JSON or plain text. Encrypted
   (`FieldDecorator`) fields show masked. **Refresh** reloads the last target.

Reading app-private storage requires a debuggable / development-signed build — this is inherent to
the platforms, not a plugin limitation.

## Build

Requires JDK 21.

```bash
./gradlew build          # compile + run tests
./gradlew buildPlugin    # build the installable plugin zip
./gradlew runIde         # launch a sandbox IDE with the plugin loaded
```

## Credits

The plugin reads MMKV files with a small pure-Kotlin reader whose format knowledge comes from the
Rust crate [`mmkv-parser`](https://crates.io/crates/mmkv-parser) — an independent implementation of
the Tencent MMKV on-disk format (with CRC and AES-CFB support) that also serves as the reference
oracle in development — cross-checked against the
[MMKV design documentation](https://github.com/Tencent/mmkv/wiki/design_eng). The TypeScript parser
[`syail/mmkv-parser`](https://github.com/syail/mmkv-parser) was consulted earlier but is incomplete.
We reuse the format knowledge, not the binary. Thanks to their authors for reverse-engineering and
documenting the format.

[MMKV](https://github.com/Tencent/MMKV) is a project of Tencent.

## License

TBD.
