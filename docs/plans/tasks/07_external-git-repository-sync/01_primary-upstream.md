# Implement Primary Upstream Synchronization

Status: todo
Owner: codex, session 6e3b, paused 2026-09-04 01:25 Europe/Amsterdam;
next: implement serialized outbound processing, retry, and minute audits.
Depends on: completed repository and mirror configuration foundation

Attach one reserved `upstream` remote, reconcile compatible state when Orion
starts or reconnects, and then synchronize changes outbound from Orion.

## Scope

- Support the GitHub HTTPS token profile over provider-neutral Git transport.
- Fetch all branches on attach into `refs/remotes/upstream/*`; do not sync tags.
- Plan all branches before changing live refs or pushing, and apply compatible
  local creates and fast-forwards atomically.
- Leave live refs and the external repository independent when any branch has
  diverged; expose all conflicting branch tips for operator reconciliation.
- Retry attachment explicitly after the operator resolves divergence in Orion.
- Coalesce outbound ref updates durably and retry transient failures with
  backoff without blocking local repository use.
- Audit upstream refs once per minute with staggering and no per-remote overlap;
  detect out-of-band remote changes without importing them after attachment.
- Cover startup import, Orion-ahead push, mixed compatible branches, divergence,
  restart recovery, lost responses, expected-ID races, and manual retry.

---

## Primary Upstream Git Synchronization Design

### Goal

Let an Orion repository attach to one external primary Git remote, import its
compatible branch state when Orion starts or reconnects, and then keep the
remote current through event-driven outbound synchronization. Keep GitHub as a
profile over provider-neutral Git synchronization rather than as a separate
replication engine.

### First Delivery

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

### Configuration Model

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

### Repository Ref Model

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

### Attachment and Reconciliation

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

### Outbound Synchronization

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

### Scheduling and Concurrency

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

### Runtime States and Failures

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

### Component Boundaries

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

### Verification

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

---

## Primary Upstream Git Synchronization Implementation Plan

**Goal:** Add a provider-neutral service that attaches an Orion native repository to one GitHub HTTPS
`upstream`, reconciles all compatible branches on start, and then mirrors Orion branch changes outbound.

**Architecture:** Add a `git/git-sync` module between `git-client`, `git-native-storage`, and the immutable
repository configuration model. A pure planner classifies a complete branch snapshot, a Smart HTTP gateway
owns remote I/O, and a serialized per-repository coordinator owns attachment, durable coalesced work, retry,
and one-minute audits. The service accepts explicit desired-state registrations now; the separate native Git
configuration-snapshot task will later feed those registrations into the running service.

**Tech Stack:** Java 21, Maven reactor modules, JDK `HttpClient`, Orion native Git storage/client APIs,
Jackson JSON, JUnit 5, AssertJ.

---

#### Task 1: Add commit relationship queries to native storage

**Files:**

- Modify:
  `git/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/upload/NativeObjectClosure.java`
- Test:
  `git/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/upload/NativeObjectClosureTest.java`

**Step 1: Write failing relationship tests**

Create commit graphs with the existing native object helpers and assert:

```java
assertThat(closure.isAncestor(root, child)).isTrue();
assertThat(closure.isAncestor(child, root)).isFalse();
assertThat(closure.mergeBase(left, right)).contains(root);
```

Cover equal tips, linear history, two diverged tips, unrelated histories, and a missing object.

**Step 2: Run the focused test and confirm failure**

Run:

```bash
make run-test MODULE=git/git-native-storage TEST='NativeObjectClosureTest'
```

Expected: compilation failure because `isAncestor` and `mergeBase` do not exist.

**Step 3: Implement minimal graph queries**

Add public query methods that traverse commit parents only, use deterministic breadth-first order, and return
an empty merge base when histories are unrelated or incomplete. Keep existing fetch-closure behavior unchanged.

**Step 4: Run the focused test**

Run the command from Step 2. Expected: PASS.

#### Task 2: Create the sync module and pure all-branch planner

**Files:**

