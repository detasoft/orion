# Agent Protocol Version 1

AgentD and the central server exchange binary messages over logical HTTP/2
streams. HTTP/2 stream IDs are transport details and never identify an agent,
session, command, or journal cursor.

## Frame

Every message is exactly one 16-byte header followed by one payload. Integers
use unsigned network byte order unless a field is explicitly signed.

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 4 | Magic `OAGP` |
| 4 | 2 | Framing version, `1` |
| 6 | 2 | Agent protocol version, `1` |
| 8 | 2 | Message type |
| 10 | 2 | Flags, zero in v1 |
| 12 | 4 | Payload length |

The hard frame limit is 16 MiB. A deployment may configure a smaller limit.
Readers reject the length before allocating it. Framing and Agent protocol
versions are independent from the session journal format version.

## Fields

Known message payloads are concatenated TLV fields:

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 2 | Nonzero field tag |
| 2 | 4 | Value length |
| 6 | length | Value bytes |

Unknown field tags are skipped. A singular known tag must occur exactly once
unless documented as optional; repeated known tags represent lists. Nested
objects and map entries contain the same TLV field format. Map entries use tag
`1` for the UTF-8 key and tag `2` for the UTF-8 value. Writers sort map keys so
fixtures and signatures over complete frames remain stable.

Strings are strict UTF-8. UUID values are 16 bytes in network byte order. A
u64 value above Java's signed positive range is reserved until a later
implementation defines a wider representation.

## Message Types

| ID | Direction | Message | Fields |
| ---: | --- | --- | --- |
| `0x0001` | agent to server | `HELLO` | version `1`, AgentId `2`, InstanceId `3`, agent version `4`, machine `5`, capabilities `6*` |
| `0x0002` | agent to server | `HEARTBEAT` | AgentId `1`, InstanceId `2`, epoch millis `3` |
| `0x0003` | agent to server | `AGENT_STATUS` | AgentId `1`, InstanceId `2`, version `3`, machine `4`, active sessions `5`, metrics `6*`, capabilities `7*` |
| `0x0004` | agent to server | `SESSION_STATUS` | SessionId `1`, state `2`, detail `3` |
| `0x0005` | agent to server | `COMMAND_RESULT` | CommandId `1`, optional SessionId `2`, outcome `3`, detail `4` |
| `0x0010` | agent to server | `SESSION_EVENTS` | SessionId `1`, event `2*` |
| `0x0011` | agent to server | `SESSION_GAP` | SessionId `1`, requested cursor `2`, available-from timestamp `3` |
| `0x8001` | server to agent | `WELCOME` | version `1`, ConnectionId `2`, configuration `3*` |
| `0x8100` | server to agent | `START_SESSION` | CommandId `1`, SessionId `2`, optional WorkspaceId `3`, argv `4*`, cwd `5`, environment `6*`, columns `7`, rows `8`, sandbox `9`, runtime `10` |
| `0x8101` | server to agent | `INPUT` | CommandId `1`, SessionId `2`, input UUID `3`, bytes `4` |
| `0x8102` | server to agent | `RESIZE` | CommandId `1`, SessionId `2`, columns `3`, rows `4` |
| `0x8103` | server to agent | `SIGNAL` | CommandId `1`, SessionId `2`, kind `3`, signed platform code `4` |
| `0x8104` | server to agent | `TERMINATE` | CommandId `1`, SessionId `2`, mode `3`, grace millis `4` |
| `0x8110` | server to agent | `SESSION_ACK` | SessionId `1`, through cursor `2` |
| `0x8111` | server to agent | `RESUME_SESSION` | SessionId `1`, after cursor `2` |

Machine info uses hostname `1`, OS `2`, and architecture `3`. A session event
uses source timestamp `1`, event type `2`, payload-schema version `3`, u32 flags
`4`, and opaque bytes `5`. AgentD preserves the event type, schema, flags, and
payload without interpreting unknown journal event types.

Session states are starting `1`, running `2`, exited `3`, degraded `4`, journal
gap `5`, lost `6`, and failed `7`. Command outcomes are succeeded `1`, failed
`2`, rejected `3`, and duplicate `4`. Signal kinds match the session-host
portable signal allocation: interrupt `1`, terminate `2`, kill `3`, hangup `4`,
quit `5`, and platform-specific `0xffff`. Termination modes are graceful `0`
and force `1`.

Unknown message type IDs are surfaced with their complete opaque payload.
Known message types with invalid required fields, duplicates, invalid UTF-8,
or out-of-range values are protocol errors.

## Compatibility Fixtures

`fixtures/session-ack-v1.hex` freezes cursor acknowledgement framing.
`fixtures/session-events-opaque-v1.hex` freezes an event with an unknown type,
nontrivial flags, and non-UTF-8 payload bytes. Whitespace in fixture files is
not part of the frame.
