# Protocol V2 Upload-Pack Command Parsing Design

## Goal

Advertise the supported protocol v2 upload-pack capabilities and parse
streaming `ls-refs` and `fetch` requests into typed values before handing them
to explicit continuation placeholders.

## Scope

This slice includes:

- the protocol v2 capability advertisement;
- streaming pkt-line parsing for the command, capability, delimiter, argument,
  and flush portions of an upload-pack request;
- typed request values for `ls-refs` and `fetch`;
- separate `Continuation` placeholders for the two parsed commands.

Generating `ls-refs` and `fetch` responses is deliberately deferred.

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
`command=fetch` line, accepts optional capability lines before a delimiter,
then collects ordered command arguments after the delimiter until flush.

The completed parser creates a typed exchange value containing the original
`InitialRequestData`, command capabilities, and arguments. It transitions to
either `LsRefsContinuation` or `FetchContinuation`. Those continuations retain
the parsed value but terminate with an explicit not-implemented error until
response generation is added.

## Validation and Errors

The parser rejects:

- missing, duplicate, empty, or unknown commands;
- arguments before the delimiter;
- capability lines after the delimiter;
- unsupported delimiter placement and control packets;
- malformed or non-ASCII request payloads.

Protocol grammar failures use `INVALID_PROTOCOL_V2_REQUEST`. Output failures
preserve the error returned by `GitNativeClientOutput`.

## Testing

Production continuation logic is written before its tests, as required for
`Continuation` implementations in this repository. Tests then cover:

- exact advertisement bytes and the completed/streaming output paths;
- fragmented pkt-line headers and payloads;
- valid `ls-refs` and `fetch` requests;
- preserved capability and argument ordering;
- unknown commands and malformed section ordering;
- transitions to the distinct command placeholders.

