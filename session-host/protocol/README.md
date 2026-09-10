# Session Host Protocol Version 1

## Journal CBOR Sequence

A session journal is a sequence of numbered segment files:

```text
00000001.cbor.zst
00000002.cbor.zst
00000003.cbor
```

The active segment is an uncompressed `.cbor` file. Closed segments may be
stored as `.cbor.zst`; decompression produces the exact original logical CBOR
Sequence. File names establish segment order and do not encode event IDs.

Every event is one independent CBOR item:

```text
[eventId, eventType, payload, ...futureFields]
```

There is no segment header, block header, length prefix, checksum frame,
completion flag, or other framing outside CBOR. A complete CBOR item is the
only persisted record boundary. Readers ignore unknown trailing array fields.

`eventId` is an unsigned `u64`, unique and strictly increasing within one
session. It is derived from a monotonic clock relative to session start:

```text
raw = monotonicTimeSinceSessionStart()
eventId = max(raw, previousEventId + 1)
```

It is not a Unix timestamp and cannot be compared across sessions. Recovery
reads the last complete event so a restarted writer remains strictly above the
recovered ID even when its current monotonic reading is lower.

`readAfter(requestedEventId)` discovers each segment range from its first
event, starts at the last segment whose first ID is at most the requested ID,
skips records through that ID, and reads later segments in file order. A
requested ID below the first available ID produces an explicit retention gap
alongside the available events. No persistent index, cursor, or first/last
metadata is required for correctness.

An incomplete CBOR item at the end of the active `.cbor` file is a recoverable
crash tail. Readers return all preceding complete items, and recovery may
truncate the file to the last complete boundary. Invalid CBOR before that tail
is corruption. Closed and compressed segments must contain only complete
items.

Rotation occurs only between complete CBOR items. A single item larger than
the configured segment target remains indivisible in one segment. Closed
segments are compressed asynchronously without waiting for acknowledgement.
Physical-size retention deletes only the oldest closed prefix that is fully
covered by an acknowledged journal-event watermark and never deletes the
active raw segment. Without a watermark, or when meeting the configured
maximum would require deleting unacknowledged events, files remain over the
limit. After deletion, the retention-gap floor is derived from the first
record in the oldest remaining segment.

## Event Type Allocation

| Range | Owner |
| --- | --- |
| `0x0000-0x00ff` | Journal and system |
| `0x0100-0x01ff` | PTY/ConPTY |
| `0x0200-0x02ff` | Hosted process lifecycle |
| `0x1000-0x1fff` | Harness structured events |
| `0x2000-0x7fff` | Reserved for future Orion allocation |
| `0x8000-0xffff` | Invalid until a later journal version allocates it |

Version 1 assigns:

| ID | Name | CBOR payload |
| ---: | --- | --- |
| `0x0002` | `COMMAND_RESULT` | `[source, operationSequence, exactSourceEnvelope, outcome, detail]` |
| `0x0003` | `HOST_WARNING` | `[code, message]` |
| `0x0100` | `PTY_OUTPUT` | byte string |
| `0x0101` | `PTY_INPUT` | `[ptyInputId, byte-string]` |
| `0x0102` | `PTY_RESIZE` | `[columns, rows]` |
| `0x0103` | `PTY_CLOSED` | `[]` |
| `0x0200` | `PROCESS_STARTED` | `[processId]` |
| `0x0201` | `PROCESS_EXITED` | `[exitCode]` |
| `0x0202` | `SIGNAL` | `[kind, platformCode]` |
| `0x0203` | `SESSION_START_FAILED` | `[commandId, diagnostic, omittedByteCount]` |
| `0x1000` | `HARNESS_MESSAGE` | schema reserved |
| `0x1001` | `HARNESS_STATUS` | schema reserved |
| `0x1010` | `TOOL_CALL` | schema reserved |
| `0x1011` | `TOOL_RESULT` | schema reserved |
| `0x1020` | `PROMPT` | schema reserved |
| `0x1030` | `ARTIFACT` | schema reserved |
| `0x1040` | `CHECKPOINT` | schema reserved |

