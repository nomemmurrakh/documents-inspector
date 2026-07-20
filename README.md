# Documents Inspector

An IntelliJ / Android Studio plugin that inspects on-device [MMKV](https://github.com/Tencent/MMKV)
files written by the [Documents](https://github.com/nomemmurrakh/documents) Kotlin Multiplatform
library, rendering them as a browsable tree of **collections → documents → typed fields**.

## Status

Early scaffold. Not yet functional — this repo currently contains only the plugin skeleton
(a bottom tool window placeholder) and its build setup.

## How it will work

Each Documents *collection* is one MMKV file (filename == collection name; the default collection
is `documents.default`). The plugin pulls those files off a running, debuggable app over ADB
(`adb exec-out run-as <pkg> …`), reads their `{document}::{field}` keys, and CBOR-decodes each
field value for display. No native code, no device rooting for debuggable apps.

Encrypted fields (written via a `FieldDecorator`) render as opaque blobs — the inspector is a
plaintext tool.

## Build

Requires JDK 21.

```bash
./gradlew build          # compile + verify plugin
./gradlew runIde         # launch a sandbox IDE with the plugin loaded
```

## License

TBD.
