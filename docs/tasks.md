# Documents Inspector — Task Breakdown (v1)

**Last updated:** 2026-07-21

Ordered, independently testable units. Build top to bottom; each task should compile and have tests
(where it has logic) before moving on.

> Workflow reminder: do not build or run without approval; no comments in code; ask before every
> git push.

Legend: `[x]` done · `[~]` partially done · `[ ]` not started.

## Current state (0.1.0 — feature-complete)

Phases 0–4 are done. The plugin has been **verified live on Android (real device) and the iOS
simulator** against the Documents sample app (`com.nomemmurrakh.documents.sample`);
`buildPlugin` produces `documents-inspector-0.1.0.zip`; `verifyPlugin` → Compatible.

**Phase 5 (post-0.1.0) — done, verified live on both platforms** (Android prefilled the package name;
iOS offered the installed apps with the sample preselected). The Connect prompt is prefilled from the
open project and stays editable. Committed in `b7a24ef`. See
[ADR-0008](adr/0008-prefill-app-id-from-the-ide.md). **94 tests pass** (76 + 18 new). Live iOS
testing surfaced one ordering bug — with no `.xcodeproj` in the open project the list fell back to
simulator apps alone and preselected an unrelated app that sorted first; fixed in T5.6 by ranking
apps that already hold a Documents store ahead of those that do not.

**T2.5 done 2026-07-21** — `IosDeviceSource` (physical iOS via `xcrun devicectl`), verified live on a
real iPhone. All three `DeviceSource` backends now exist; the platform matrix in
[ADR-0007](adr/0007-device-source-per-platform.md) is complete. **112 tests pass.**

