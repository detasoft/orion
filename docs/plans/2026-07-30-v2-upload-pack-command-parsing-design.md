# Protocol V2 Upload-Pack Command Parsing Design

## Goal

Advertise the supported protocol v2 upload-pack capabilities and dispatch
streaming `ls-refs` and `fetch` commands to explicit continuation
placeholders.

## Scope

This slice includes:

- the protocol v2 capability advertisement;
- byte-level parsing of the initial command pkt-line and delimiter;
- separate `Continuation` placeholders for the two parsed commands.

Parsing command-specific capabilities and arguments and generating responses
are deliberately deferred to the command continuations.

## Architecture

`v2.UploadPackContinuation` sends this fixed advertisement through
`GitNativeClientOutput`:

```text
version 2
ls-refs
fetch=shallow
server-option
flush
```

The output operation follows the existing completed, streaming, and failed
contract. After completed output the continuation transitions immediately;
after streaming output it transitions and yields the output task.

Parsing remains a flat continuation graph. Dedicated continuations consume
pkt-line headers and fragmented payloads without concatenating the complete
request. The graph first accepts exactly one `command=ls-refs` or
`command=fetch` line and dispatches on the delimiter.

The payload continuation follows the `InitialRequestParser` pattern: a nested
parser consumes one byte at a time, tracks only fixed command candidates, and
never materializes the pkt-line as a `String`, `StringBuilder`, or raw payload
array. The dispatcher transitions to either `LsRefsContinuation` or
`FetchContinuation` without consuming any command-specific input after the
delimiter.

## Validation and Errors

The parser rejects:

- missing, duplicate, empty, or unknown commands;
- data packets between the command and delimiter until capability parsing is
  connected;
- flush or response-end before dispatch;
- malformed or non-ASCII request payloads.

Protocol grammar failures use `INVALID_PROTOCOL_V2_REQUEST`. Output failures
preserve the error returned by `GitNativeClientOutput`.

## Testing

Production continuation logic is written before its tests, as required for
`Continuation` implementations in this repository. Tests then cover:

- exact advertisement bytes and the completed/streaming output paths;
- fragmented pkt-line headers and payloads;
- valid `ls-refs` and `fetch` command dispatch;
- command-specific bytes remaining unread after the delimiter;
- unknown commands and invalid control packets;
- transitions to the distinct command placeholders.
