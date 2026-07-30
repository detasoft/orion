# Protocol V2 `ls-refs` Design

## Goal

Replace the protocol v2 `ls-refs` placeholder with a fragment-safe command
continuation that reads request arguments, resolves native repository refs, and
writes a backpressured pkt-line response before accepting the next command.

## Scope

This slice includes:

- parsing `peel`, `symrefs`, `unborn`, and repeated `ref-prefix` arguments;
- advertising `ls-refs=unborn`;
- deterministic ref-prefix filtering without duplicate rows;
- direct refs, symbolic `HEAD`, peeled annotated tags when available, and
  unborn `HEAD`;
- completed, streaming, and failed output transitions;
- returning to protocol v2 command parsing after the response flush.

Protocol v2 `fetch` remains outside this slice.

## Architecture

Use three explicit layers:

1. `LsRefsContinuation` and a payload continuation parse pkt-line framing and
   command arguments into a typed request.
2. `GitNativeRepositoryService` resolves the requested repository and builds a
   typed, deterministically ordered response.
3. `GitNativeClientOutput` serializes response rows and the terminating flush
   through its existing completed, streaming, and failed output contract.

The continuation graph stays flat. `LsRefsContinuation` reads one control
header at a time, delegates DATA payloads to a bounded ASCII parser, completes
the request on FLUSH, and rejects DELIMITER or RESPONSE_END. After output
completion it transitions to a new `UploadCommandContinuation`. Streaming
output transitions to the same continuation and yields the output task.

## Request Parsing

The request parser accepts:

- `peel`;
- `symrefs`;
- `unborn`;
- `ref-prefix <prefix>`, repeated as needed.

Arguments are parsed incrementally across arbitrary `ByteBuf` fragments. Each
DATA packet is bounded by the pkt-line maximum. The parser retains only the
typed flags and requested prefix strings, not raw request packets.

Unknown well-formed arguments are ignored as required by protocol v2
extensibility. Empty payloads, non-ASCII payloads, malformed known arguments,
and invalid control packets produce `INVALID_PROTOCOL_V2_REQUEST`.

If no `ref-prefix` is supplied, all visible refs match. Multiple prefixes are
an inclusive union; each ref appears at most once.

## Repository Response

The repository service snapshots refs and sorts matching names
lexicographically. `HEAD` is included only when it matches the requested
prefixes:

- if its symbolic target resolves, the row uses the target object id and adds
  `symref-target:<target>` only when `symrefs` was requested;
- if its target does not resolve, the service emits
  `unborn HEAD symref-target:<target>` only when `unborn` was requested.

Direct branches and tags use their current object ids. When `peel` was
requested and the native object model can resolve an annotated tag target, the
tag row adds `peeled:<object-id>`. Lightweight tags have no peeled attribute.

Internal refs are not introduced by this slice.

## Output

The output serializer emits one pkt-line per response row followed by `0000`.
It validates ASCII and pkt-line limits and reports expected serialization or
delivery failures through `GitNativeClientOutput.SendResult`, never through
expected exception control flow.

The serializer preserves row order and supports a full output buffer through
the existing `Streaming` task contract.

## Testing

Production continuation logic is written before continuation tests, as required
by the repository rules. Tests cover:

- ordinary branch and tag rows with deterministic ordering;
- overlapping ref prefixes without duplicate rows;
- resolved `HEAD` with optional `symref-target`;
- empty repository with requested unborn `HEAD`;
- fragmented headers and payloads;
- ignored unknown arguments and rejected malformed known arguments;
- empty filtered responses;
- completed and streaming output transitions;
- output failure propagation;
- transition back to the next protocol v2 command.

Focused verification uses the `dev` Maven profile for `core/git-parser`, then
routine development verification uses `mvn verify -Pdev -T 4`.
