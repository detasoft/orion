# Agent Protocol Version 1

AgentD and the central agent session server exchange CBOR Sequences over
logical HTTP/2 streams. A DATA frame may split a CBOR item at any byte, and one
DATA frame may contain several items. There is no frame header or length prefix
outside CBOR.

DATA frame boundaries have no protocol meaning. A structurally complete item
that fails semantic decoding is skipped with a diagnostic, and decoding
continues at the next item boundary. If an item boundary cannot be established,
the structural failure is terminal for that control or session response stream;
any valid prefix is still delivered first. Receivers do not scan for a
plausible later item or rely on an outer length marker to resynchronize.

HTTP/2 stream IDs are transport details. `AgentLabel`, `AgentInstanceId`,
`SessionId`, `EventId`, and `CommandId` identify the logical participants and data.
An instance ID survives transport reconnects and changes on every AgentD process launch.

## Encoding Rules

Every control item is a definite-length CBOR array. Position `0` is an unsigned
message type. Existing positions never change meaning; later versions may only
append positions. Version 1 readers require all documented positions and ignore
an unknown tail.

Text is strict UTF-8. UUIDs are 16-byte strings in network byte order. Event IDs
are full unsigned 64-bit integers. Maps have text keys and values; the canonical
writer orders keys by their Java string order. Decoders accept either definite
or indefinite CBOR containers, enforce configured byte, collection, string,
binary, and nesting limits, and reject duplicate map keys.

Unknown control message IDs are returned with the original encoded CBOR item.
Unsupported Agent protocol or journal format versions fail negotiation without
changing any local session. That negotiation policy is applied after sequence
decoding and is distinct from semantic recovery within the sequence decoder.

## Control Messages

| ID | Direction | Message | Array positions after type |
| ---: | --- | --- | --- |
| `0x0001` | agent to server | `HELLO` | versions, AgentLabel, instance, agent version, machine, capabilities, optional authentication tail |
| `0x0002` | agent to server | `HEARTBEAT` | AgentLabel, AgentInstanceId, epoch milliseconds |
| `0x0003` | agent to server | `AGENT_STATUS` | IDs, version, machine, session count, metrics, capabilities |
| `0x0004` | agent to server | `SESSION_STATUS` | session descriptor |
| `0x0005` | agent to server | `COMMAND_RESULT` | CommandId, optional SessionId, outcome, detail |
| `0x0006` | agent to server | `SESSION_LIST` | array of session descriptors |
| `0x0010` | agent to server | `SESSION_OPEN` | SessionId, optional first/last EventId, state |
| `0x8001` | server to agent | `WELCOME` | protocol version, journal version, ConnectionId, configuration, optional reconnect token |
| `0x8002` | server to agent | `REQUEST_SESSION_LIST` | none |
| `0x8100` | server to agent | `START_SESSION` | IDs, workspace, argv, cwd, env, PTY size, sandbox, runtime |
| `0x8101` | server to agent | `INPUT` | CommandId, SessionId, input UUID, bytes, operationSequence |
| `0x8102` | server to agent | `RESIZE` | CommandId, SessionId, columns, rows, operationSequence |
| `0x8103` | server to agent | `SIGNAL` | CommandId, SessionId, signal kind, signed platform code, operationSequence |
| `0x8104` | server to agent | `TERMINATE` | CommandId, SessionId, mode, operationSequence |
| `0x8110` | server to agent | `SESSION_SYNC` | SessionId, optional committed EventId |

Machine is `[hostname, operatingSystem, architecture]`. A session descriptor is
`[sessionId, state, firstAvailableEventId|null, lastAvailableEventId|null,
detail]`. The first and last event IDs are either both null for an empty journal
or both present in unsigned order.

Session states are starting `1`, running `2`, exited `3`, degraded `4`, journal
gap `5`, lost `6`, and failed `7`. Command outcomes are succeeded `1`, failed
`2`, rejected `3`, and duplicate `4`. Signal kinds are interrupt `1`, terminate
`2`, kill `3`, hangup `4`, quit `5`, and platform-specific `0xffff`.
Termination modes are graceful `0` and force `1`.
Established-session commands carry the server-assigned per-session
`operationSequence` as an unsigned 64-bit value from 1 through `u64::MAX - 1`.
`START_SESSION` has no operation sequence.
An agent-to-server `COMMAND_RESULT` can report a transient delivery failure;
only a durably replicated session-journal `COMMAND_RESULT` establishes the
command's execution outcome. An empty native `RECEIVED` is admission evidence,
not completion.

`HELLO` and `WELCOME` negotiate the Agent protocol and session journal format
independently. Version 1 uses protocol version `1` and journal version `1`.
The frozen eight-field `HELLO` prefix may append `[generation, launchId,
credentialKind, credentialBytes]`, where generation is positive, launch ID is
a UUID, kind `1` is a launch permit, kind `2` is a reconnect token, and the
credential contains 32 through 512 bytes. A partial authentication tail is
invalid. The codec can represent an unauthenticated message, but the server control
endpoint rejects an unauthenticated `HELLO`. The frozen five-field
`WELCOME` prefix may append a 32-through-512-byte reconnect token.
`SESSION_OPEN` starts each logical replication stream. The server answers with
`SESSION_SYNC`; a null cursor requests the first available event, otherwise
the agent resumes after the returned committed event ID.

