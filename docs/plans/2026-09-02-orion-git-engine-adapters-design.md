# Orion Git Engine Adapters Design

## Goal

Expose Orion's public native Git client, repository, and server primitives as
reusable test-only client and server adapters for the shared interoperability
harness.

## Module Boundary

Add a narrow `tests/git-engine-orion-adapters` module. It depends on the
engine-neutral `git-engine-test-support` contracts plus the production
`git-client`, `git-native-storage`, and `git-transport` modules. This preserves
the existing dependency direction: production Git client tests may continue
using the neutral support module without forming a Maven cycle.

The module exposes an Orion engine factory. Each returned server owns fresh
file-backed native storage and one `GitNativeTransportService`; each returned
client creates independent native local repositories. Neither adapter is a
production porcelain API.

## Orion Server Adapter

Provisioning validates a one-segment repository name, creates an isolated
`FileNativeGitRepositoryProvider` below the matrix invocation directory, and
creates the requested empty native repository whose default branch is `main`.
The adapter starts `GitNativeTransportService` on `127.0.0.1:0` with the
production allow-all native-daemon profile, then exposes the actual bound port
as a `git://127.0.0.1:<port>/<repository>` URI.

Server snapshots use a fresh read-only JGit clone only as an independent
observer of the public server endpoint. Empty native repositories return the
known empty `main` state because Git's legacy empty-repository advertisement
has no object to clone. Closing the adapter stops the service deterministically
and retains endpoint/storage details in diagnostics.

## Orion Client Adapter

An Orion worktree stores native loose objects and refs beneath its `.git`
directory, with a symbolic `HEAD` for independent inspection. `add` records
paths, and `commit` reads their current bytes and calls the public native
repository file-save operation with the shared deterministic identity.

Push discovers receive-pack refs with `GitReceivePackClient`, derives commands
from requested refspecs, and streams a pack produced by `NativeGitRepository`
directly into `GitReceivePackClient`. Fetch discovers upload-pack refs with
`GitUploadPackClient`, streams the returned pack into a native
`PackIngestionSession`, publishes the quarantined objects, and updates remote
tracking refs. The adapter treats structured client failures and rejected ref
statuses as explicit operation failures.

Clone is a macro over native repository initialization and fetch followed by a
local `main` ref update. Fast-forward pull is a fetch followed by ancestry
validation through production `NativeObjectClosure`, then a compare-and-set
local ref update. JGit is not used for any Orion client behavior.

## Capabilities and Failure Handling

The Orion adapters declare their full supported capability set explicitly.
Tests assert completeness against the operations required by the matrix rather
than filtering unsupported invocations. Unsupported pathspecs, refspec shapes,
missing refs, incomplete packs, rejected updates, and non-fast-forward pulls
fail with actionable exceptions.

Network deadlines remain owned by `GitTcpClientTransport` and the public Git
client options. The adapters add no worker threads or per-call timeout
wrappers.

## Testing

Focused tests first cover local deterministic commit creation, native fetch
ingestion, clone and fast-forward pull, capability completeness, server
provisioning on loopback port zero, and deterministic shutdown. Cross-engine
tests then prove deterministic Orion push and clone/fetch against JGit,
canonical Git, and Orion servers; canonical Git and JGit push, clone, update,
and fast-forward pull through Orion; and an Orion/Orion result observed through
an independent read-only JGit snapshot.
