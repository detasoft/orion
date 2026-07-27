# Code Review: 85edfbf

Commit: Implement native receive-pack parser layer and git-native-storage module  
Effort: high (3 angles × 6 candidates → verify)  
Date: 2026-07-27

## Findings

### CONFIRMED — C3: Objects stored before ref updates; packAccepted=true even on full STALE

**File:** `NativeReceivePackService.java:51`

`objectStore.putAll(quarantine)` is called unconditionally before the ref-update
loop.  When every `refStore.update()` returns `STALE`, all new objects are
permanently written to the shared store with no ref pointing at them.
`ReceiveResult.success(refResults)` is returned regardless, so
`packAccepted=true` even though no ref moved.

Consequence: dangling objects accumulate silently; partial-push state (some
refs ok, others ng) is indistinguishable from a clean push at the API level.

**Fix direction:** promote objects only after all ref updates succeed, or treat
a fully-stale batch as `packFailure`.

---

### CONFIRMED — C5: oldId not validated against object store for update commands

**File:** `NativeReceivePackService.java:46`

The validation loop checks only that `newId` is present in quarantine or
objectStore.  For update commands (non-null `oldId`) there is no check that
`oldId` is a known object.  The only enforcement is the CAS inside
`LooseRefStore.update()` which compares the string against the stored ref
value — ancestry is not verified at the object level.

**Fix direction:** add `objectStore.contains(GitObjectId.of(command.oldId()))`
for every update command.

---

### CONFIRMED — C6: clientCapabilities ignored; caller cannot determine wire-response format

**File:** `NativeReceivePackService.java:35`

`commandSection.clientCapabilities()` is never read.  `ReceiveResult` carries
no record of negotiated capabilities, so any caller that must decide whether to
use side-band-64k framing or report-status encoding has no signal from the
service.

**Fix direction:** pass the capability resolution result through `ReceiveResult`
or accept a `ReceivePackCapabilityResolution` as a parameter.

---

### CONFIRMED — C8: Delete command produces misleading packFailure instead of explicit rejection

**File:** `NativeReceivePackService.java:44`

`ReceivePackCommandParser` already rejects delete commands, but
`NativeReceivePackService.receive()` accepts any `ReceivePackCommandSection`
directly.  A command with `newId = NULL_ID` falls through to the object-
presence check and returns `packFailure("missing object 000...0")` instead of a
clear "delete not supported" error.

**Fix direction:** guard at the top of `receive()`:
`if (ReceivePackCommand.NULL_ID.equals(command.newId())) return packFailure("delete commands are not supported")`.

---

### CONFIRMED — C9: ReceivePackCapabilityResolver has no callers; unknown capabilities silently accepted

**File:** `ReceivePackCapabilityResolver.java` (no call sites in service layer)

`ReceivePackCapabilityResolver.resolve()` is a complete, tested implementation
that correctly returns `accepted()=false` for unknown capabilities such as
`atomic`.  However, `NativeReceivePackService` never calls it; the push is
processed unconditionally regardless of what capabilities the client requested.
A client requesting `atomic` will receive partial ref updates when some commands
are STALE — the opposite of what it asked for.

**Fix direction:** wire `ReceivePackCapabilityResolver` into `NativeReceivePackService`
(or into the caller) and reject the push when `accepted()=false`.

---

### PLAUSIBLE — C10: LooseObjectStore.putAll() is not atomic under concurrent access

**File:** `LooseObjectStore.java:49`

`ConcurrentHashMap.putAll()` is not atomic.  A concurrent reader calling
`objectStore.contains(id)` during the merge of a quarantine store can observe
partially-copied state and return `false` for an id that is mid-transfer,
causing a spurious `packFailure("missing object …")` for a valid push.

Currently low risk because `receive()` is single-threaded per request, but
becomes a real race if multiple requests share the same `LooseObjectStore`
concurrently.

---

## Refuted candidates (not bugs)

| Candidate | Reason |
|-----------|--------|
| C1: inflate() startPos dead variable | `finally` block computes consumed correctly without startPos; no buffer corruption on exception path |
| C2: NUL byte in capabilityLine | `commandLine + capabilityLine` reconstructs the original full line; `parseAdvertisementLine` already does `indexOf('\0')` and correctly strips the command prefix |
| C4: LooseRefStore putIfAbsent race | `putIfAbsent` return value is used directly (no second map lookup), so the comparison is atomic |
| C7: objectCount Integer.MAX_VALUE | `readAll()` enforces `maxPackBytes` before the parse loop; a pack that large is rejected first |
