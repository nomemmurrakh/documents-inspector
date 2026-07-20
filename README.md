# Documents Inspector

An IntelliJ / Android Studio plugin that inspects on-device [MMKV](https://github.com/Tencent/MMKV)
files written by the [Documents](https://github.com/nomemmurrakh/documents) Kotlin Multiplatform
library, rendering them as a browsable tree of **collections → documents → typed fields**.

## Status

Early scaffold. Not yet functional — this repo currently contains only the plugin skeleton
(a bottom tool window placeholder) and its build setup.

## How it will work

Each Documents *collection* is one MMKV file (filename == collection name; the default collection
is `documents.default`). The plugin acquires those files from a running, debuggable app, reads
their `{document}::{field}` keys, and CBOR-decodes each field value for display. No native code.

Documents is Kotlin Multiplatform, so the inspector targets both platforms through a shared reader
behind per-platform acquisition backends:

- **Android** device / emulator — `adb exec-out run-as <pkg> …`
- **iOS Simulator** — `xcrun simctl` (the simulator sandbox is a directory on the Mac)
- **iOS physical device** — `xcrun devicectl` (deferred to a later phase)

Encrypted fields (written via a `FieldDecorator`) render as opaque blobs — the inspector is a
plaintext tool.

See [`docs/design.md`](docs/design.md) for the full design, platform matrix, and open risks.

## Build

Requires JDK 21.

```bash
./gradlew build          # compile + verify plugin
./gradlew runIde         # launch a sandbox IDE with the plugin loaded
```

## Credits

The plugin reads MMKV files with a small pure-Kotlin reader **ported from**
[`syail/mmkv-parser`](https://github.com/syail/mmkv-parser) — a read-only Rust parser of the Tencent
MMKV on-disk format — cross-checked against the
[MMKV design documentation](https://github.com/Tencent/mmkv/wiki/design_eng). We reuse the format
knowledge, not the binary. Thanks to its author for reverse-engineering and documenting the format.

[MMKV](https://github.com/Tencent/MMKV) is a project of Tencent.

## License

TBD.
