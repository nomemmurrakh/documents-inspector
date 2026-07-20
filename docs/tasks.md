# Documents Inspector — Task Breakdown (v1)

**Last updated:** 2026-07-20

Ordered, independently testable units. Build top to bottom; each task should compile and have tests
(where it has logic) before moving on.

> Workflow reminder: do not build or run without approval; no comments in code; ask before every
> git push.

Legend: `[x]` done · `[~]` partially done · `[ ]` not started.

**Committed:** Phase 0 (scaffold) and Phase 1 (reader core) — including this doc set — landed in
`f5122a3` ("Add MMKV reader core and full documentation set"). Phases 2–4 are not started.

---

## Phase 0 — Scaffold  *(done — committed in `f5122a3`)*

- [x] **T0.1** Empty plugin skeleton: IntelliJ Platform Gradle Plugin 2.10.0,
      `androidStudio("2025.1.1.14")`, bundled `org.jetbrains.android`, Kotlin 2.4.0, JDK 21,
      Gradle 9.4.1. `./gradlew build` verified SUCCESSFUL.
- [x] **T0.2** Bottom tool-window placeholder (`InspectorToolWindowFactory`) + `plugin.xml`
      descriptor (id `com.nomemmurrakh.documents.inspector`, depends on the Android plugin).
- [x] **T0.3** `buildSearchableOptions = false` (no custom settings; keeps builds fast).
- [x] **T0.4** `kotlinx-serialization-cbor` dependency + `kotlin("plugin.serialization")` (tests use
      `@Serializable` fixtures).

## Phase 1 — Reader core (pure Kotlin/JVM, platform-agnostic)  *(done — committed in `f5122a3`)*

- [x] **T1.1** `MmkvCrcHeader` — parse the `.crc` metafile (`crc32`, `iv`, `real_size`). Port of the
      crate's `cipher.rs`. Tests. See [ADR-0005](adr/0005-body-size-from-crc.md).
- [x] **T1.2** `Mmkv` — varint (`readU64`), length-prefixed container (`readContainer`), body parse
      with latest-value-wins + deletion (`parse`), and `readFile` sizing from the `.crc`. Port of
      `mmkv.rs`; tests mirror the crate's vectors. See [ADR-0001](adr/0001-pure-kotlin-reader.md),
      [ADR-0003](adr/0003-rust-mmkv-parser-oracle.md).
- [x] **T1.3** `Mmkv.unwrapByteArrayValue` — strip MMKV's `byte[]` length prefix before CBOR. Tests.
      See [ADR-0006](adr/0006-mmkv-length-prefix-then-cbor.md).
- [x] **T1.4** `decrypt` — AES-128-CFB whole-file decrypt, CRC + size check. Ported faithfully;
      **not wired into the read path** (MMKV-level decryption is deferred, PRD NG5).
- [x] **T1.5** `CborDecoder` — schema-free RFC 8949 walker → `DocumentValue`; multi-item top level →
      `Listing`; non-CBOR → `Opaque`. Tests. See [ADR-0004](adr/0004-opaque-decorated-and-encrypted-fields.md).
- [x] **T1.6** `DocumentValue` — the decoded value sealed hierarchy (Text/Integer/Decimal/Bool/Null/
      Bytes/Listing/Mapping/Opaque).
- [x] **T1.7** `CollectionReader` — file bytes → group keys by `::` → unwrap → CBOR-decode →
      `Collection(label, documents[fields])`. Tests.
- [x] **T1.8** `RealFileOracleTest` — decode real device fixtures (`documents.default`, `session`
      + `.crc`) and assert against the Rust oracle's golden output. **31 reader tests pass.**

## Phase 2 — Device acquisition (`DeviceSource`, per platform)

See [ADR-0007](adr/0007-device-source-per-platform.md). Each backend must pull **both** `<name>` and
`<name>.crc`.

- [x] **T2.1** `DeviceSource` interface — "give me a local copy of this app's mmkv files" — plus
      `PulledCollection` (name + main bytes + crc bytes) and `DeviceSourceException`.
