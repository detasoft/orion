# Primary Upstream Git Synchronization Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

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

### Task 1: Add commit relationship queries to native storage

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

**Step 5: Commit**

```bash
git add git/git-native-storage/src/main/java/pro/deta/orion/git/nativestorage/upload/NativeObjectClosure.java \
  git/git-native-storage/src/test/java/pro/deta/orion/git/nativestorage/upload/NativeObjectClosureTest.java
git commit -m "Expose native Git commit relationships"
```

### Task 2: Create the sync module and pure all-branch planner

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

**Step 5: Commit**

```bash
git add git/pom.xml git/git-sync
git commit -m "Add primary upstream attach planner"
```

### Task 3: Add provider-neutral remote mechanics and the GitHub profile

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

**Step 6: Commit**

```bash
git add git/git-sync
git commit -m "Implement GitHub Smart HTTP remote gateway"
```

### Task 4: Execute atomic attachment and conflict preservation

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

**Step 5: Commit**

```bash
git add git/git-sync
git commit -m "Reconcile primary upstream attachment"
```

### Task 5: Persist mirror state and coalesced outbound work

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

**Step 5: Commit**

```bash
git add git/git-sync
git commit -m "Persist primary upstream synchronization state"
```

### Task 6: Add serialized outbound processing, retry, and minute audits

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

**Step 5: Commit**

```bash
git add git/git-sync
git commit -m "Coordinate durable outbound Git synchronization"
```

### Task 7: Expose service lifecycle, status, and explicit retry

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

**Step 5: Commit**

```bash
git add git/git-sync
git commit -m "Expose primary upstream synchronization service"
```

### Task 8: Verify module contracts and end-to-end behavior

**Files:**

- Create: `git/git-sync/src/test/java/pro/deta/orion/git/sync/GitSyncEndToEndTest.java`
- Modify: `docs/plans/current-work/external-git-repository-sync/primary-upstream/TASK.md`

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

**Step 4: Request code review and resolve blocking findings**

Apply `superpowers:requesting-code-review`, then handle findings through
`superpowers:receiving-code-review`. Repeat the affected focused tests and `mvn verify -Pdev -T 4`.

**Step 5: Finish the task branch**

Follow the repository worktree completion rules: remove the completed leaf/link in the squashed task commit,
cherry-pick that commit to `main`, run `make test` on `main`, and remove the worktree and task branch.
