# Session Host Protocol Version 1

## Conventions

All multibyte integers are unsigned little-endian unless a field is explicitly
declared signed. Sizes include bytes only, never UTF-8 characters. All reserved
fields are written as zero and ignored by a v1 reader. A reader rejects a known
header whose reserved field is nonzero only when this document explicitly says
so.

CRC fields use CRC-32C (Castagnoli), reflected polynomial `0x82f63b78`, initial
value `0xffffffff`, and final XOR `0xffffffff`. The CRC of an empty payload is
zero. Header CRC fields cover all preceding bytes in that fixed header.

The hard maximum record or control payload is 16 MiB. Implementations may
configure smaller command and event limits. A length above the hard maximum is
invalid framing and must be rejected before allocating the declared size.

## Journal Segment

A journal is logically one ordered event stream and physically zero or more
files named `journal-NNNNNN.seg`. A segment begins with this 64-byte header:

| Offset | Size | Type | Field |
| ---: | ---: | --- | --- |
| 0 | 8 | bytes | Magic `ORJSEG01` |
| 8 | 2 | u16 | Journal format version, `1` |
| 10 | 2 | u16 | Header length, `64` |
| 12 | 4 | u32 | Flags, zero in v1 |
| 16 | 16 | bytes | Stable journal UUID bytes |
| 32 | 8 | u64 | Segment sequence, starting at `1` |
| 40 | 8 | u64 | Segment creation epoch milliseconds |
| 48 | 12 | bytes | Reserved, zero |
| 60 | 4 | u32 | CRC-32C of bytes 0 through 59 |

Segment sequences increase by one. All segments for one session carry the same
journal UUID, also stored in metadata. A reader reports a format error for a
bad magic, checksum, header length, or unsupported journal version. A later
compatible version may extend the header by increasing its length.

After the segment header, blocks are concatenated without padding.

## Journal Block

Every block starts with a 64-byte header:

| Offset | Size | Type | Field |
| ---: | ---: | --- | --- |
| 0 | 8 | bytes | Magic `ORJBLK01` |
| 8 | 2 | u16 | Journal format version, `1` |
| 10 | 2 | u16 | Header length, `64` |
| 12 | 2 | u16 | Codec: `0` none, `1` Zstandard |
| 14 | 2 | u16 | Flags; bit 0 is `FINAL` |
| 16 | 8 | u64 | Block sequence, starting at `0` in each segment |
| 24 | 8 | u64 | First record timestamp |
| 32 | 8 | u64 | Last record timestamp |
| 40 | 4 | u32 | Uncompressed payload length |
| 44 | 4 | u32 | Stored payload length |
| 48 | 4 | u32 | Record count |
| 52 | 4 | u32 | Reserved, zero |
| 56 | 4 | u32 | CRC-32C of the stored payload |
| 60 | 4 | u32 | CRC-32C of bytes 0 through 59 |

The stored payload follows immediately and contains either concatenated record
frames or one Zstandard frame whose decompressed bytes are those same frames.
Zstandard content size may be absent from the frame; the header's uncompressed
length is authoritative and bounded by implementation configuration. A block
must contain whole records and its first/last timestamps and count must match
the decoded records.

A reader ignores an incomplete final compressed block. For an incomplete final
uncompressed block, it may recover every complete record frame whose payload
checksum is valid, then reports the remaining bytes as an ignored crash tail.
It never scans past a corrupt complete block to guess a later boundary.

`FINAL` means the writer completed the block. Version 1 readers accept only
codecs 0 and 1. An unknown codec is an unsupported block, not an unknown event,
and terminates reading of that segment.

## Journal Record

The uncompressed block payload contains records with a 32-byte header followed
by exactly `payloadLength` bytes:

| Offset | Size | Type | Field |
| ---: | ---: | --- | --- |
| 0 | 4 | bytes | Magic `ORJR` |
| 4 | 2 | u16 | Record framing version, `1` |
| 6 | 2 | u16 | Header length, `32` |
| 8 | 2 | u16 | Event type |
| 10 | 2 | u16 | Type-specific payload-schema version |
| 12 | 4 | u32 | Event flags, zero unless defined by the event schema |
| 16 | 8 | u64 | Session-relative monotonic timestamp in nanoseconds |
| 24 | 4 | u32 | Payload length |
| 28 | 4 | u32 | CRC-32C of payload bytes |

The writer assigns timestamps using:

```text
raw = monotonicNow - sessionStart
timestamp = max(raw, previousTimestamp + 1)
```

Timestamp zero is not written. It represents the cursor before the first
record. A read cursor means records with `timestamp > cursor`. The writer must
reject timestamp exhaustion instead of wrapping.

