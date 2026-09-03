# Primary Upstream Git Synchronization Design

## Goal

Let an Orion repository attach to one external primary Git remote, import its
compatible branch state when Orion starts or reconnects, and then keep the
remote current through event-driven outbound synchronization. Keep GitHub as a
profile over provider-neutral Git synchronization rather than as a separate
replication engine.

## First Delivery

The first delivery supports:

- one reserved `upstream` remote with the explicit `PRIMARY` role;
- HTTPS Git transport with a token credential reference;
- all branch refs, with the fixed mapping
  `refs/heads/*:refs/heads/*` and no tag synchronization;
- startup and reconnect reconciliation;
- event-driven outbound synchronization after attachment;
- durable coalesced outbound work and retry with backoff;
- a staggered remote-ref audit once per minute;
- conflict diagnostics, remote-tracking refs, and an explicit retry operation.

SSH, GitHub App credentials, webhooks, additional outbound remotes, tags, and
configurable branch filtering are later slices. Branch filtering has its own
task node and must apply consistently to attachment, tracking refs, conflict
reporting, and outbound work.

## Configuration Model

Repository desired state belongs to the repository entry under its owning team
in `orion.xml`. Extend the immutable `OrionDocument.Repository` and the XML v2
DTO with repository metadata, a default branch, repository policy, and remote
definitions.

Each remote has a canonical alias, role, provider profile, sanitized URI,
credential reference, and update policy. The `PRIMARY` role is valid only for
the reserved alias `upstream`, and a repository may define at most one primary
remote. A repository without `upstream` remains an ordinary local repository.

The first delivery accepts a GitHub HTTPS profile whose URI identifies an
existing repository and whose credential reference resolves to a token. Raw
credentials, authorization headers, and secret-bearing URIs are invalid.

Queue entries, leases, attempts, observed remote refs, conflicts, and last-run
state are operational facts and must not be written to `orion.xml`.

## Repository Ref Model

The native Orion repository owns live refs and the last fetched upstream view:

```text
refs/heads/main
refs/heads/release
refs/remotes/upstream/main
refs/remotes/upstream/release
```

Only Orion clients and synchronization code may update `refs/heads/*`. Only the
synchronization service may update `refs/remotes/upstream/*`. Clients may read
the remote-tracking refs so an operator can fetch both histories, resolve a
conflict with ordinary Git merge or rebase, and push the reconciled head back
to Orion.

Remote-tracking refs are retained after successful attachment. They represent
the latest remote state confirmed by Orion and keep fetched objects reachable
for diagnostics and manual reconciliation. Removing a remote or narrowing a
future branch filter must not silently delete live, remote, or tracking refs.

## Attachment and Reconciliation

Run attachment when the service starts, when an offline connection becomes
available, and when an operator explicitly requests retry.

1. Read the upstream advertisement and fetch the required objects.
2. Publish the fetched view under `refs/remotes/upstream/*`.
3. Compare every selected local head with its upstream tracking ref.
4. Produce one complete plan before changing live heads or pushing anything.

The planner classifies each branch as follows:

- `CREATE_LOCAL`: the branch exists only upstream;
- `FAST_FORWARD_LOCAL`: Orion is an ancestor of upstream;
- `PUSH_UPSTREAM`: upstream is an ancestor of Orion;
- `NO_OP`: both tips are equal;
- `DIVERGED`: neither tip is an ancestor of the other.

If any branch is `DIVERGED`, publish only the remote-tracking refs. Do not
modify any live head and do not push any branch. Record every conflicting
branch with its local tip, upstream tip, and merge base when available, then
enter `CONFLICTED`.

If every branch is compatible, atomically apply all local creates and
fast-forwards using expected old object IDs. Recompute the plan if a local ref
changed concurrently. After local publication succeeds, push locally leading
branches one at a time with expected upstream object IDs. Enter `ACTIVE` only
after every selected branch is reconciled.

Every retry reads current local and remote state again. A stored plan is never
replayed blindly.