**What remains (all optional / deferred):**
- **JetBrains Marketplace** publishing (`signPlugin`/`publishPlugin`, screenshots, account/review).
- Optional: render a CBOR array of all-small-ints (e.g. `session_key`'s AES bytes) compactly rather
  than as a long number list.

**To resume a live test:** boot the iPhone 17 Pro sim, ensure the sample app is installed (it builds
after the MMKV SPM 2.4.0 pin — commit `f085105` in the documents repo), then `./gradlew runIde`,
open any project, **Documents Inspector** tool window → Connect → iOS simulator → bundle
`com.nomemmurrakh.documents.sample`. `runIde` bakes the plugin at launch, so restart it to pick up
code changes.

**Unpushed:** the inspector repo is ahead of `origin/master` (ask before pushing, per the rule).

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
- [x] **T2.4** `IosSimulatorSource` — resolves the container via
      `xcrun simctl get_app_container <udid> <bundleId> data`, then reads `Documents/MMKV/` off the
      Mac filesystem (pairs each main file with its `.crc`). `Simctl` seam (interface + `ProcessSimctl`)
      and a `CollectionFiles` seam (`LocalFiles` real) keep it testable without a simulator.
      `IosSimulators.listBooted/parse` enumerates **booted** sims from `simctl list devices booted -j`
      (kotlinx-serialization-json); the Connect action now asks Android vs iOS first. 12 tests
      (`IosSimulatorsTest` 8, `IosSimulatorSourceTest` 4). Parser validated against real `simctl -j`
      output. **Not yet exercised end-to-end live** (needs a booted sim with the Documents iOS sample
      installed).
- [x] **T2.5** `IosDeviceSource` — physical iOS via `xcrun devicectl`. A device sandbox is not a
      directory on the Mac, so files cannot be read in place: the container is listed
      (`device info files … --subdirectory Documents/MMKV`), each collection is copied into a
      temporary staging directory (`device copy from`), read back, and the directory deleted.
      `Devicectl` + `PullTarget` seams keep it testable with no device. `IosDevices` also backs the
      app-id prefill, filtering on `builtByDeveloper`. 18 tests
      (`IosDevicesTest` 13, `IosDeviceSourceTest` 5). **Verified end-to-end live on a real iPhone**
      (iPhone 13): launched the sample, pulled `documents.default` + `.crc`, decoded to the `Default`
      collection with 3 documents. See [ADR-0009](adr/0009-ios-device-via-devicectl.md).
      Live testing corrected four things unit tests could not have caught: `devicectl` **exits 0 even
      on failure** (the JSON `error` object is the source of truth); `relativePath` is **relative to
      the requested `--subdirectory`** (bare `cache`, not `Documents/MMKV/cache`) — parsing it as a
      full path made the first live run report "No collections found"; prefill needed the same
      store-first ranking as the simulator, but with **concurrent per-app probes** since the device
      listing has no container path; and MMKV's iOS directory is
      **`Documents/MMKV`** (uppercase) — a real device rejects `Documents/mmkv`, and the simulator
      only tolerated it because the Mac's filesystem is case-insensitive. The constant is shared, so
      this fixed a latent bug in `IosSimulatorSource` too.

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

- [x] **T4.1** Plugin metadata, description, icon. `pluginIcon.svg` (Kotlin-gradient document mark),
      a rich HTML `<description>` + `<change-notes>` in `plugin.xml`, version `0.1.0` (dropped
      `-SNAPSHOT`). `verifyPluginStructure` + `verifyPluginProjectConfiguration` pass; `buildPlugin`
      produces `documents-inspector-0.1.0.zip` with the icon + descriptor packaged. **`verifyPlugin`
      → Compatible** against Android Studio `AI-251...` (a `pluginVerification.ides` block was added);
      the only advisories are default `ToolWindowFactory` interface methods (inherited, not our code).
- [x] **T4.2** Distribution: **side-load** for now (no Marketplace yet). README documents building
      the zip and "Install Plugin from Disk", plus a Usage section (connect → platform → target →
      app id, browse JSON/plain). Marketplace publishing (`signPlugin`/`publishPlugin`, screenshots,
      review) is left for when there's an account.

---

## Phase 5 — Prefill the app id in the Connect prompt

The Connect flow asks for the application id / bundle identifier with a bare input dialog seeded only
by the previous target, so the first connect of a session always starts empty. The IDE usually knows
the answer already. Detect it and prefill, while keeping the prompt editable so another app can still
be inspected. See [ADR-0008](adr/0008-prefill-app-id-from-the-ide.md) for the decision and the
alternatives rejected.

**Verified during planning** (against Android Studio `AI-251.25410.109.2511.13752376`, and by running
the CLIs live) — these are confirmed present, not assumed:

| Need | API / mechanism |
| --- | --- |
| Android app id | `ProjectSystemUtil.getModuleSystem(module).applicationIdProvider.packageName` (`plugins/android/lib/android.jar`) — resolves the **installed** id, honouring `applicationIdSuffix`; throws `ApkProvisionException` when unsynced |
| Android fallback | `AndroidModuleSystem.packageName` (does not throw) |
| Enumerate modules | `ProjectFacetManager.getInstance(project).getFacets(AndroidFacet.ID)` (`lib/app.jar`) |
| App vs library | `AndroidFacetConfiguration.isAppProject()` (`android-common.jar`) |
| Editable dropdown | `Messages.showEditableChooseDialog(message, title, icon, values, initial, validator)` |
| iOS bundle id | `PRODUCT_BUNDLE_IDENTIFIER` is a plain literal in `*.xcodeproj/project.pbxproj`. `Info.plist` holds only `$(PRODUCT_BUNDLE_IDENTIFIER)`, so **pbxproj is the source** |
| iOS fallback | `xcrun simctl listapps <udid>`, filtered to `ApplicationType = User` (live run: 21 apps → 2 real user apps) |

Two `simctl listapps` gotchas found live, both of which the implementation must handle:

1. Output is **NeXTSTEP plist, not JSON** — `-j` is accepted but ignored. Pipe through
   `plutil -convert json -r -o - -- -` to get JSON the existing `kotlinx-serialization-json` parses.
2. It **exits 0 even on failure** (verified against a shut-down sim: printed `code=405 … Shutdown`,
   returned 0). The `Simctl` seam keys off `exitCode`, so this path must not trust it — unparseable
   stdout means "no candidates".

- [x] **T5.1** `AppIdCandidates` — pure, IDE-free parsers, unit-testable with no device or IDE:
      `parsePbxprojBundleIds` (regex `PRODUCT_BUNDLE_IDENTIFIER\s*=\s*"?([\w.\-]+)"?\s*;`, dropping
      values containing `$(` and `*.tests`/`*.UITests` targets, distinct, order-preserving),
      `parseListappsJson` (keep `ApplicationType == "User"`, read `CFBundleIdentifier`,
      `emptyList()` on any parse failure), and `merge` (distinct, order-preserving).
- [x] **T5.2** `IosSimulators.listApps` — add alongside `listBooted`/`appContainer`. Runs
      `listapps <udid>`, converts via a `PlistToJson` seam (real impl shells out to `plutil`), then
      delegates to `parseListappsJson`. **Ignores the exit code**; any failure returns `emptyList()`.
- [x] **T5.3** `AppIdDetector` — the only class touching Android-plugin APIs, isolated so the rest
      stays IDE-free. `androidCandidates(project)` filters `AndroidFacet`s by `isAppProject()` and
      resolves each id, per-module `runCatching` so one unsynced module cannot kill the list;
      `iosCandidates(project)` walks `project.basePath` for `*.xcodeproj/project.pbxproj` (bounded
      depth, skipping `build/` and `.git/`) and parses each.
- [x] **T5.4** Wire into `InspectorView`. `promptForApp` takes candidates and uses
      `showEditableChooseDialog` when any exist, falling back to today's `showInputDialog` when none
      do. Preselection: `lastTarget?.appLabel` (an explicit earlier choice wins) → else the first
      candidate. Call sites are the Android and iOS prompts in `connectAndroid` / `connectIosSimulator`.
      **Threading:** the pbxproj walk and `simctl` call must not run on the EDT — compute candidates
      in the background and show the dialog via `invokeLater`, mirroring the existing `render()`
      pattern; the facet reads need a read action.
- [x] **T5.5** Tests — `AppIdCandidatesTest`: pbxproj literal extraction (fixture from the real
      sample), `$(…)` variables skipped, test targets skipped, duplicates collapsed; `listapps` JSON
      → only `User` ids (fixture captured live); malformed input → `emptyList()`. The existing 76
      tests must still pass.
- [x] **T5.6** Rank simulator candidates by whether the app's sandbox already holds a Documents
      store (`Documents/MMKV/`), so the prompt does not preselect an unrelated installed app that
      merely sorts first. Found in live testing: with no `.xcodeproj` in the open project the iOS
      list fell back to simulator apps alone and preselected `com.nexcore.bigbros` over the sample.
      The container path already comes back in the `listapps` output, so this adds no `simctl` calls;
      it reuses the `CollectionFiles` seam and so stays testable. Ranking only — every user app is
      still offered. 4 tests.

**Live verification — done 2026-07-21, both platforms.**

- **Android** — the prompt prefilled the app module's package name correctly.
- **iOS** — the prompt offered every user-installed app with the sample preselected. This is the
  path that exposed the ranking bug fixed in T5.6: before it, the fallback preselected
  `com.nexcore.bigbros` purely because it sorted first.

Still unexercised (both low-risk, covered by unit tests): the **degradation** path — a project that
is neither Android nor iOS should show the plain input box with no error — and a **multi-app-module**
Android project, where the dropdown should list every app module and no library modules.

---

## Known environment issue

Gradle can fail to resolve the IntelliJ platform (`build.gradle.kts` `androidStudio(...)`) with
`Couldn't resolve Android Studio download URL for version: '2025.1.1.14'` — network, not code.

**Diagnosed 2026-07-21.** The real cause is a **JVM-only DNS failure** on `teamcity.jetbrains.com`
(where `jb.gg/android-studio-releases-list.xml` redirects). The distinguishing symptom: `nslookup`
and `dig` resolve the host fine, but `InetAddress.getByName` throws `UnknownHostException` for that
host *and only that host* — `data.services.jetbrains.com`, `download.jetbrains.com`, and
`google.com` all resolve from the same JVM. That points at a stale negative entry in macOS
`mDNSResponder`, not at connectivity or an unpublished version (`2025.1.1.14` is still listed).

Fix: `sudo dscacheutil -flushcache; sudo killall -HUP mDNSResponder` (needs a password), then retry.

**Workaround that does not need the network:** `--offline` uses the already-unpacked platform in
`~/.gradle/caches`, and is enough for `compileKotlin` and `test`:

```bash
./gradlew test --offline --no-configuration-cache
```

Only `buildPlugin`, `runIde`, and `verifyPlugin` genuinely need the resolver, so live checks have to
wait for DNS. To confirm the JVM-vs-shell split before blaming code, save this as `R.java` and run
`java R.java` — if `nslookup` succeeds while this prints FAIL, it is the resolver cache:

```java
public class R {
    public static void main(String[] a) throws Exception {
        try { System.out.println("OK " + java.net.InetAddress.getByName("teamcity.jetbrains.com")); }
        catch (Exception e) { System.out.println("FAIL " + e); }
    }
}
```
