# ADR-0001: Read MMKV with a pure-Kotlin reader, not a bundled native library

**Status:** Accepted
**Date:** 2026-07-20

## Context

The plugin runs on a desktop JVM inside the IDE, with no Android or iOS runtime. Once a collection
file is on the local disk it must parse MMKV's on-disk bytes itself. Two routes were available:
bundle Tencent's native MMKV (its C++ core has a POSIX/desktop build) behind JNI, or reimplement
the small read path in Kotlin.

## Decision

Ship a minimal pure-Kotlin reader. The read path is small — a varint decoder, a header parse, an
entry loop, and latest-value-wins dedupe — and has been independently reimplemented by others (see
[ADR-0003](0003-rust-mmkv-parser-oracle.md)).

## Consequences

**Positive**
- The plugin is a single, dependency-light jar that runs wherever the IDE runs — no per-OS native
  binary, no JNI, no loading a shared library inside the IntelliJ sandbox.
- The reader is trivially unit-testable on the JVM against byte fixtures.
- Read-only by construction: the code only ever reads bytes.

**Negative / cost**
- We own the format knowledge and must track any MMKV on-disk changes ourselves.
- Write support (were it ever wanted) would have to be built by hand — but writing is a non-goal
  (PRD NG1).

## Alternatives considered

- **Bundle native MMKV + JNI.** Tencent MMKV has a desktop C++ core but publishes no standalone
  desktop-JVM/JNI binding; the Kotlin Multiplatform port (`ctripcorp/mmkv-kotlin`) targets only
  Android/iOS/macOS and wraps the native library. Either route means shipping and loading a per-OS
  native binary plus hand-written JNI — heavy and fragile for what is a ~200-line parser. Rejected.