An unknown event type, or a known type with an unknown payload-schema version,
is surfaced as an opaque event after validating its declared payload length and
checksum. The reader preserves its framing fields and payload and continues
journal iteration; a consumer may skip semantic interpretation of the event.

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

| ID | Name | Payload schema |
| ---: | --- | --- |
| `0x0100` | `PTY_OUTPUT` | v1 raw bytes |
| `0x0101` | `PTY_INPUT` | v1 input UUID plus raw bytes |
| `0x0102` | `PTY_RESIZE` | v1 columns and rows |
| `0x0200` | `PROCESS_STARTED` | v1 child process ID |
| `0x0201` | `PROCESS_EXITED` | v1 exit code and signal |
| `0x0202` | `SIGNAL` | v1 portable signal kind and platform code |
| `0x1000` | `HARNESS_MESSAGE` | schema to be assigned |
| `0x1001` | `HARNESS_STATUS` | schema to be assigned |
| `0x1010` | `TOOL_CALL` | schema to be assigned |
| `0x1011` | `TOOL_RESULT` | schema to be assigned |
| `0x1020` | `PROMPT` | schema to be assigned |
| `0x1030` | `ARTIFACT` | schema to be assigned |
| `0x1040` | `CHECKPOINT` | schema to be assigned |

Reserved harness names do not make their payload schemas valid. Until a schema
is assigned, v1 readers expose those records as opaque typed events.

## Event Payload Schemas

All payloads below use schema version `1`.

### `PTY_OUTPUT`

The entire payload is exactly the bytes read from the terminal master. It has
no text encoding and no internal length. ANSI/VT sequences, invalid UTF-8, NUL,
and CR/LF bytes are preserved.

### `PTY_INPUT`

| Offset | Size | Type | Field |
| ---: | ---: | --- | --- |
| 0 | 16 | bytes | Input UUID in network byte order |
| 16 | remaining | bytes | Exact bytes accepted for terminal delivery |

The UUID is the deduplication key for the host lifetime. A duplicate input is
not recorded and its bytes are not written again.

### `PTY_RESIZE`

| Offset | Size | Type | Field |
| ---: | ---: | --- | --- |
| 0 | 4 | u32 | Columns, range 1 through 65535 |
| 4 | 4 | u32 | Rows, range 1 through 65535 |

The host appends this record before applying the terminal resize.

### `PROCESS_STARTED`

The payload is one u64 operating-system process ID. Command and working
directory are in metadata. Zero is invalid.

### `PROCESS_EXITED`

| Offset | Size | Type | Field |
| ---: | ---: | --- | --- |
| 0 | 4 | i32 | Exit code; `i32::MIN` when unavailable |
| 4 | 4 | i32 | Termination signal; `-1` when unavailable |

POSIX signals use their numeric value in the signal field. Windows reports
`-1` and preserves the native process exit code in the first field.

### `SIGNAL`

| Offset | Size | Type | Field |
| ---: | ---: | --- | --- |
| 0 | 2 | u16 | Kind |
| 2 | 2 | u16 | Flags, zero in v1 |
| 4 | 4 | i32 | Platform signal/control code, `-1` if none |

Kinds are `1` interrupt, `2` terminate, `3` kill, `4` hangup, `5` quit, and
`0xffff` platform-specific. Other values are invalid in schema v1.

## Control Framing

Control uses a reliable byte-stream transport: Unix domain sockets on Unix and
named pipes on Windows. Every request and response is one 32-byte header plus
its payload:

| Offset | Size | Type | Field |
| ---: | ---: | --- | --- |
| 0 | 4 | bytes | Magic `ORCT` |
| 4 | 2 | u16 | Control protocol version, `1` |
| 6 | 2 | u16 | Header length, `32` |
| 8 | 2 | u16 | Message type |
| 10 | 2 | u16 | Payload-schema version |
| 12 | 4 | u32 | Flags, zero in v1 |
| 16 | 8 | u64 | Client request ID copied into the response |
| 24 | 4 | u32 | Payload length |
| 28 | 4 | u32 | CRC-32C of payload bytes |

Request ID is a correlation value and is not a delivery deduplication key.
Clients may reuse it only when retrying the same request on a new connection.
The input UUID provides durable host-lifetime deduplication for `INPUT`.

The host closes the connection after bad magic, framing version, length, or
checksum. A semantic error receives `ERROR`; the connection may then continue.
Unknown request types receive `ERROR_UNSUPPORTED_MESSAGE`.

## Control Requests