`HOST_WARNING` code is a nonzero unsigned 16-bit value and its message is
1–4096 strict UTF-8 bytes. Code `1` means `CGROUP_FALLBACK`. On Linux, exactly
one durable fallback warning precedes release of the held child when cgroup v2
delegation or the required `cgroup.kill` capability is unavailable. Failure to
persist this warning aborts startup while the child is still held. Normal cgroup
startup and macOS do not emit this warning. The shared
`process-control-events-v1.hex` fixture freezes its encoding.

Terminal bytes are opaque and preserved without text conversion. The 16-byte
input identity is preserved in the `PTY_INPUT` record; host replay protection
uses `operationSequence`, not that identity.
Terminal dimensions are unsigned integers in the range 1 through 65535.

The Unix terminal reader owns PTY availability. On EOF or the platform's final
PTY condition it marks the terminal unavailable and attempts one durable
`PTY_CLOSED` append after its final `PTY_OUTPUT`. With a writable journal there
is exactly one closure event and no later terminal output. A failed closure
append is reported to stderr without retrying it or restoring availability;
missing journal evidence does not prove that the terminal is available.

Output, closure, resize, and each nonblocking input write share the host's
journal mutex. An admitted INPUT or RESIZE that executes after closure performs
no terminal effect and writes no `PTY_INPUT` or `PTY_RESIZE`; it follows the
ordinary failed `COMMAND_RESULT` path with detail `PTY is closed` when that
append succeeds. An input already in progress may have written a prefix before
closure. Readiness polling releases the mutex, allowing closure and TERMINATE
to proceed while input is blocked.

Closure and root exit do not initiate termination. STATUS, LIST_PROCESSES,
addressed SIGNAL, TERMINATE, and ACK_JOURNAL remain available while owned
processes live. Foreground SIGNAL still requires a usable foreground terminal
group. The process owner decides when all owned processes have exited and been
reaped; finalization then waits for the reader and admitted operations before
`PROCESS_EXITED`. Availability is local resource state, not a metadata field or
a persisted session lifecycle replica. The additive shared
`pty-closure-v1.hex` fixture freezes output, empty closure, and final exit;
existing fixtures and journal version 1 remain unchanged.

Process IDs are nonzero unsigned integers. Exit codes and platform signal codes
fit signed 32-bit integers. Portable signal kinds `1` through `5` may carry
`-1` or any non-negative platform signal or control code delivered by the host;
platform-specific kind `0xffff` requires a non-negative platform code.

`COMMAND_RESULT` stores source (`1` server, `2` manual), the sequence, the exact
opaque server command item (server) or complete control payload (manual) as a
CBOR byte string, and the operation outcome. Result outcomes are succeeded `1`, failed `2`,
rejected `3`, and ambiguous `4`;
ambiguous is reserved for shared readers and is not generated by a live host.
Successful results have empty detail. Other result detail is strict UTF-8 and
at most 4096 bytes.

`SESSION_START_FAILED` stores the `START_SESSION` CommandId, a strict UTF-8
diagnostic of at most 1 MiB, and the number of omitted diagnostic bytes. A
longer diagnostic keeps a UTF-8-safe prefix of at most 64 KiB and suffix of at
most 960 KiB. Once the host creates a journal, it attempts one durable start
outcome before leaving the start phase: `PROCESS_STARTED` after the child
crosses the exec boundary or `SESSION_START_FAILED` for an earlier failure.
A failed append can leave no durable outcome. In particular, failure to append
`PROCESS_STARTED` is logged to stderr and the host still publishes the live
session; it does not record `SESSION_START_FAILED` after exec. Failures before
journal creation have no native outcome record.

Readers expose an unknown event type and its encoded payload as an opaque
record, preserve its complete encoded record, and continue with later events.

## Compatibility Fixtures

`session-events-v1.hex` is the canonical sequence for `PTY_OUTPUT`,
`PTY_RESIZE`, `PTY_INPUT`, and `PROCESS_EXITED`. It is byte-for-byte identical
to the Agent protocol fixture consumed by AgentD/server-side code.
`session-event-unknown-tail-v1.hex` freezes opaque unknown-event and trailing
field behavior. `command-events-v1.hex` freezes the system command result record,
an unsigned operation sequence above `i64::MAX`, and an exact command envelope
with an unknown trailing field. `start-outcomes-v1.hex` freezes the two hosted
process start outcomes and is mirrored into `agent-protocol` for dependent
readers. `generate-protocol-fixtures` can also generate their binary forms plus
a partial active-tail fixture.

