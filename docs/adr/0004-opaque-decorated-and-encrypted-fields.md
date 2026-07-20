# ADR-0004: Decorated and MMKV-encrypted values render as opaque blobs

**Status:** Accepted
**Date:** 2026-07-20

## Context

Two independent layers can make a value unreadable on disk:

1. **`FieldDecorator`** (Documents' per-value extension point — encryption, compression). It
   transforms bytes *after* CBOR encoding, so on disk the value is not CBOR at all.
2. **MMKV-level encryption.** If the consumer initialized MMKV with a crypt key, the whole file is
   enciphered (AES-128-CFB) and nothing decodes without that key.

Neither can be undone by the inspector without secrets it does not have.

## Decision

Render both as opaque blobs rather than failing or guessing. A value whose bytes are not
well-formed CBOR after the MMKV frame is stripped surfaces as `DocumentValue.Opaque` (shown as
"🔒 encrypted, N bytes" / a blob). MMKV whole-file decryption is not attempted in v1; supplying a
crypt key is deferred.

## Consequences

**Positive**
- The tree never breaks on data it cannot read: a decorated field sits next to plaintext fields,
  clearly labeled, and the rest of the document still renders.
- The reader's CBOR decoder falls back to `Opaque` on malformed input by design — no special-casing.
- Verified on real data: the sample app's `session` fields (written through the session-encryption
  decorator) come through as opaque, while its plaintext fields decode normally.

**Negative / cost**
- The developer sees "there is a value here, it is N bytes" but not its content. That is the correct
  and honest outcome for a plaintext tool; it is a stated non-goal to decode these (PRD NG2, NG5).

## Alternatives considered

- **Attempt heuristic decompression / decryption.** Can't, without the decorator's algorithm and
  keys, which live in app code the inspector doesn't run. Rejected.
- **Accept a user-supplied MMKV crypt key to decrypt whole files.** Plausible later — the reader
  already ports the crate's `decrypt` faithfully — but out of scope for v1. Deferred.
