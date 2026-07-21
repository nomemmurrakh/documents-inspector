# ADR-0007: One `DeviceSource` interface per platform; the reader is shared

**Status:** Accepted
**Date:** 2026-07-20

## Context

Documents targets Android and iOS. The bytes of a collection file must be pulled off a device,
emulator, or simulator before the reader can touch them — and each platform exposes app-private
storage differently (Android `adb run-as`, iOS simulator filesystem, iOS device `devicectl`).
The question is where that difference lives.

## Decision

Isolate acquisition behind a single `DeviceSource` interface: "give me a local copy of this app's
mmkv files." Everything below it — parsing, grouping, decoding, the tree — is platform-agnostic and
shared. Backends: `AndroidAdbSource`, `IosSimulatorSource`, and later `IosDeviceSource`. Adding a
platform is adding a backend, never a second parser.

Acquisition mechanisms per backend:

| Backend | Mechanism | MMKV dir on target |
| --- | --- | --- |
| `AndroidAdbSource` | `adb [-s <serial>] exec-out run-as <pkg> cat files/mmkv/<file>` | `/data/data/<pkg>/files/mmkv/` |
| `IosSimulatorSource` | `xcrun simctl get_app_container <udid> <bundleId> data` → file reads | `<sandbox>/Documents/MMKV/` |
| `IosDeviceSource` | `xcrun devicectl device copy from …` → local staging dir | app container `Documents/MMKV/` |

All three backends are now implemented. The iOS directory is `Documents/MMKV` (uppercase) — see
[ADR-0009](0009-ios-device-via-devicectl.md), which also covers why the device backend stages files
locally instead of reading them in place.

## Consequences

**Positive**
- The reader is written and tested once, on the JVM, independent of any device. The 31 reader tests
  need no device at all.
- Cross-platform support is additive: the iOS simulator is just a directory on the Mac, so once its
  sandbox path is resolved it is plain file I/O — the same code path as Android once bytes are local.
- Each backend must pull *both* `<name>` and `<name>.crc` (the size lives in the `.crc`, per
  [ADR-0005](0005-body-size-from-crc.md)) — a single obligation stated in one interface.
- Multiple attached Android targets are disambiguated with `adb -s <serial>` (a stable device
  serial from `adb devices`), not `-t <transport-id>` — transport ids are transient and change
  across reconnects, so a serial is the durable way to pin a target.

**Negative / cost**
- Every backend depends on external tooling (`adb`, `xcrun`) being installed and on the target being
  debuggable / development-signed. That constraint is inherent (PRD §7), not introduced here.

## Alternatives considered

- **Per-platform end-to-end paths** (acquire + parse together). Would duplicate the parser or fork
  it per platform — exactly what the shared reader avoids. Rejected.
