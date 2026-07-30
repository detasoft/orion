# Legacy Receive-Pack Advertisement and Commands Design

## Goal

Implement the protocol v0/v1 receive-pack discovery and command-list portion of
the continuation graph. A native Git client must receive the repository's
receive-pack advertisement and be able to send a fragmented command list
without requiring a whole-buffer parser.

Pack ingestion, ref updates, and report-status output remain later slices. This
slice must leave the unread raw pack bytes available to the continuation that
will own pack ingestion.

## Protocol Flow

`ReceivePackContinuation` prepares and sends a receive-pack-specific legacy
advertisement. The advertisement contains the current refs in deterministic
order. When the repository is empty, it contains the zero object ID under
`capabilities^{}`. The first advertised line carries only receive-pack
capabilities implemented or required by the planned native path:

- `report-status`;
- `side-band-64k`;
- `ofs-delta`;
- `object-format=sha1`;
- `agent=orion-native`.

After the output operation completes, the graph reads pkt-line command packets
until `flush-pkt`. Each command has:

```text
old-id SP new-id SP ref-name
```

Only the first command may append `NUL capability-list`. A trailing LF is
accepted but not required. Packet payloads and headers may be split at any byte
boundary.

## Models and Validation

Add immutable exchange values for one command and one complete command section.
A command records the old object ID, new object ID, and ref name. Its type is
derived from zero-ID semantics: create, update, or delete. The command section
also records the initial service request, the client capabilities in insertion
order, and the server advertisement.

Reject:

- an empty command packet;
- malformed or non-ASCII command text;
- object IDs that are not exactly 40 hexadecimal digits;
- a command where both IDs are zero;
- missing, blank, or whitespace-containing ref names;
- duplicate ref names;
- capabilities on any command after the first;
- empty capability tokens;
- delimiter and response-end control packets;
- a flush before any command.

Parsing errors transition to a typed terminal error and never escape from
`process`.

## Continuation Boundary

The graph is:

```text
ReceivePackContinuation
  -> send advertisement
  -> ReceiveCommandContinuation
  -> ReceiveCommandPayloadContinuation
  -> ReceivePackBoundaryContinuation
```

`ReceivePackBoundaryContinuation` holds the complete typed command section and
does not consume input. It completes successfully as the explicit handoff point
for the future raw pack ingestor. Therefore, when a network chunk contains both
the final command flush and the beginning of `PACK`, the pack bytes remain
unread.

The boundary also records whether the command set requires a pack. Create and
update require a pack according to Git's receive-pack protocol; an all-delete
command set does not. A later slice will replace boundary completion with the
appropriate pack-ingestion or direct-command-processing continuation.

## Testing

Production continuation logic is implemented before tests, as required for
Orion `Continuation` classes. Tests then cover:

- populated and empty repository advertisements;
- fragmented command headers and payloads;
- create, update, and delete parsing;
- first-command capabilities;
- multiple commands in one chunk;
- raw `PACK` bytes remaining unread after the command flush;
- duplicate refs, malformed IDs, late capabilities, unsupported controls, and
  an empty command section;
- output serialization failure through the standard continuation flow.