The endpoint is `POST /agent/session/{sessionId}` over HTTP/2 on the main Orion
HTTPS listener. The same physical connection must already have completed the
`/agent/control` handshake; supplying a label or sharing an address is not
sufficient. An additional control stream on that connection receives HTTP 409,
and closing the control stream closes the physical connection and its dependent
replication streams. Before a completed handshake, replication receives HTTP 401.
Ordinary client event and command routes remain on the same HTTPS listener. The request path
ID must match the `SESSION_OPEN` payload, and the response cursor comes only
from durable server storage. The remaining request body carries the journal's
original CBOR Sequence records. Multiple disposable physical streams may
overlap for one session. Each stream retains its authenticated connection context.
Every queued open and append verifies the current label/instance registration and
session ownership while holding an operation lease. Registration replacement waits
for already-authorized operations; later work from old streams is rejected.
Independent session streams may append concurrently.

## Label registration and replacement

`agentLabel` is one unique logical agent name within a server. Labels contain 1–128
ASCII characters matching `[A-Za-z0-9][A-Za-z0-9._:-]{0,127}`, with exact case-sensitive
equality and no normalization. Labels are stable across process restarts and own
the server's sessions. A label alone grants no authority.

The trusted server/provisioner path allocates a launch against either no current
instance (initial registration) or an exact expected current instance (authorized
restart). Occupied-label initial issuance and stale expected instances are rejected.
The latest allocated generation and launch ID fence all older pending permits;
allocating or issuing a new permit leaves the current registration authoritative.
The provisioner delivers the bounded, single-use permit through standard input;
`--agent-label`, generation, and launch ID are non-secret launch arguments.

AgentD generates a fresh instance UUID per process and sends it with the label and
permit in `HELLO`. Under one durable ownership transition, the server consumes the
permit, binds this instance, and replaces the preceding instance's reconnect
credential. The server persists only credential digests and expiry timestamps.
A reconnect token authenticates only the exact registered label, instance,
generation, and launch ID. A fresh process cannot reuse it. Heartbeat renewal
retains the digest and extends expiry from server time.

A lost first `WELCOME` does not make the consumed permit reusable. Recovery obtains
a new authorized launch against the now-current registration. A reconnect response
can be retried by the same process with its existing reconnect credential. Connection
IDs identify disposable transports; delayed control callbacks, delivery completions,
and stream appends from a replaced instance cannot mutate the replacement's state.

Agent records, session ownership records, and command-ledger records use server
storage format version 2. Previous identity formats are unsupported and fail
explicitly before their files are rewritten or removed. There is no conversion or
legacy authentication path. An ambiguous publication fences the registry until
reopen; durable recovery cannot revive consumed permits or superseded credentials.

## Session Journal Records

The bytes after `SESSION_OPEN` are the journal's original CBOR Sequence. Each
record is:

```text
[eventId, eventType, payload, ...optionalFutureFields]
```

The shared version 1 allocation is:

| ID | Event | Payload |
| ---: | --- | --- |
| `0x0100` | `PTY_OUTPUT` | byte string |
| `0x0101` | `PTY_INPUT` | `[ptyInputId, byte string]` |
| `0x0102` | `PTY_RESIZE` | `[columns, rows]` |
| `0x0103` | `PTY_CLOSED` | `[]` |
| `0x0200` | `PROCESS_STARTED` | `[processId]` |
| `0x0201` | `PROCESS_EXITED` | `[signed exit code]` |
| `0x0203` | `SESSION_START_FAILED` | `[CommandId, diagnostic, omittedByteCount]` |

The transport decoder extracts EventId and event type but retains the encoded
payload and complete encoded record. It therefore forwards unknown event types,
payload encodings, and optional record tails byte-for-byte.

`PTY_CLOSED` has a typed Java projection and ends terminal availability only.
Readers continue through subsequent command results and process events;
closure does not mean that the host or its owned processes have exited. Native
ordering and append-failure semantics are specified in the
[session-host protocol](../../session-host/protocol/README.md).

Once a native journal exists, the host attempts one durable start outcome
before leaving its start phase: `PROCESS_STARTED` after exec or
`SESSION_START_FAILED` for an earlier failure. A failed append can leave no
durable outcome. If recording `PROCESS_STARTED` fails, the host logs the error
and publishes the live session; it does not record `SESSION_START_FAILED`
after exec. Missing durable history does not prove that no process was launched.
Failures before journal creation have no native outcome record. The
[session-host protocol](../../session-host/protocol/README.md) defines these
start-outcome and append-failure semantics.

`SESSION_START_FAILED` diagnostics are strict UTF-8 capped at 1 MiB;
truncation retains at most the first 64 KiB and last
960 KiB and reports the removed byte count separately. Typed Java decoding of
the lifecycle records may be added independently because the transport already
preserves their exact encoded bytes.

## Compatibility Fixtures

- `fixtures/agent-hello-v1.hex` freezes control message array positions, UUID
  byte order, machine layout, map encoding, and both negotiated versions.
- `fixtures/session-events-v1.hex` is one CBOR Sequence containing all required
  event payloads.
- `fixtures/session-event-unknown-tail-v1.hex` freezes preservation of an
  unknown event payload and an optional future record field.
- `fixtures/start-outcomes-v1.hex` freezes native success and failure start
  observations and is byte-identical to the session-host fixture.
- `fixtures/pty-closure-v1.hex` freezes terminal output, empty terminal closure,
  and process exit, and is byte-identical to the session-host fixture.

Whitespace in fixture files is not part of the encoding.