- Modify: `git/pom.xml`
- Create: `git/git-sync/pom.xml`
- Create: `git/git-sync/src/main/java/pro/deta/orion/git/sync/GitBranchAction.java`
- Create: `git/git-sync/src/main/java/pro/deta/orion/git/sync/GitBranchPlan.java`
- Create: `git/git-sync/src/main/java/pro/deta/orion/git/sync/GitAttachPlan.java`
- Create: `git/git-sync/src/main/java/pro/deta/orion/git/sync/GitAttachPlanner.java`
- Test: `git/git-sync/src/test/java/pro/deta/orion/git/sync/GitAttachPlannerTest.java`

**Step 1: Write failing planner tests**

Build local/upstream maps and a fake relationship query. Assert all five classifications:

```java
GitAttachPlan plan = planner.plan(localHeads, upstreamHeads, graph);

assertThat(plan.branches()).extracting(GitBranchPlan::action)
        .containsExactly(CREATE_LOCAL, FAST_FORWARD_LOCAL, NO_OP, PUSH_UPSTREAM, DIVERGED);
assertThat(plan.compatible()).isFalse();
```

Also assert stable branch ordering, every divergence is reported in one plan, and merge bases are optional.

**Step 2: Run the test and confirm failure**

Run:

```bash
make run-test MODULE=git/git-sync TEST='GitAttachPlannerTest'
```

Expected: reactor/module failure because `git-sync` does not exist.

**Step 3: Add the module and planner**

Define `CREATE_LOCAL`, `FAST_FORWARD_LOCAL`, `PUSH_UPSTREAM`, `NO_OP`, and `DIVERGED`. The planner must inspect
the union of `refs/heads/*`, reject non-head input, and produce the whole immutable plan before callers mutate
anything.

**Step 4: Run the planner test**

Run the command from Step 2. Expected: PASS.

#### Task 3: Add provider-neutral remote mechanics and the GitHub profile

**Files:**

- Create: `git/git-sync/src/main/java/pro/deta/orion/git/sync/GitCredentialResolver.java`
- Create: `git/git-sync/src/main/java/pro/deta/orion/git/sync/GitRemoteConnection.java`
- Create: `git/git-sync/src/main/java/pro/deta/orion/git/sync/GitRemoteProfile.java`
- Create: `git/git-sync/src/main/java/pro/deta/orion/git/sync/GitHubRemoteProfile.java`
- Create: `git/git-sync/src/main/java/pro/deta/orion/git/sync/GitRemoteGateway.java`
- Create: `git/git-sync/src/main/java/pro/deta/orion/git/sync/SmartHttpGitRemoteGateway.java`
- Test: `git/git-sync/src/test/java/pro/deta/orion/git/sync/GitHubRemoteProfileTest.java`
- Test: `git/git-sync/src/test/java/pro/deta/orion/git/sync/SmartHttpGitRemoteGatewayTest.java`

**Step 1: Write failing profile tests**

Assert that the GitHub profile accepts an HTTPS GitHub repository URI, resolves only the configured secret
reference, and supplies HTTP Basic credentials with a non-empty username and the token as password. Assert that
unsupported providers, non-GitHub hosts, missing credentials, and secret-bearing diagnostics fail safely.

**Step 2: Run the profile test and confirm failure**

Run:

```bash
make run-test MODULE=git/git-sync TEST='GitHubRemoteProfileTest'
```

Expected: compilation failure because the profile classes do not exist.

**Step 3: Implement the profile and gateway boundary**

`GitRemoteProfile` returns a connection containing upload-pack and receive-pack clients, the sanitized URI, and
client options. `GitHubRemoteProfile` remains the only provider-specific class. `GitRemoteGateway` exposes:

```java
GitFetchedHeads fetchHeads(NativeGitRepository repository);
Map<String, String> listHeads();
GitPushOutcome pushHead(NativeGitRepository repository, String refName,
        String expectedRemoteId, String desiredId);
```

The Smart HTTP implementation fetches every advertised `refs/heads/*` in one upload-pack, publishes fetched
objects with `refs/remotes/upstream/*`, exports pushes through `NativePackProducer`, and never requests tags.

**Step 4: Add gateway tests**

Use a scripted transport to cover multi-head fetch, empty remote, exact expected-ID push, already-applied push
after a lost response, remote ref mismatch, rejection, and sanitized failures.