## Control Framing

Control remains a reliable binary byte-stream protocol. Unix uses a Unix
domain socket and Windows will use a named pipe. Every request and response is
one 32-byte little-endian header followed by its payload:

| Offset | Size | Type | Field |
| ---: | ---: | --- | --- |
| 0 | 4 | bytes | Magic `ORCT` |
| 4 | 2 | u16 | Control protocol version, `1` |
| 6 | 2 | u16 | Header length, `32` |
| 8 | 2 | u16 | Message type |
| 10 | 2 | u16 | Payload-schema version |
| 12 | 4 | u32 | Flags, zero in v1 |
| 16 | 8 | u64 | Control sequence (`u64::MAX` means no sequence) |
| 24 | 4 | u32 | Payload length |
| 28 | 4 | u32 | CRC-32C of payload bytes |

The hard control payload maximum is 16 MiB. CRC fields use CRC-32C
(Castagnoli), reflected polynomial `0x82f63b78`, initial value `0xffffffff`,
and final XOR `0xffffffff`.

The control sequence is the one sequence used to correlate a response and to
identify an operation. `u64::MAX` is reserved as the response sequence when a
request has no associated sequence. Source-aware operation controls use the
sequence from this header and the command envelope described below.

The host closes the connection after bad magic, framing version, length, or
checksum. A semantic error receives `ERROR` or, for an operation with a known
sequence, `RECEIVED` with an error payload; the connection may then continue.
Unknown request types receive `ERROR_UNSUPPORTED_MESSAGE`.

## Control Requests

| ID | Name | Payload schema |
| ---: | --- | --- |
| `0x0001` | `INPUT` | v3 source-aware wrapper and UUID plus input bytes |
| `0x0002` | `RESIZE` | v3 source-aware wrapper and dimensions |
| `0x0003` | `SIGNAL` | v3 foreground signal; v4 addressed signal |
| `0x0004` | `TERMINATE` | v3 source-aware wrapper and termination |
| `0x0005` | `STATUS` | v1 empty payload |
| `0x0006` | `APPEND_EVENT` | v1 producer event UUID and typed payload |
| `0x0007` | `ACK_JOURNAL` | v3 source-aware wrapper and journal event ID |
| `0x0008` | `LIST_PROCESSES` | v1 empty payload |

Every schema-3 operation, and schema-4 addressed `SIGNAL`, has this little-endian
prefix before its command-specific effect payload:

```text
u16 source (1 server, 2 manual)
u16 reserved zero
u32 serverCommandEnvelopeLength
serverCommandEnvelopeLength bytes
```

The frame sequence is nonzero and is not `u64::MAX`. For server controls the envelope is
a nonempty, opaque, exact server CBOR item. For manual controls its length must
be zero and the entire control payload becomes the result's source envelope.
The server envelope may contain the server command
identifier, but that identifier is not duplicated in the native operation
wrapper. Only server controls check and advance the sequence high-water mark;
manual sequences may repeat and do not change server admission. The envelope
remains opaque. The 16 MiB maximum applies to the complete wrapped payload.
`control-source-aware.bin` freezes both sources for the five operation controls.
The live host rejects operation schemas 1 and 2. Schema 4 is accepted only for
addressed `SIGNAL`; it does not introduce a second execution path.

The host serializes new operation effects and keeps only an in-memory accepted
server sequence high-water mark. A server sequence at or below that mark is stale; gaps above
it are valid. Every new effect is applied once, synchronously. The host writes
one `COMMAND_RESULT` after every operation effect when the durable append succeeds,
with an empty detail after successful application or diagnostic detail when
application fails. Admitted operations receive an empty `RECEIVED` before the
effect, with the frame sequence in the response header. Validation
and stale-sequence failures receive `RECEIVED` with the same sequence and an
error payload. Journal append failures go only to stderr.

For `SERVER`, `operationSequence` identifies an operation and protects it from
replay. For `MANUAL`, it is only a live response-correlation value and may
repeat. It is not a FIFO position across control connections. `operation_order` serializes
ordinary effects, but `TERMINATE` bypasses it so it can signal descendants while
a blocked ordinary effect is still running. `RECEIVED` may therefore be
observed before an earlier effect or result is complete, and journal readers
must match `COMMAND_RESULT` records by `operationSequence` rather than by record
order. Match source as well as sequence because manual sequences can repeat.

