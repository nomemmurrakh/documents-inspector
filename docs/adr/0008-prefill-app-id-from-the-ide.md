# ADR-0008: Prefill the app id from the IDE, but keep the prompt editable

**Status:** Accepted
**Date:** 2026-07-21

## Context

Connecting to an app requires an application id (Android) or bundle identifier (iOS). Until now the
Connect flow asked for it with a bare input dialog seeded only by the previous target, so the first
connect of every session started from an empty box. The developer had to recall and retype an id the
IDE usually already knows, and a typo surfaced late and indirectly — as an "unknown package" failure
from `run-as`, or "not installed" from `simctl`, neither of which reads as "you misspelled it".

The IDE has the answer in almost every real case: an Android Studio project has the Android module
model loaded, and a KMP project has the Xcode project sitting in the opened tree. The question is
whether to use it, and whether using it should remove the prompt.

## Decision

Detect candidate app ids from the open project and **prefill** them, while keeping the prompt itself
— as an *editable* dropdown (`Messages.showEditableChooseDialog`) rather than a plain text field.
The best candidate is preselected, the other detected ids are one click away, and any id at all can
still be typed. Inspecting an app whose source is not open stays a first-class case.

Detection is **best-effort and never blocking**: if the project model is unsynced, the sources are
absent, or a lookup throws, the flow degrades to the previous plain input box. No detection failure
is ever surfaced as an error, because the developer can always just type the id.

Per platform:

| Platform | Primary source | Fallback |
| --- | --- | --- |
| Android | `getModuleSystem(module).applicationIdProvider.packageName`, over app-project `AndroidFacet`s | `AndroidModuleSystem.packageName` |
| iOS | `PRODUCT_BUNDLE_IDENTIFIER` parsed from `*.xcodeproj/project.pbxproj` in the open project | `xcrun simctl listapps <udid>`, filtered to `ApplicationType = User`, apps holding a Documents store ranked first |

Preselection order is **last explicit choice → first detected candidate**, so a deliberate earlier
selection is never overridden by detection.

Within the iOS simulator fallback, candidates are ordered by whether the app's sandbox already
contains a Documents store (`Documents/MMKV/`), not by the order `simctl` happens to report them.
That ordering was added after live testing showed an unrelated installed app preselected simply
because its bundle id sorted first. The container path is already present in the `listapps` output,
so the check costs one directory probe per app and no extra `simctl` calls. It is a ranking signal
only — every user-installed app stays in the dropdown, because an app that has not written its store
yet is still a legitimate target.

`ApplicationIdProvider` is chosen over the manifest package deliberately: it resolves the *installed*
id, applying `applicationIdSuffix`. A debug build of `com.example.app` installs as
`com.example.app.debug`, and only the resolved id works with `run-as`.

## Consequences

**Positive**
- The common case — inspect the app you are looking at — becomes a confirm instead of a recall.
  Fewer typo-driven failures, and the failures that remain are about the device, not the spelling.
- Multi-module and multi-target projects are handled by listing every detected id, so the dropdown
  is also a discovery aid for which app modules exist.
- Nothing is taken away: the free-text path that worked before still works identically.

**Negative / cost**
- Introduces the plugin's first dependency on Android Studio project-model APIs
  (`ProjectFacetManager`, `AndroidFacet`, `ProjectSystemUtil`). These are already reachable — the
  plugin hard-`depends` on `org.jetbrains.android` — but they are IDE APIs, so they are isolated in a
  single detector class and every call is guarded, keeping the rest of the codebase testable and
  IDE-free.
- Detection does file I/O (walking for `project.pbxproj`) and may shell out (`simctl listapps`), so
  it must run off the EDT. That is a threading obligation the old prompt did not have.
- `simctl listapps` needs two workarounds, both verified live: its output is NeXTSTEP plist rather
  than JSON (`-j` is accepted but ignored), so it is converted with `plutil -convert json`; and it
  **exits 0 even on failure**, so its exit code cannot be trusted and unparseable output is treated
  as "no candidates". It is called out in the code and in [`tasks.md`](../tasks.md).
  ([ADR-0009](0009-ios-device-via-devicectl.md) later found `devicectl` behaves the same way, so
  ignoring an Apple tool's exit code is a pattern here, not a one-off.)

## Alternatives considered

- **Auto-connect with no prompt at all.** Fastest for the single-module case, but it removes the
  ability to inspect another app — an explicit requirement — and would silently pick one id in
  multi-module projects. Rejected.
- **A plain text field, just prefilled.** Simplest change, but in a multi-module or multi-target
  project it offers only one guess and hides the rest. The editable dropdown is the same interaction
  when there is one candidate, and strictly better when there are several. Rejected.
- **A separate module-picker step before the prompt.** Consistent with the existing device chooser,
  but it adds a second dialog to the common single-app case to serve the rare one. Rejected in
  favour of folding the choice into the prompt already being shown.
- **Reading the Android manifest package directly.** Would miss `applicationIdSuffix` and produce an
  id that fails under `run-as` on debug builds — the exact failure this ADR set out to reduce.
  Rejected in favour of `ApplicationIdProvider`.
- **Parsing `Info.plist` for the iOS bundle id.** It holds `$(PRODUCT_BUNDLE_IDENTIFIER)`, an
  unresolved build variable, not the literal. The pbxproj carries the real value. Rejected.
