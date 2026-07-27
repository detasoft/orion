# Native Receive-Pack Vertical Slice Design

## Goal

Build the first native receive-pack push path without using JGit production
classes and without changing `git-engine` in this phase. The slice should prove
that Orion-owned code can parse a push request, ingest a small pack into
quarantine, publish objects, update refs with compare-and-set semantics, and
write a Git-compatible report-status response.

This design narrows the broader receive-pack plan to the smallest useful local
backend slice. `git-engine` integration and transport routing remain follow-up
work after the native storage and receive-pack behavior are covered by tests.

## Module Boundaries

`core/git-parser` owns wire-format parsing and writing:

- receive-pack command line parsing;
- receive-pack capability enum and selection resolver;
- receive-pack advertisement writing;
- reuse of existing pkt-line, capability, side-band, and report-status helpers.

`core/git-native-storage` is a new module for native Git storage and serving
primitives:

- loose object storage;
- quarantine object staging;
- pack ingestion orchestration;
- loose ref reads and compare-and-set updates;
- a native receive-pack service that depends on `git-parser` and `git-common`.

`core/git-storage` stays JGit-backed. Do not add native storage classes there.

`core/git-engine` is not changed in this phase. It will later select a native
repository implementation or provider once the native storage tests exist.

## Receive-Pack Scope

Advertise only implemented capabilities:

- `report-status`;
- `side-band-64k`;
- `object-format=sha1`;
- `agent=<orion-native version>`.

Do not advertise `atomic`, `delete-refs`, `ofs-delta`, `push-options`, or
`quiet` until they are implemented.

Model receive-pack capabilities with an enum instead of scattering string
literals through parsers and service code. Each enum entry defines:

- wire name;
- whether it may carry a value;
- whether it is advertised in this slice;
- whether a client request requires explicit server support;
- how to render the advertised value, for example `agent` and `object-format`.

Client capability resolution should use one shared resolver. The resolver takes
the server-supported enum set and the client-requested `GitCapabilitySet`, then
returns a typed result containing selected capabilities, ignored capabilities
that Git permits, and unsupported capabilities that must reject the request
before pack ingestion. Advertisement writing and command validation must both use
this resolver so capability behavior stays consistent.

Support branch create and branch update for `refs/heads/*`. Reject duplicate
refs, malformed object ids, invalid ref names, deletes, tag updates, force
updates, unsupported capabilities, and command lists over the configured limit.

The first Git CLI push compatibility test should push a simple repository with
delta generation disabled. The production code still rejects delta entries
because `ofs-delta` and `ref-delta` ingestion are outside this slice.

## Data Flow

The native service writes the receive-pack advertisement from the current ref
snapshot, then parses one command section from pkt-lines. The first command line
may carry NUL-separated client capabilities. Capability validation happens
before pack ingestion.

If command validation passes, the service reads the following pack stream into a
quarantine area with a byte limit. The pack ingestor validates `PACK` magic,
version 2, declared object count, deflated whole-object entries, SHA-1 object ids,
and the pack trailer checksum. Parsed objects are written as loose objects in
quarantine.

After ingestion, the service verifies that every non-delete command's `new-id`
exists in quarantine or already published storage. Only then does it publish
quarantined objects to the visible loose object store and apply ref updates.

Ref updates use expected-old compare-and-set semantics:

- create requires zero old id and absent current ref;
- update requires the advertised/current old id to match the command old id;
- non-fast-forward updates are rejected until commit graph traversal exists;
- no-op handling is explicit and returns a typed result.

The service writes `unpack ok` plus per-ref `ok` or `ng` entries when
`report-status` is selected. When `side-band-64k` is selected, report-status is
wrapped on band 1. Pack-level failures write `unpack <sanitized reason>` and do
not update refs.

## Safety

No ref may point at an object before the object is durable in visible storage.
Any failure during parse, quarantine write, object validation, publication, or
CAS ref update leaves refs unchanged. Successfully published but unreachable
objects may remain for later garbage collection if a ref update fails after
publication.

Errors returned to clients must be Git-compatible and sanitized. They should not
include local filesystem paths, credentials, raw object data, or hidden ref
names.

## Testing

Use TDD by layer:

- parser tests for create/update command parsing, first-line capabilities,
  duplicate refs, malformed ids, invalid refs, unsupported capabilities, and
  rejected deletes/tags;
- pack ingestion tests for valid whole-object packs, unsupported delta entries,
  checksum mismatch, truncated pack, object id calculation, and quarantine
  cleanup behavior;
- ref store tests for create CAS, update CAS, stale old id rejection, invalid
  ref names, and ref snapshot ordering;
- receive-pack service tests for first branch create, stale update rejection,
  pack failure leaving refs unchanged, report-status output, and receive event
  result mapping at the native-storage boundary;
- one Git CLI compatibility test against the native receive-pack service with
  delta generation disabled.

The compatibility harness can live in tests and invoke the native service
directly through process streams or a small test-only command adapter. It must
not require `git-engine` integration.

## Follow-Up Work

After this slice passes, add:

- `git-engine` provider selection and `GitRepository.receive()` integration;
- delete and tag policy support;
- delta and thin-pack ingestion;
- fast-forward traversal through native object models;
- multi-ref atomic transactions and `atomic` capability;
- smart HTTP, SSH, or native transport routing.
