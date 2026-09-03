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
| `0x0100` | `PTY_OUTPUT` | byte string |
| `0x0101` | `PTY_INPUT` | `[commandId, byte-string]` |
| `0x0102` | `PTY_RESIZE` | `[columns, rows]` |
| `0x0200` | `PROCESS_STARTED` | `[processId]` |
| `0x0201` | `PROCESS_EXITED` | `[exitCode]` |
| `0x0202` | `SIGNAL` | `[kind, platformCode]` |
| `0x1000` | `HARNESS_MESSAGE` | schema reserved |
| `0x1001` | `HARNESS_STATUS` | schema reserved |
| `0x1010` | `TOOL_CALL` | schema reserved |
| `0x1011` | `TOOL_RESULT` | schema reserved |
| `0x1020` | `PROMPT` | schema reserved |
| `0x1030` | `ARTIFACT` | schema reserved |
| `0x1040` | `CHECKPOINT` | schema reserved |

Terminal bytes are opaque and preserved without text conversion. `commandId`
is the canonical textual input UUID used for host-lifetime deduplication.
Terminal dimensions are unsigned integers in the range 1 through 65535.
Process IDs are nonzero unsigned integers. Exit codes and platform signal codes
fit signed 32-bit integers. Portable signal kinds `1` through `5` may carry
`-1` or any non-negative platform signal or control code delivered by the host;
platform-specific kind `0xffff` requires a non-negative platform code.

Readers expose an unknown event type and its encoded payload as an opaque
record, preserve its complete encoded record, and continue with later events.

## Compatibility Fixtures

`session-events-v1.hex` is the canonical sequence for `PTY_OUTPUT`,
`PTY_RESIZE`, `PTY_INPUT`, and `PROCESS_EXITED`. It is byte-for-byte identical
to the Agent protocol fixture consumed by AgentD/server-side code.
`session-event-unknown-tail-v1.hex` freezes opaque unknown-event and trailing
field behavior. `generate-protocol-fixtures` can also generate their binary
forms plus a partial active-tail fixture.

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
| 16 | 8 | u64 | Client request ID |
| 24 | 4 | u32 | Payload length |
| 28 | 4 | u32 | CRC-32C of payload bytes |

The hard control payload maximum is 16 MiB. CRC fields use CRC-32C
(Castagnoli), reflected polynomial `0x82f63b78`, initial value `0xffffffff`,
and final XOR `0xffffffff`.

Request ID is a correlation value and is not a delivery deduplication key.
Clients may reuse it only when retrying the same request on a new connection.
The input UUID provides durable host-lifetime deduplication for `INPUT`.

The host closes the connection after bad magic, framing version, length, or
checksum. A semantic error receives `ERROR`; the connection may then continue.
Unknown request types receive `ERROR_UNSUPPORTED_MESSAGE`.

## Control Requests

| ID | Name | Payload schema v1 |
| ---: | --- | --- |
| `0x0001` | `INPUT` | UUID bytes followed by exact terminal input bytes |
| `0x0002` | `RESIZE` | u32 columns followed by u32 rows |
| `0x0003` | `SIGNAL` | u16 kind, zero u16 flags, and i32 platform code |
| `0x0004` | `TERMINATE` | Mode, reserved, and grace milliseconds |
| `0x0005` | `STATUS` | Empty |
| `0x0006` | `APPEND_EVENT` | Producer event UUID and typed payload |

`TERMINATE` is 8 bytes: u16 mode (`0` graceful, `1` force), u16 reserved zero,
and u32 grace milliseconds. Grace is ignored for force mode.

`APPEND_EVENT` begins with a 16-byte producer event UUID, u16 journal event
type, u16 payload-schema version, u32 event flags, then the exact event payload.
Version 1 accepts only event types in `0x1000-0x1fff`. The host assigns the
journal event ID. Producer UUID deduplication policy is defined with harness
ingress; it is not implied by control framing.

## Control Responses

| ID | Name | Payload schema v1 |
| ---: | --- | --- |
| `0x8000` | `ACCEPTED` | u64 assigned journal event ID, or zero |
| `0x8001` | `DUPLICATE` | u64 event ID of the original accepted input |
| `0x8002` | `ERROR` | u32 error code and UTF-8 detail |
| `0x8003` | `STATUS` | Fixed 64-byte status |

`ACCEPTED` returns the assigned journal event ID, or zero for a command that
does not append. `DUPLICATE` returns the original input event ID. Status bytes
28 through 35 contain the oldest available event ID and bytes 36 through 43
the latest event ID, using `u64::MAX` when absent.

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
temporary file in the session directory, apply the configured durability
policy, and atomically replace `metadata`. Readers ignore unknown object
fields. Its version-1 journal-bound fields are named
`oldestAvailableEventId` and `latestEventId`; both are nonzero `u64` values or
both are null. They are snapshots for discovery and status, not required
journal indexes. `activeSegment` is a nonzero segment number. The checked-in
`metadata-v1.json` is the canonical field and formatting example.

Metadata also contains session and journal identity, wall-clock creation and
start times, command, working directory, host/child process identity,
lifecycle state, terminal dimensions and type, sandbox description, and the
control endpoint. The dedicated metadata-manifest task owns further reduction
of those fields and per-event metadata writes.

`sandbox` contains boolean `requested`, enum `enforcement` (`none`, `landlock`,
or `future`), enum `unavailablePolicy` (`fail` or `run-unsandboxed`), and arrays
`readWritePaths` and `readOnlyPaths` of UTF-8 paths. `control` contains enum
`transport` (`unix-domain-socket` or `named-pipe`) and its endpoint.

## Command-Line Contract

The host accepts:

```text
session-host \
  --session-id ID \
  --session-dir PATH \
  --cwd PATH \
  [--cols 160] [--rows 50] \
  [--term xterm-256color] [--colorterm truecolor] \
  [--sandbox-policy PATH] \
  [--sandbox-unavailable fail|run-unsandboxed] \
  -- COMMAND [ARG...]
```

Session ID uses 1-128 ASCII letters, digits, dots, underscores, or hyphens and
is neither `.` nor `..`. Dimensions are 1-65535. Option names and environment
values must be valid UTF-8. Paths and child arguments use native OS strings at
the process boundary; the implementation rejects values it cannot encode in
required UTF-8 metadata rather than silently replacing bytes.

Duplicate and unknown options are errors. A requested sandbox defaults to
fail-closed when unavailable. `--help` and `--version` are standalone actions.

## Compatibility and Failure Rules

- A partial active CBOR tail returns preceding complete records and is
  truncatable on recovery.
- Invalid CBOR, non-array records, missing base fields, non-increasing event
  IDs, and invalid known payloads are format errors.
- Unknown event types and appended record fields do not stop iteration.
- Compressed closed segments decode to the same logical records as their
  uncompressed form.
- Missing or damaged persistent indexes cannot prevent journal discovery.
- A reader behind the first available event ID receives a retention gap.