**Step 5: Run the focused tests**

Run:

```bash
make run-test MODULE=git/git-sync TEST='GitHubRemoteProfileTest,SmartHttpGitRemoteGatewayTest'
```

Expected: PASS.

#### Task 4: Execute atomic attachment and conflict preservation

**Files:**

- Create: `git/git-sync/src/main/java/pro/deta/orion/git/sync/GitSyncConflict.java`
- Create: `git/git-sync/src/main/java/pro/deta/orion/git/sync/GitAttachmentResult.java`
- Create: `git/git-sync/src/main/java/pro/deta/orion/git/sync/GitAttachment.java`
- Test: `git/git-sync/src/test/java/pro/deta/orion/git/sync/GitAttachmentTest.java`

**Step 1: Write failing attachment tests**

Cover initial import into an empty Orion repository, local fast-forward, Orion-ahead push, and mixed compatible
branches. Add a divergence test that asserts remote-tracking refs are updated while every live head and remote
head remain unchanged. Add an expected-old-ID race that asserts the complete plan is recomputed.

**Step 2: Run the test and confirm failure**

Run:

```bash
make run-test MODULE=git/git-sync TEST='GitAttachmentTest'
```

Expected: compilation failure because attachment execution does not exist.

**Step 3: Implement attachment**

Fetch first, snapshot all live and upstream heads, plan once, and stop on any divergence. For a compatible plan,
publish every `CREATE_LOCAL` and `FAST_FORWARD_LOCAL` through one atomic native ref update using expected old
IDs. Push `PUSH_UPSTREAM` branches one at a time with the observed remote ID. Retry local planning on stale
publication; never replay a stored mutation plan.

**Step 4: Run the attachment tests**

Run the command from Step 2. Expected: PASS.

#### Task 5: Persist mirror state and coalesced outbound work

**Files:**

- Create: `git/git-sync/src/main/java/pro/deta/orion/git/sync/GitSyncState.java`
- Create: `git/git-sync/src/main/java/pro/deta/orion/git/sync/GitSyncFailure.java`
- Create: `git/git-sync/src/main/java/pro/deta/orion/git/sync/GitOutboundWork.java`
- Create: `git/git-sync/src/main/java/pro/deta/orion/git/sync/GitSyncSnapshot.java`
- Create: `git/git-sync/src/main/java/pro/deta/orion/git/sync/GitSyncStateStore.java`
- Create: `git/git-sync/src/main/java/pro/deta/orion/git/sync/InMemoryGitSyncStateStore.java`
- Create: `git/git-sync/src/main/java/pro/deta/orion/git/sync/FileGitSyncStateStore.java`
- Test: `git/git-sync/src/test/java/pro/deta/orion/git/sync/GitSyncStateStoreTest.java`
- Test: `git/git-sync/src/test/java/pro/deta/orion/git/sync/FileGitSyncStateStoreTest.java`

**Step 1: Write failing store tests**

Assert default `ATTACHING`, latest-tip coalescing by repository/remote/branch, and conditional completion that
cannot drop a newer tip. Cover conflict retention, last-attempt metadata, and reload after an interrupted retry.
Assert serialized files contain no credential, authorization header, pack bytes, or remote URI user info.

**Step 2: Run the tests and confirm failure**

Run:

```bash
make run-test MODULE=git/git-sync TEST='GitSyncStateStoreTest,FileGitSyncStateStoreTest'
```

Expected: compilation failure because the state store does not exist.

**Step 3: Implement stores**

Represent state as immutable snapshots. Make each mutation a synchronized read-modify-write operation. The file
store writes one versioned JSON document per repository/remote via a sibling temporary file, flushes it, and
atomically replaces the durable file. Treat an in-flight item as pending after restart.

**Step 4: Run the store tests**

Run the command from Step 2. Expected: PASS.

#### Task 6: Add serialized outbound processing, retry, and minute audits

**Files:**

- Create: `git/git-sync/src/main/java/pro/deta/orion/git/sync/GitSyncBackoff.java`
- Create: `git/git-sync/src/main/java/pro/deta/orion/git/sync/GitSyncCoordinator.java`
- Test: `git/git-sync/src/test/java/pro/deta/orion/git/sync/GitSyncCoordinatorTest.java`

