# Documents Inspector — Product Requirements Document

**Status:** v1.0 — all goals and success criteria met by the unreleased 0.1.0 build
**Owner:** Nomem Labs
**Last updated:** 2026-07-21

---

## 1. Problem statement

The [Documents](https://github.com/nomemmurrakh/documents) library persists typed objects to
[MMKV](https://github.com/Tencent/MMKV) — memory-mapped, protobuf-backed, fast. That speed comes
at the cost of legibility: the on-disk bytes are opaque. When a developer wants to answer a plain
question — *"what did my app actually store?"* — there is nowhere to look.

- MMKV files are a binary blob of length-prefixed entries; no shipped tool renders them.
- The values are CBOR, wrapped in MMKV's own framing — not something a hex editor makes obvious.
- The document structure (`{document}::{field}`) is a convention that lives only in the library's
  head; a raw key/value dump does not show it as a tree.

There is no way to *see* a Documents store the way the library sees it: collections, documents,
and typed fields.

## 2. Target user

Developers building on the Documents library (Android-first, KMP-aware) who want to:

- verify what their app persisted, while debugging or building a feature,
- confirm a write happened (or a delete took), without adding logging,
- understand the shape of stored data without reading the library's source.

The user is already running a **debuggable** build on a device, emulator, or simulator — the same
build they are developing.

## 3. The gap

MMKV ships no inspector, and generic key-value viewers (where they exist) stop at raw keys and
byte values. None of them understand the Documents layout — that one file is a collection, that
`::` splits a key into document and field, that a value is MMKV-framed CBOR. The inspector is the
only tool that renders a Documents store *as Documents*.

## 4. Goals

- **G1** — Render an app's Documents store as a tree of **collections → documents → typed fields**,
  inside the IDE the developer already uses (IntelliJ / Android Studio).
- **G2** — Decode field values to typed, readable form (numbers, text, booleans, lists, maps,
  null) **without** the original data class — CBOR is self-describing.
- **G3** — Acquire the files from a live, debuggable app with no code changes to that app and no
  runtime dependency on the Documents library.
- **G4** — Support Documents' platforms — Android and iOS — through one shared reader behind
  per-platform acquisition backends.
- **G5** — Pure JVM: no native MMKV binary, no JNI. The plugin is a single dependency-light jar
  that runs wherever the IDE runs.
- **G6** — Read-only and non-destructive: inspecting must never mutate on-device data.

## 5. Non-goals (v1)

Explicitly out of scope for the first release — do **not** build these:

- **NG1** — Editing or writing values back to the device. Read-only, always (G6).
- **NG2** — Decoding `FieldDecorator`-transformed values (encryption/compression). They are opaque
  on disk by design and render as blobs. See [ADR-0004](adr/0004-opaque-decorated-and-encrypted-fields.md).
- **NG3** — A general-purpose MMKV browser for non-Documents files. The plugin assumes the
  Documents layout; there is no "smart mode" that sniffs arbitrary MMKV. See
  [ADR-0002](adr/0002-documents-only-no-smart-mode.md).
- **NG4** — Recovering the original data-class *type name* of a document. It is not stored and
  cannot be recovered; only field names and values survive to disk.
- **NG5** — MMKV-level (whole-file) decryption. If a consumer initialized MMKV with a crypt key,
  the file is opaque without that key; supplying keys is deferred. See
  [ADR-0004](adr/0004-opaque-decorated-and-encrypted-fields.md).
- **NG6** — A standalone desktop app. The inspector is an IDE plugin; it lives where debugging
  already happens.

## 6. Success criteria

- Pointed at a running debuggable sample app, the plugin lists its collections, expands a document,
  and shows each field's decoded value — matching what the app wrote.
- A value written through a `FieldDecorator` shows as an opaque blob, clearly labeled, without
  breaking the surrounding tree.
- The same reader, unchanged, renders files pulled from Android, an iOS simulator, and a physical
  iOS device.
- No native library is bundled; the plugin builds and loads as a pure-JVM artifact.

## 7. Constraints

- **Access requires a debuggable / development-signed target.** Reading app-private storage on
  Android needs `run-as` (debuggable builds only); on iOS it needs `simctl`/`devicectl`
  (development-signed builds). Inherent to app-private files, not something the plugin can engineer
  away.
- **The reader reconstructs, it does not read library state.** It parses on-disk bytes; it never
  links or calls the Documents library. This keeps the two repos independent and lets the inspector
  read stores written by any Documents version whose format it understands.
