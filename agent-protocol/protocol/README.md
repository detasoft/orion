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

HTTP/2 stream IDs are transport details. `AgentId`, `AgentInstanceId`,
`SessionId`, `EventId`, and `CommandId` are the stable logical identities used
after reconnects and server restarts.

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
| `0x0001` | agent to server | `HELLO` | versions, AgentId, instance, agent version, machine, capabilities, optional authentication tail |
| `0x0002` | agent to server | `HEARTBEAT` | AgentId, AgentInstanceId, epoch milliseconds |
| `0x0003` | agent to server | `AGENT_STATUS` | IDs, version, machine, session count, metrics, capabilities |
| `0x0004` | agent to server | `SESSION_STATUS` | session descriptor |
| `0x0005` | agent to server | `COMMAND_RESULT` | CommandId, optional SessionId, outcome, detail |
| `0x0006` | agent to server | `SESSION_LIST` | array of session descriptors |
| `0x0010` | agent to server | `SESSION_OPEN` | SessionId, optional first/last EventId, state |
| `0x8001` | server to agent | `WELCOME` | protocol version, journal version, ConnectionId, configuration, optional reconnect token |
| `0x8002` | server to agent | `REQUEST_SESSION_LIST` | none |
| `0x8100` | server to agent | `START_SESSION` | IDs, workspace, argv, cwd, env, PTY size, sandbox, runtime |
| `0x8101` | server to agent | `INPUT` | CommandId, SessionId, input UUID, bytes |
| `0x8102` | server to agent | `RESIZE` | CommandId, SessionId, columns, rows |
| `0x8103` | server to agent | `SIGNAL` | CommandId, SessionId, signal kind, signed platform code |
| `0x8104` | server to agent | `TERMINATE` | CommandId, SessionId, mode, grace milliseconds |
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

`HELLO` and `WELCOME` negotiate the Agent protocol and session journal format
independently. Version 1 uses protocol version `1` and journal version `1`.
The frozen eight-field `HELLO` prefix may append `[generation, launchId,
credentialKind, credentialBytes]`, where generation is positive, launch ID is
a UUID, kind `1` is a launch permit, kind `2` is a reconnect token, and the
credential contains 32 through 512 bytes. A partial authentication tail is
invalid. The generic codec retains legacy readability, but the server control
endpoint must reject an unauthenticated `HELLO`. The frozen five-field
`WELCOME` prefix may append a 32-through-512-byte reconnect token.
`SESSION_OPEN` starts each logical replication stream. The server answers with
`SESSION_SYNC`; a null cursor requests the first available event, otherwise
AgentD sends records whose EventId is greater than the committed cursor.

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
| `0x0101` | `PTY_INPUT` | `[CommandId, byte string]` |
| `0x0102` | `PTY_RESIZE` | `[columns, rows]` |
| `0x0201` | `PROCESS_EXITED` | `[signed exit code]` |

The transport decoder extracts EventId and event type but retains the encoded
payload and complete encoded record. It therefore forwards unknown event types,
payload encodings, and optional record tails byte-for-byte.

## Compatibility Fixtures

- `fixtures/agent-hello-v1.hex` freezes control message array positions, UUID
  byte order, machine layout, map encoding, and both negotiated versions.
- `fixtures/session-events-v1.hex` is one CBOR Sequence containing all required
  event payloads.
- `fixtures/session-event-unknown-tail-v1.hex` freezes preservation of an
  unknown event payload and an optional future record field.

Whitespace in fixture files is not part of the encoding.