- [x] **T2.2** `AndroidAdbSource` — enumerate `files/mmkv/` and pull each main + `.crc` via
      `adb exec-out run-as <pkg> …`; pin the target with `-s <serial>` when given; map
      not-debuggable / unknown-package / multiple-devices / no-devices failures to clear messages.
      `adb` is behind an injectable `Adb` seam (`ProcessAdb` real, fake in tests), so the 12
      `AndroidAdbSourceTest` cases verify command construction + parsing with no live device.
- [x] **T2.3** Device/package selection plumbing. `AdbDevices.list/parse` enumerates ready devices
      from `adb devices -l` (skips offline/unauthorized; model → display name); the Connect action
      auto-picks a single device or shows a chooser, then prompts for the app package. 7 tests.
- [ ] **T2.4** `IosSimulatorSource` — resolve the sandbox via
      `xcrun simctl get_app_container <udid> <bundleId> data`, then plain file reads of
      `Documents/mmkv/`. Tests around path resolution.
- [ ] **T2.5** `IosDeviceSource` — `xcrun devicectl device copy from …`. **Deferred** (a clean
      additional backend, not a redesign).

## Phase 3 — Plugin shell (UI)

- [x] **T3.1** Tree model over `Collection → Document → Field`, with each `DocumentValue` rendered
      per node — scalars inline, containers summarized and expanded, `Opaque`/`Bytes` as labeled
      blobs. `DocumentValueRenderer` (pure) + `InspectorTreeModel` (Swing nodes); 12 tests.
- [x] **T3.2** `InspectorView` wraps the `InspectorPanel` tree with a toolbar (Connect / Refresh
      actions). Connect resolves a device + package and runs `InspectorController.load` on a
      background task; the result renders on the EDT. Refresh re-runs the last target.
      `InspectorController` (pull → `CollectionReader.read` → `LoadResult`) is Swing-free and tested
      (decodes the real fixtures end-to-end; failures → `Failed`). 4 controller tests.
- [x] **T3.3** Three-pane master-detail (replaced the single tree): **Collections → Documents →
      Document content**, side by side via nested `JBSplitter`s. The content pane has a view-mode
      dropdown — **JSON** (pretty-printed; list→array, map→object) or **Plain text** (`field: value`
      lines). `Opaque`/`Bytes` values (decorator-encrypted) render as length-hinted asterisk masks,
      never raw bytes. `DocumentView` (pure JSON/plain renderer, 8 tests) + `InspectorPanel`
      rewrite. The earlier `InspectorTreeModel`/`DocumentValueRenderer` were removed as superseded.
- [x] **T3.4** Error + empty states. `InspectorController` turns `DeviceSourceException` (no device,
      not debuggable, unknown package, multiple devices) and decode errors into `LoadResult.Failed`
      messages, which `InspectorView` shows via `InspectorPanel.showMessage`; empty pull → "No
      collections found"; initial "connect a debuggable app". Missing-`.crc` surfaces as a read
      failure. (Verified via the pure `InspectorController` tests; the dialogs themselves are thin.)
- [x] **T3.5** UI polish. Syntax-highlighted JSON via a real IntelliJ editor (`ContentEditor` —
      resolves the JSON file type by extension, no hard JSON-plugin dependency; plain-text view
      falls back to no highlighting). Per-row icons (collection / document, shield when a document
      has a masked field). `JBPanelWithEmptyText` + list `emptyText` for empty/error states.
      Uniform fixed-height pane headers so the three panes align; the content header shows the
      selected document's key with the view-mode dropdown right-aligned; the connected
      `package • device` shows beside the toolbar actions. Verified live.

## Phase 4 — Packaging

- [ ] **T4.1** Plugin metadata, description, icon; `verifyPlugin` clean.
- [ ] **T4.2** Decide distribution (JetBrains Marketplace vs. side-load) and document install steps.

---

## Known environment issue

The inspector's own `./gradlew test` can be blocked by flaky DNS to `data.services.jetbrains.com`
(the IntelliJ platform resolver at `build.gradle.kts` `androidStudio(...)`) — network, not code. The
reader sources + tests were also verified compiling in a standalone JVM Gradle project without the
IntelliJ dependency. Re-run in-module when DNS is stable.