`SIGNAL` schema 3 has an eight-byte effect: u16 portable kind, u16 reserved zero,
and i32 platform code. It targets the terminal foreground group. Schema 4 has
the same eight bytes followed by a nonzero u64 process token, for a total of
16 effect bytes. Schema and effect length must agree. Portable kinds are
`1` interrupt, `2` terminate, `3` kill, `4` hangup, `5` quit, and `0xffff`
platform-specific. A portable platform code is `-1` or the actual platform
signal; a platform-specific signal must be positive on Unix.

Addressed signals resolve only tokens issued by this live host's process owner.
Linux refreshes ownership and resolves the token to a retained pidfd; an exited
or unknown token never falls back to a numeric PID. A stale/foreign token is
an admitted effect failure and produces the existing failed `COMMAND_RESULT`
when journal append succeeds, with no `SIGNAL` event. A delivered addressed
signal uses the existing `SIGNAL` event and ordinary result path. No intent,
retry ledger, timer, or automatic escalation is added. `TERMINATE` still
targets the whole owned tree.

On macOS, the development host retains PID/start-time identity, refreshes the
owned set, and rechecks the start time immediately before `kill(pid)`. macOS
has no retained pidfd: exit/reuse between that last check and `kill` remains a
best-effort race. Its discovery can also miss detached descendants that lose
ancestry and terminal ownership before observation. Linux kernel-identity
guarantees do not apply to macOS.

The PTY reader continues draining output after a `PTY_OUTPUT` append failure.
The host reports the failure on stderr and may append later chunks if the
writer recovers. Output that cannot be journaled is discarded, so an I/O
failure may leave the journal incomplete while the child and PTY service remain
running.

`COMMAND_RESULT` describes the host's execution attempt, not a rollback
boundary. `Failed` does not imply that no side effect occurred: `INPUT` may
have transferred only part of the requested bytes, and a signal may have been
delivered to some or none of its targets. The `PTY_INPUT` journal record
contains the requested bytes, not a delivery acknowledgement. If no
`COMMAND_RESULT` exists, the effect is unknown because the host may have
executed it before the journal append failed. The server may retry a `SERVER`
command with a new sequence as a new attempt, which may repeat an earlier
partial effect. A `MANUAL` sender does not retry uncertain delivery.

`TERMINATE` is 4 bytes: u16 mode (`0` graceful, `1` force) and u16 reserved zero.
The server owns escalation timing and sends a later force operation when needed.

`APPEND_EVENT` begins with a 16-byte producer event UUID, u16 journal event
type, u16 payload-schema version, u32 event flags, then the exact event payload.
The reserved version-1 layout allows event types in `0x1000-0x1fff`, with a
host-assigned journal event ID and no producer UUID deduplication semantics.
The current Unix host rejects `APPEND_EVENT` with an unsupported-message error;
ordered harness ingress is not implemented yet.

`ACK_JOURNAL` is recorded as `COMMAND_RESULT` using its source, operation
sequence, and exact source envelope: the opaque server envelope for `SERVER`,
or the complete operation payload for `MANUAL`. Its effect is the supplied
journal event ID. Higher-level AgentD server forwarding must send it only after
the server confirms durable storage through that event ID; the shared Java
command model and codec already support ACK. The host
first durably publishes the monotonic
`control-retention-state` sidecar and only then allows newly covered closed
segments to be deleted. A repeated or lower value does not lower the watermark.
The sidecar contains only `stateVersion: 1` and `acknowledgedEventId`; it is
local deletion permission, never a replication cursor or server authority.
Zero and values beyond the current logical journal tail are invalid.

## Control Responses

Successful operation controls, including `ACK_JOURNAL`, receive a transient
`RECEIVED` response after admission and before the effect is applied. The
response header carries the operation sequence and its payload is empty. If an
operation cannot be admitted, the response is `RECEIVED` with the same
sequence and an error payload. `ERROR` uses the same response-header sequence;
when no sequence can be associated with the request it is `u64::MAX` (the
logical value `-1`). `STATUS` returns a state snapshot and echoes its control
sequence in the response header:

| ID | Name | Payload schema |
| ---: | --- | --- |
| `0x8000` | `RECEIVED` | empty on success; u32 error code and UTF-8 detail on rejection |
| `0x8002` | `ERROR` | u32 error code and UTF-8 detail |
| `0x8003` | `STATUS_RESPONSE` | Fixed 64-byte status |
| `0x8004` | `LIST_PROCESSES_RESPONSE` | u32 count followed by count fixed 24-byte entries |

`LIST_PROCESSES` and its response use payload schema 1. The request must be
empty; its unsigned sequence is correlation only (including zero and
`u64::MAX`) and is echoed unchanged. Listing never enters operation admission,
advances its high-water mark, or appends journal events/results. It remains
available while descendants keep the host live after root exit. A snapshot
reflects discovery at request time; processes may fork or exit while it is read.

The response begins with a little-endian u32 count and exactly `count * 24`
entry bytes. The complete payload is bounded to 16 MiB (at most 699050 entries).
An oversized snapshot fails the request rather than returning a partial list.
Each entry is:

| Offset | Size | Type | Field |
| ---: | ---: | --- | --- |
| 0 | 8 | u64 | Nonzero incarnation-local process token |
| 8 | 8 | u64 | Diagnostic Unix PID, 1 through i32::MAX |
| 16 | 4 | u32 | Flags: bit 0 original root, other bits zero |
| 20 | 4 | u32 | Reserved zero |

Tokens and PIDs are unique within a snapshot, and at most one entry is the
original root. Tokens are allocated after identity acquisition, remain stable
while tracked, and are never reused by this host incarnation. Exit retires the
token; a process reusing that PID receives a new token. They are scoped to the
endpoint/incarnation and cannot be transferred to another session. PID is
diagnostic only. `process-controls.bin`, consumed by Rust and Java tests,
freezes listing and both sources of addressed signals; existing fixtures stay
byte-for-byte unchanged.

An empty `RECEIVED` means only that the operation passed admission and is
scheduled for one execution attempt by this host incarnation; it is not a
durable journal confirmation. There is no separate durable acceptance record.
The execution result is observed through `COMMAND_RESULT` when its append
succeeds. Failure to deliver `RECEIVED` does not cancel an admitted effect.
Validation and stale-sequence failures use `RECEIVED` with an error. An I/O failure while
appending a journal event is reported on stderr; the effect is never retried.

Status bytes 28 through
35 contain the oldest available event ID and bytes 36 through 43 the latest
event ID, using `u64::MAX` when absent.

Error detail is at most 4096 bytes and is diagnostic, not machine-readable.
Error codes are `1` invalid request, `2` unsupported message, `3` unsupported
payload schema, `4` invalid state, `5` I/O failure, `6` policy failure, and `7`
payload too large.

The status payload is:

| Offset | Size | Type | Field |
| ---: | ---: | --- | --- |
| 0 | 2 | u16 | State: 1 starting, 2 running, 3 exited, 4 failed |
| 2 | 2 | u16 | Flags; bit 0 host live, bit 1 child live, bit 2 sandboxed |
| 4 | 4 | u32 | Current columns |
| 8 | 4 | u32 | Current rows |
| 12 | 8 | u64 | Host PID |
| 20 | 8 | u64 | Child PID, `u64::MAX` if absent |
| 28 | 8 | u64 | Oldest available event ID, `u64::MAX` if absent |
| 36 | 8 | u64 | Latest event ID, `u64::MAX` if absent |
| 44 | 4 | i32 | Exit code, `i32::MIN` if unavailable |
| 48 | 4 | i32 | Exit signal, `-1` if unavailable |
| 52 | 2 | u16 | Journal format version |
| 54 | 2 | u16 | Control protocol version |
| 56 | 8 | bytes | Reserved, zero |

## Metadata Version 1

`metadata` is UTF-8 JSON with `metadataVersion: 1`. Writers create a complete
temporary file in the session directory and atomically replace `metadata`.
Readers ignore unknown object fields. The checked-in `metadata-v1.json` is the
canonical field and formatting example.

Metadata is a session manifest, not a journal index or lifecycle record. It
contains compatibility versions, session identity, wall-clock creation and
start times, command, working directory, host and child process identity,
initial and latest successfully applied terminal dimensions, terminal type,
sandbox description, and the control endpoint. Writers persist the manifest
when the session directory is created, after publishing the child process
identity, and after a successful resize. Output, input, signals, process exit,
journal appends, rotation, and retention do not rewrite it.