**Step 1: Write failing coordinator tests**

Use a deterministic scheduler/gateway and assert:

- start attaches immediately and enters `ACTIVE` only after reconciliation;
- successful local `refs/heads/*` updates enqueue immediately while tracking refs do not;
- repeated local updates coalesce and push the latest tip;
- retryable failures enter `OFFLINE`, retain work, and use exponential backoff with bounded jitter;
- restart drains persisted work;
- a lost response completes when the desired tip is already upstream;
- an unexpected remote change fetches tracking refs, enters `CONFLICTED`, and blocks pushes;
- audits use a staggered initial offset, one-minute fixed delay, and cannot overlap another remote operation.

**Step 2: Run the test and confirm failure**

Run:

```bash
make run-test MODULE=git/git-sync TEST='GitSyncCoordinatorTest'
```

Expected: compilation failure because the coordinator does not exist.

**Step 3: Implement the coordinator**

Subscribe to native ref updates before initial attachment, serialize all remote work through one coordinator
execution gate, and read the current desired tip before every push. Keep the repository usable in `OFFLINE` and
`CONFLICTED`. An audit only compares observed upstream state after activation; it never advances live heads.

**Step 4: Run the coordinator tests**

Run the command from Step 2. Expected: PASS.

#### Task 7: Expose service lifecycle, status, and explicit retry

**Files:**

- Create: `git/git-sync/src/main/java/pro/deta/orion/git/sync/GitSyncRegistration.java`
- Create: `git/git-sync/src/main/java/pro/deta/orion/git/sync/GitSyncStatus.java`
- Create: `git/git-sync/src/main/java/pro/deta/orion/git/sync/GitSyncService.java`
- Test: `git/git-sync/src/test/java/pro/deta/orion/git/sync/GitSyncServiceTest.java`

**Step 1: Write failing service tests**

Register multiple repositories and assert independent lifecycle, deterministic audit staggering, safe status,
manual retry from `CONFLICTED`, registration replacement, disabled/removal behavior, and complete shutdown.

**Step 2: Run the test and confirm failure**

Run:

```bash
make run-test MODULE=git/git-sync TEST='GitSyncServiceTest'
```

Expected: compilation failure because the service does not exist.

**Step 3: Implement the service**

Accept explicit `GitSyncRegistration` values containing a repository identity, native repository, primary remote
configuration, gateway, and durable state key. Reject aliases other than `upstream` and mappings other than the
fixed all-branch mapping in this first slice. Expose immutable status and `retry(repositoryId)` without exposing
credentials or unsanitized failures.

**Step 4: Run the service tests**

Run the command from Step 2. Expected: PASS.

#### Task 8: Verify module contracts and end-to-end behavior

**Files:**

- Create: `git/git-sync/src/test/java/pro/deta/orion/git/sync/GitSyncEndToEndTest.java`
- Modify: `docs/plans/tasks/07_external-git-repository-sync/01_primary-upstream.md`

**Step 1: Add an HTTPS end-to-end test**

Start a local Smart HTTP Git backend and exercise a populated external repository through the concrete gateway:
startup import, Orion-ahead outbound push, remote outage plus store reload, out-of-band remote divergence,
tracking-ref availability, manual operator reconciliation in Orion, and explicit retry back to `ACTIVE`.

**Step 2: Run all sync and affected native-storage tests**

Run:

```bash
make run-test MODULE=git/git-sync \
  TEST='GitAttachPlannerTest,GitHubRemoteProfileTest,SmartHttpGitRemoteGatewayTest'
make run-test MODULE=git/git-sync \
  TEST='GitAttachmentTest,GitSyncStateStoreTest,FileGitSyncStateStoreTest'
make run-test MODULE=git/git-sync \
  TEST='GitSyncCoordinatorTest,GitSyncServiceTest,GitSyncEndToEndTest'
make run-test MODULE=git/git-native-storage TEST='NativeObjectClosureTest,NativeGitRepositoryTest'
```

Expected: PASS.

**Step 3: Run development verification**

Run:

```bash
mvn verify -Pdev -T 4
```

Expected: BUILD SUCCESS.