## Outbound Synchronization

Subscribe to successful `NativeGitRepository.onRefUpdate()` notifications and
enqueue work only for `refs/heads/*`. Coalesce pending work by repository,
remote, and branch so the queue retains the latest desired tip rather than one
entry per intermediate commit.

An outbound worker lists the current upstream refs before pushing, verifies the
expected old object ID, exports the needed pack from the native repository, and
uses `git-client` receive-pack over HTTPS. A successful result updates the
matching `refs/remotes/upstream/*` ref.

If the response is lost after the remote accepted the update, retry observes
that the desired tip is already remote and completes idempotently. A remote ref
change that is incompatible with Orion moves the mirror to `CONFLICTED` and
stops outbound processing until explicit retry succeeds.

## Scheduling and Concurrency

Local ref changes schedule outbound work immediately. Failed transport work is
retried with exponential backoff and jitter and survives Orion restart.

While enabled, each mirror performs a lightweight upstream ref audit once per
minute. Audits are staggered within the minute, use fixed delay from completion,
and never overlap another attachment, audit, or push for the same remote. An
outbound event does not wait for the audit interval.

The audit detects out-of-band upstream changes. It does not silently import
them after initial attachment. It refreshes the upstream tracking view and
moves the mirror to `CONFLICTED` when live and upstream histories no longer
match the outbound-only contract.

## Runtime States and Failures

The durable operational state is one of:

- `ATTACHING`: no successful reconciliation has completed;
- `ACTIVE`: outbound synchronization is allowed;
- `OFFLINE`: the last remote operation failed transiently;
- `CONFLICTED`: branch histories or expected object IDs disagree;
- `DISABLED`: the desired configuration disables the remote.

Configuration, credential, authorization, host verification, transport,
protocol, remote rejection, local publication, and divergence failures remain
typed. Local repository reads and writes continue in every state. `OFFLINE`
work stays queued; `CONFLICTED` work is retained as desired branch tips but is
not automatically pushed.

Operational status exposes the safe remote URI, state, last attempt, pending
work, and per-branch conflict details. It never stores or renders token values,
authorization headers, private keys, pack contents, or unsanitized transport
diagnostics.

## Component Boundaries

Add synchronization orchestration under a new `git/git-sync` Maven module:

- `GitSyncService` owns lifecycle and per-repository coordinators;
- `GitAttachPlanner` is a pure local/upstream comparison;
- `GitSyncStateStore` owns durable states and attempt records;
- `GitOutboundQueue` owns durable coalesced work and leases;
- `GitRemoteProfile` resolves a provider-neutral remote connection;
- `GitHubRemoteProfile` validates GitHub HTTPS configuration;
- native-storage and git-client adapters own local and remote Git mechanics.

Configuration types remain in `core/schema`. `git/git-sync` depends on the Git
client and native storage boundaries; it must not place Git application logic
in `core` or transport-specific logic in the configuration model.

## Verification

Unit tests cover every planner classification, multi-branch all-or-nothing
attachment, configuration invariants, queue coalescing, state transitions,
retry timing, audit serialization, lost responses, expected-ID races, and
secret redaction.

Native-storage tests cover atomic object and ref publication, tracking-ref
durability, reachability after restart, and rejected stale updates. HTTPS
contract tests cover authenticated advertisement, fetch, push, remote
rejection, bounded streaming, and safe failures.

The end-to-end acceptance flow starts with a populated external repository,
imports it into Orion, propagates a local Orion push upstream, survives a
remote outage and Orion restart, detects an out-of-band remote change within
one audit interval, exposes the tracking ref for manual reconciliation, and
returns to `ACTIVE` after explicit retry. Local repository use remains
available throughout every remote failure and conflict.

## Existing Baseline Failure

The new worktree baseline consistently fails
`MinaSshOperationTest#wholeOperationWatchdogClosesAStalledSession` with an SSH
provisioning operation timeout. This unrelated failure is recorded in its own
task node and is not part of Git synchronization implementation.