Journal identity, segment discovery, retained event bounds, and exact lifecycle
history come from journal files. A live `STATUS` response derives its lifecycle
observation from current host state and its oldest/latest event IDs from the
active journal writer; it does not read those facts from metadata. The removed
`journalId`, `state`, `activeSegment`, `oldestAvailableEventId`, and
`latestEventId` fields are not part of the metadata v1 manifest contract.

`sandbox` contains boolean `requested`, enum `enforcement` (`none`, `landlock`,
or `future`), compatibility enum `unavailablePolicy`, and arrays `readWritePaths`
and `readOnlyPaths` of UTF-8 paths. Writers use the fixed value
`run-unsandboxed`; readers continue to accept the legacy `fail` value. `control`
contains enum `transport` (`unix-domain-socket` or `named-pipe`) and its endpoint.

Sandbox metadata may additionally contain `policyVersion`, `handledRights`,
and ordered `rules`. Each rule has an absolute `path` and a `rights` array using
the symbolic names from the compiled-policy bit table. These fields describe
the complete compiled policy; the legacy path arrays contain only rules whose
mask exactly matches the old read-only or read-write presets. Readers must
continue to accept metadata written before the granular fields were added.

## Command-Line Contract

The host accepts:

```text
session-host \
  --session-id ID \
  --start-command-id COMMAND_ID \
  --session-dir PATH \
  --cwd PATH \
  [--cols 160] [--rows 50] \
  [--term xterm-256color] [--colorterm truecolor] \
  [--sandbox-policy PATH] \
  [--journal-segment-bytes 67108864] \
  [--journal-max-bytes 1073741824] \
  -- COMMAND [ARG...]
```

Session ID uses 1-128 ASCII letters, digits, dots, underscores, or hyphens and
is neither `.` nor `..`. Start CommandId matches
`[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`. Dimensions are 1-65535. Option names and
environment values must be valid UTF-8. Paths and child arguments use native
OS strings at the process boundary; the implementation rejects values it
cannot encode in required UTF-8 metadata rather than silently replacing bytes.

Journal limits are positive decimal byte counts. The segment target defaults
to 67,108,864 bytes (64 MiB), and the physical journal maximum defaults to
1,073,741,824 bytes (1 GiB). The journal maximum must be at least the segment
target.

Duplicate and unknown options are errors. When Landlock ABI 9 is unavailable,
a requested sandbox emits a warning and starts without filesystem restrictions.
Policy decoding, grant-path validation, ruleset construction, rule application,
incomplete enforcement, and child restriction failures remain fatal. `--help`
and `--version` are standalone actions.

## Compiled Landlock Policy v1

AgentD passes the native host a private canonical-CBOR policy file. The value is
`[version, handledRights, rules]`; each rule is `[absolutePath, grantedRights]`.
Version 1 requires `version == 1` and `handledRights == 131071`, handling all
Landlock filesystem rights from ABI 9 even when no rule grants one. Rules have
non-zero masks, are unique, and are sorted by the raw UTF-8 bytes of normalized
absolute paths. Arrays are definite-length, and integers and text lengths use
their shortest CBOR representation. Trailing data is invalid.

Bits 0 through 16 respectively mean `execute`, `write-file`, `read-file`,
`read-dir`, `remove-dir`, `remove-file`, `make-char`, `make-dir`, `make-reg`,
`make-sock`, `make-fifo`, `make-block`, `make-sym`, `refer`, `truncate`,
`ioctl-dev`, and `resolve-unix`. Decoders limit the file to 1 MiB, paths to
4096 UTF-8 bytes, and rules to 32768. The shared v1 fixture is
`fixtures/sandbox-policy-v1.hex`.

## Compatibility and Failure Rules

- A partial active CBOR tail returns preceding complete records and is
  truncatable on recovery.
- Invalid CBOR, non-array records, missing base fields, non-increasing event
  IDs, and invalid known payloads are format errors.
- Unknown event types and appended record fields do not stop iteration.
- Compressed closed segments decode to the same logical records as their
  uncompressed form.
- Closed segments are not deleted before their last event ID is acknowledged.
- Missing or damaged persistent indexes cannot prevent journal discovery.
- A reader behind the first available event ID receives a retention gap.
