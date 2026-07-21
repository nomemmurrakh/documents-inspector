# ADR-0009: Reach physical iOS devices with `devicectl`, staging files locally

**Status:** Accepted
**Date:** 2026-07-21

## Context

[ADR-0007](0007-device-source-per-platform.md) anticipated a third backend for physical iOS devices
and named `xcrun devicectl` as its mechanism. Simulators were easy: `simctl get_app_container`
returns a directory on the Mac, so `IosSimulatorSource` reads the files in place. A physical device
has no such path — its sandbox is only reachable through a remote file service — so the backend has
to be shaped differently even though it decodes the same bytes.

## Decision

Add `IosDeviceSource`, driving `xcrun devicectl` through a `Devicectl` seam, with three calls:

| Purpose | Command |
| --- | --- |
| Enumerate devices | `devicectl list devices` |
| Enumerate apps (prefill) | `devicectl device info apps --device <id>` |
| List the store | `devicectl device info files --device <id> --domain-type appDataContainer --domain-identifier <bundleId> --subdirectory Documents/MMKV` |
| Pull a file | `devicectl device copy from … --source <path> --destination <local>` |

Because the files cannot be read in place, `pull()` copies each collection into a **temporary
staging directory** (a `PullTarget` seam), reads the bytes back, and deletes the directory in a
`finally`. The reader above is untouched — this remains "add a backend, never a second parser".

Four behaviours of `devicectl` shape the implementation, all verified live against a paired iPhone
during this work:

1. **JSON to a file is the only supported machine interface.** `devicectl --help` states stdout is
   for humans and "not guaranteed to be stable across releases", while `--json-output` is "versioned
   and will remain stable". So the `Devicectl` seam captures the *JSON file's contents*, not stdout —
   the one place where this project's seams differ from `Adb`/`Simctl`.
2. **It exits 0 even on failure**, reporting the failure as an `error` object in that JSON. The exit
   code is therefore ignored and the JSON is the source of truth — the same trap as `simctl listapps`
   ([ADR-0008](0008-prefill-app-id-from-the-ide.md)), independently rediscovered here.
3. **`relativePath` is relative to the requested `--subdirectory`.** With
   `--subdirectory Documents/MMKV` an entry reads `cache`; listing the whole domain instead gives
   `Documents/MMKV/cache`. The parser therefore strips the prefix only when present and takes the
   path as-is otherwise. Getting this wrong is what made the first live run report "No collections
   found" — a fixture built from a whole-domain listing passed happily while the real
   subdirectory query returned nothing.
4. **App-id prefill filters on `builtByDeveloper`.** App Store and system apps cannot have their
   container read, so offering them would only produce failures.

For prefill ordering, apps holding a Documents store are ranked first, matching the simulator
([ADR-0008](0008-prefill-app-id-from-the-ide.md)). The device listing carries no container path, so
unlike the simulator the check cannot be answered locally — each app needs its own `info files` round
trip (~1s). Those probes run **concurrently** (five in ~1.7s rather than ~5s), are skipped entirely
when only one app is installed, and a failed probe means "no store" rather than dropping the app.

The MMKV directory constant is **`Documents/MMKV`**, uppercase. This was found live: a real device
rejects `Documents/mmkv` outright, and the simulator only ever tolerated the lowercase spelling
because the Mac's filesystem is case-insensitive. The constant is shared with `IosSimulatorSource`,
so this corrected a latent bug there rather than introducing one here.

## Consequences

**Positive**
- Physical iOS devices become inspectable, completing the platform matrix in ADR-0007.
- The corrected `Documents/MMKV` casing removes a latent portability bug that would have surfaced on
  any case-sensitive filesystem.
- Discovery and pulling are behind the `Devicectl` and `PullTarget` seams, so all 18 tests for this
  backend run with no device attached.

**Negative / cost**
- Pulling is materially slower than the simulator path: every file is a round trip over the network
  or cable, versus a local read. Acceptable for stores of this size (~16 KB per collection).
- `devicectl` needs a paired, unlocked, developer-mode device, and its first call per session pays a
  tunnel-establishment cost. Failures surface as messages, not exceptions.
- The staging directory is a new failure mode: a copy that reports success but writes nothing is
  detected explicitly and reported rather than decoded as empty bytes.

## Alternatives considered

- **`ios-deploy` or `libimobiledevice`.** Third-party dependencies the user would have to install,
  where `devicectl` ships with Xcode, which this workflow already requires. Rejected.
- **Mounting the container and reading in place**, mirroring the simulator backend. There is no
  supported way to do this for a device sandbox; the file service is the interface. Rejected.
- **Parsing `devicectl`'s stdout** instead of its JSON file. Explicitly unsupported and documented as
  unstable across releases. Rejected.
- **Reusing `CollectionFiles`** for the device path. It models "list and read a local directory",
  which a device sandbox is not; forcing it would have hidden the copy step that actually defines
  this backend. A separate `PullTarget` seam keeps the staging explicit. Rejected.
