# Documents Inspector

An IntelliJ / Android Studio plugin that inspects on-device [MMKV](https://github.com/Tencent/MMKV)
files written by the [Documents](https://github.com/nomemmurrakh/documents) Kotlin Multiplatform
library, rendering them as a browsable tree of **collections → documents → typed fields**.

## Status

In progress. The **reader core is done and verified** — it parses real MMKV files pulled from a
device and decodes their fields (31 tests pass, cross-checked against a Rust reference parser). What
remains is the device-acquisition backends and the tree UI; the tool window is still a placeholder.
See [`docs/tasks.md`](docs/tasks.md) for the build plan and progress.

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

## Documentation

- [`docs/PRD.md`](docs/PRD.md) — why it exists, goals, and non-goals.
- [`docs/design.md`](docs/design.md) — full design, platform matrix, limitations.
- [`docs/format.md`](docs/format.md) — the authoritative on-disk byte format.
- [`docs/tasks.md`](docs/tasks.md) — ordered build plan and progress.
- [`docs/adr/`](docs/adr/) — the decisions behind the design.

## Build

Requires JDK 21.

```bash
./gradlew build          # compile + verify plugin
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
