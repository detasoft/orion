# Git Pack Parsing Implementation

## Goal

Implement Orion-owned Git pack parsing that can read pack streams, validate
their structure, expose object entries and delta metadata, and feed later
storage or object reconstruction code without importing the pack through JGit.

This is the parser half of `Git Pack Parser and Builder`. The first milestone is
correct, streaming, well-tested parsing. Object reconstruction and pack building
can build on top of it, but should not be required for the raw parser to be
useful.

## Current State

Orion still relies on JGit `UploadPack`, `ReceivePack`, and repository object
APIs for pack protocol serving and object handling.

There is already a high-level plan for Git pack parsing and building. There is
also an S3 pack storage plan that expects a parser able to consume streams or
range readers.

The codebase has Git protocol transcript tests and JGit-backed repository tests,
but it does not yet have an Orion-owned pack parser, pack entry model, delta
parser, pack checksum validator, or parser error model.

There is partial S3-side exploratory code in `S3PackParser`. It subclasses JGit
`PackParser` and persists callback data such as raw stream chunks, object
headers, inflated object data, pack hash, and delta metadata through
`AbstractClient`. That code is useful as implementation context, but it should
not be treated as the target parser because JGit still owns the actual pack
format parsing, validation, delta handling, and callback ordering.

The surrounding S3 repository implementation is also incomplete:
`S3ObjectDatabase` can return the JGit-backed `S3PackParser`, but ordinary object
insertion, object reading, ref updates, and repository provider operations are
not implemented or are explicitly disabled. The parser plan therefore starts by
extracting pack-format logic into backend-independent code before any S3 storage
integration depends on it.

## Non-Goals

Do not replace JGit receive-pack or upload-pack in this phase.

Do not write parsed objects to repository storage in the first parser step.

Do not resolve deltas in the raw parser. Delta resolution can be a separate
component that consumes parsed delta entries.

Do not require byte-for-byte pack building to exist before the parser is useful.

Do not load full packs into memory except in small test helpers.

## Parser Model

Introduce parser-facing value objects:

- `GitPackHeader`: magic, version, object count;
- `GitPackObjectType`: commit, tree, blob, tag, ofs-delta, ref-delta;
- `GitPackEntryHeader`: object type, inflated size, stream offset;
- `GitPackWholeObjectEntry`: whole object type plus inflated bytes or stream
  handle;
- `GitPackOfsDeltaEntry`: object size, negative base offset, base stream
  offset, delta bytes or stream handle;
- `GitPackRefDeltaEntry`: object size, base object id, delta bytes or stream
  handle;
- `GitPackTrailer`: trailing SHA-1 pack checksum;
- `GitPackParseError`: offset, invariant name, and cause.

The raw parser should expose the exact pack stream offsets needed for later
pack indexes, range reads, and delta base lookup.

## Streaming Boundary

The parser should accept a streaming input abstraction rather than only
`InputStream`:

- current byte offset;
- sequential read;
- bounded read;
- digest update;
- optional range reader later.

An `InputStream` adapter is enough for the first implementation. The API should
not prevent a later S3 range-backed reader from using the same parser.

Parser output should support event-style consumption:

```text
onHeader(header)
onWholeObject(entry)
onOfsDelta(entry)
onRefDelta(entry)
onTrailer(trailer)
```

Small tests can collect events into a list. Production storage can stream
entries without retaining the full pack.

## Phased Plan

Phase 1: Pack fixtures.

Add deterministic JGit-generated fixture helpers for:

- empty pack;
- one blob;
- commit plus tree plus blob;
- tag object;
- invalid magic;
- unsupported version;
- truncated body;
- checksum mismatch.

Fixtures should be generated from explicit object inputs and fixed metadata, not
from ambient Git config.

Phase 2: Header and trailer parser.

Parse `PACK`, version, object count, and the trailing SHA-1 checksum. Validate
that the computed digest over all bytes before the trailer equals the trailer.
Report offsets in all failures.

Phase 3: Entry header parser.

Implement object entry type and variable-length size decoding. Cover boundary
sizes: 0, small values, values crossing continuation bytes, and large values.

Phase 4: Deflated payload reader.

Inflate each object body while tracking compressed stream consumption and
inflated size. Reject bodies whose inflated size does not match the entry
header. Keep zlib handling isolated so compression behavior is easy to test.

Phase 5: Delta entry parsing.

Parse `OFS_DELTA` base offset encoding and `REF_DELTA` base object id. Store raw
delta payload bytes or stream handles without applying the delta yet.

Phase 6: Git delta instruction parser.

Add a separate parser for delta instructions:

- source size varint;
- result size varint;
- copy instructions;
- insert instructions;
- reserved opcode rejection;
- result-size validation.

This parser still does not need repository storage; it should apply deltas only
against caller-provided base bytes in tests.

Phase 7: Canonical comparison with JGit.

Parse the same fixture with Orion and JGit, then compare object type, object
size, object id for whole objects, delta base reference, and checksum validity.

## Error Handling

All parser errors should include:

- byte offset;
- pack section: header, entry header, entry body, delta body, trailer;
- violated invariant;
- expected value if known;
- actual value if known.

Invalid packs must fail before any storage layer is asked to persist parsed
objects.

## Open Questions

Should whole object bodies be exposed as byte arrays, temporary files, or
bounded streams in the first implementation?

Should delta instruction parsing live in the same module as raw pack parsing or
in a separate object reconstruction module?

Should parser support pack version 3 if encountered, or reject everything except
version 2 until there is a specific need?

How should large-object tests avoid excessive memory while still validating
streaming behavior?

Should the parser compute object ids for whole objects immediately, or leave
that to the reconstruction layer?

## Verification

Cover at least these cases:

- empty valid pack parses header and trailer;
- whole blob, tree, commit, and tag objects parse from JGit fixtures;
- entry header size decoding handles continuation bytes;
- invalid magic, unsupported version, truncated header, truncated body, and
  checksum mismatch fail with offsets;
- inflated body size mismatch is rejected;
- `OFS_DELTA` and `REF_DELTA` entries parse base references correctly;
- delta instruction parser applies copy and insert instructions to a provided
  base object;
- reserved delta opcode is rejected;
- parser can stream through a pack without retaining all entries;
- canonical parsed output matches JGit for deterministic fixtures.