| ID | Name | Payload schema v1 |
| ---: | --- | --- |
| `0x0001` | `INPUT` | Same bytes as `PTY_INPUT` payload |
| `0x0002` | `RESIZE` | Same bytes as `PTY_RESIZE` payload |
| `0x0003` | `SIGNAL` | Same bytes as `SIGNAL` payload |
| `0x0004` | `TERMINATE` | Mode, reserved, and grace milliseconds |
| `0x0005` | `STATUS` | Empty |
| `0x0006` | `APPEND_EVENT` | Producer event UUID and typed payload |

`TERMINATE` is 8 bytes: u16 mode (`0` graceful, `1` force), u16 reserved zero,
and u32 grace milliseconds. Grace is ignored for force mode.

`APPEND_EVENT` begins with a 16-byte producer event UUID, u16 journal event
type, u16 payload-schema version, u32 event flags, then the exact event payload.
Version 1 accepts only event types in `0x1000-0x1fff`. The host assigns the
journal timestamp. Producer UUID deduplication policy is defined with harness
ingress; it is not implied by control framing.

## Control Responses

| ID | Name | Payload schema v1 |
| ---: | --- | --- |
| `0x8000` | `ACCEPTED` | u64 assigned journal timestamp, or zero |
| `0x8001` | `DUPLICATE` | u64 timestamp of the original accepted input |
| `0x8002` | `ERROR` | u32 error code and UTF-8 detail |
| `0x8003` | `STATUS` | Fixed 64-byte status |

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
| 28 | 8 | u64 | Oldest timestamp, `u64::MAX` if no records |
| 36 | 8 | u64 | Latest timestamp, `u64::MAX` if no records |
| 44 | 4 | i32 | Exit code, `i32::MIN` if unavailable |
| 48 | 4 | i32 | Exit signal, `-1` if unavailable |
| 52 | 2 | u16 | Journal format version |
| 54 | 2 | u16 | Control protocol version |
| 56 | 8 | bytes | Reserved, zero |

## Metadata Version 1

`metadata` is UTF-8 JSON with `metadataVersion: 1`. Writers create a complete
temporary file in the session directory, apply the configured durability
policy, and atomically replace `metadata`. Readers ignore unknown object fields
but reject missing required fields, duplicate fields, invalid enum values, and
numbers outside the ranges below.

Required top-level fields are:

- `metadataVersion`, `journalFormatVersion`, and `controlProtocolVersion`: u16;
- `sessionId`: 1-128 safe ASCII characters as accepted by the CLI;
- `journalId`: canonical lower-case UUID string corresponding to segment bytes;
- `createdAtEpochMillis` and `sessionStartEpochMillis`: u64;
- `command`: non-empty array of UTF-8 strings and `cwd`: UTF-8 string;
- `hostPid`: nonzero u64 and `childPid`: nonzero u64 or null;
- `state`: `starting`, `running`, `exited`, or `failed`;
- `initialCols`, `initialRows`, `currentCols`, and `currentRows`: 1-65535;
- `term`: non-empty UTF-8 string of at most 128 bytes;
- `sandbox`, `control`, `activeSegment`, `oldestAvailableTimestamp`, and
  `latestTimestamp` as described below.

`sandbox` contains boolean `requested`, enum `enforcement` (`none`, `landlock`,
or `future`), enum `unavailablePolicy` (`fail` or `run-unsandboxed`), and arrays
`readWritePaths` and `readOnlyPaths` of UTF-8 paths. It describes effective
policy without secret contents.

`control` contains enum `transport` (`unix-domain-socket` or `named-pipe`) and a
UTF-8 `endpoint` relative to the session directory unless platform rules
require an absolute named-pipe name.

`activeSegment` is a u64 starting at 1. Oldest/latest timestamps are u64 or
null when no record exists. When present, both are nonzero and oldest is no
greater than latest. The checked-in `metadata-v1.json` is the canonical field
and formatting example; readers must not depend on field order or whitespace.

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
the process boundary; the implementation must reject a value it cannot encode
in required UTF-8 metadata rather than silently replacing bytes.

Duplicate and unknown options are errors. A requested sandbox defaults to
fail-closed when unavailable. `--help` and `--version` are standalone actions.

## Compatibility and Failure Rules

- Unsupported segment or record framing version: reject the segment.
- Unknown event type or known event with unknown payload schema: validate,
  expose as opaque, and continue.
- Unknown control request or payload schema: return a typed error.
- Bad known checksum, impossible count/timestamp order, or invalid length in a
  complete block: stop at that block and report corruption.
- Partial final record or uncompressed block: return preceding valid records and
  report an ignored crash tail.
- Partial final compressed block: ignore the entire block and retain all prior
  blocks.
- A cursor below metadata's oldest available timestamp produces an explicit
  retention gap before available records are returned.

Protocol v1 never resynchronizes by searching for magic after corruption.
