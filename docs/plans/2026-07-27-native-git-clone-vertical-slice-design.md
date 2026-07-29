# Native Git Clone Vertical Slice Design

## Goal

Serve a protocol v2 `git clone` from a small native repository without routing
upload-pack behavior through JGit.

## Scope

The first slice supports:

- protocol v2 capability advertisement;
- `ls-refs` for `HEAD`, branches, and tags with deterministic ordering;
- one `fetch` request containing wants and optional haves;
- fetch access checks before object traversal;
- commit, tree, and blob closure traversal;
- a complete no-delta pack;
- side-band-64k pack output;
- the existing stream-based `GitRepository.upload()` transport boundary;
- a real Git CLI clone compatibility test.

Filters, shallow fetches, deltas, protocol v0/v1, smart HTTP, SSH, and persistent
native repository administration remain outside this slice.

## Architecture

Native upload-pack remains a backend concern. `GitInternalService` continues to
authorize repository reads and invoke `GitRepository.upload()` through existing
streams. A native repository implementation delegates that method to a
`NativeUploadPackService`.

The service depends on narrow read-only ref and object views. These views may be
implemented by the in-progress native storage module, but upload-pack does not
modify receive-pack files or assume ownership of their uncommitted changes.
If the required storage contracts cannot be added without editing the same files
as the active receive-pack task, this task pauses.

The protocol flow is:

1. advertise protocol v2 capabilities;
2. parse an `ls-refs` or `fetch` request using existing pkt-line primitives;
3. advertise refs or validate requested object ids;
4. execute the configured fetch access check;
5. traverse requested objects and subtract closure reachable from haves;
6. stream a deterministic no-delta pack through side-band-64k;
7. publish upload statistics once after a successful response.

## Data Model

Read-only refs expose a ref name, object id, and optional symbolic target.
Read-only objects expose object id, Git object type, and uncompressed canonical
content. Object ids are verified from `<type> <size>\0<content>` when inserted
into test fixtures.

Commit parsing needs tree and parent headers. Tree parsing reads Git's binary
`<mode> <name>\0<20-byte object id>` entries. Annotated tags are advertised but
peeling is deferred unless the existing object view can resolve them without
expanding the slice.

## Errors and Limits

Malformed pkt-line sequences, invalid SHA-1 ids, unsupported commands and
arguments, missing wants, and unsupported filters become typed native
upload-pack failures. Before pack output begins they are returned as `ERR`
pkt-lines; after side-band begins they use the fatal band.

The service bounds request packet count, total request bytes, traversed object
count, and generated pack bytes. Error text must not expose hidden ref names,
filesystem paths, credentials, or object content.

## Testing

Development follows red-green-refactor:

- unit tests for deterministic `ls-refs`, including an empty repository;
- unit tests for object closure and have subtraction;
- pack builder tests parsed by the Git CLI;
- service tests for successful fetch and rejected/missing wants;
- an end-to-end `git clone` test through the existing stream transport boundary.

Focused module tests run after each behavior. Final verification is
`mvn verify -Pdev` from the repository root, outside the sandbox.

