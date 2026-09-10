# Implement GitHub Commit Replication

Status: todo
Source: converted from former root task list Next section.
Depends on: completed configuration-secret-cryptography (d17476ab),
completed repository-and-mirror-configuration (146b9e76)

Implement the detailed replication plan embedded below, then add GitHub
mirror administration, manual sync, and webhook-driven inbound synchronization.

---

## GitHub Commit Replication Implementation Plan

**Goal:** Add the first GitHub synchronization candidate that can replicate selected commits from Orion to GitHub and receive selected commits from GitHub into Orion.

**Architecture:** Build provider-neutral Git mirror core with a GitHub transport profile that supports HTTPS and SSH remotes from the first candidate. Treat bidirectional sync as two explicit one-way flows sharing one config, queue, worker, source attribution, and fast-forward conflict rules; do not add automatic merge/rebase behavior in the first candidate.

**Tech Stack:** Java 21, Maven, JUnit 5, AssertJ, Dagger, `OrionEventManager`, `GitRepositoryProvider`, `GitRepository.upload/receive`, the integrated native Git protocol client and its Smart HTTP and SSH transports.

---

### Decision

Implement the first candidate as generic Git remote replication with a GitHub
profile, not as a GitHub REST integration.

Why:

- Git commits, trees, tags, and refs are already Git protocol concerns.
- GitHub REST APIs are useful for repository provisioning and webhook setup, but
  they are not the right primitive for moving repository history.
- Orion already publishes `GitReceiveOrionEvent` after local receive-pack, so
  outbound replication can start from existing events.
- Existing plans already define native remote Git client primitives, smart
  HTTP(S), SSH transport, mirror queue semantics, credential references, and
  protected ref policy. This plan narrows those into a first shippable GitHub
  candidate.

Rejected first candidates:

- GitHub-only REST commit writing: too provider-specific and cannot preserve
  arbitrary existing Git history.
- Shelling out to `git`: easy to prototype, but it bypasses Orion's protocol,
  credential, logging, and backend abstractions.
- Full automatic bidirectional mirroring: needs conflict resolution, loop
  prevention, force/delete policy, and audit before it is safe.

### Scope

In scope:

- Existing GitHub repository; Orion does not create it in this candidate.
- HTTPS Git remotes using a credential reference for a GitHub token or GitHub App
  installation token.
- SSH Git remotes using a credential reference for a deploy key or user SSH key,
  with known-hosts validation. Accept both
  `git@github.com:<owner>/<repo>.git` and
  `ssh://git@github.com/<owner>/<repo>.git`.
- Explicit refspecs only. Start with branch refs and optional tag refs.
- Outbound: local Orion push enqueues async push to GitHub.
- Inbound: manual sync, schedule, or verified GitHub webhook enqueues fetch from
  GitHub.
- Fast-forward-only branch updates by default.
- Force pushes, deletes, and tag rewrites disabled by default and represented as
  explicit policy choices.
- Durable queue and run records.
- Loop prevention for commits received from GitHub and then observed through
  local receive events.

Out of scope for the first candidate:

- Creating GitHub repositories.
- Automatic merge, rebase, or conflict repair.
- GitHub webhook auto-installation.
- Multiple providers in the same UI/API surface.
- Synchronous "block local push until GitHub push succeeds" mode.
- Storing raw GitHub tokens, private keys, passphrases, known-host contents, auth
  headers, or SSH command lines with secrets in mirror config, events, logs, or
  queue records.

### Data Flow

Outbound Orion to GitHub:

1. User pushes to Orion.
2. `GitInternalService` publishes `GitReceiveOrionEvent`.
3. `GitMirrorReceiveEventHandler` finds enabled GitHub mirrors whose outbound
   refspecs match successful ref updates.
4. Handler enqueues or coalesces an `OUTBOUND_PUSH` work item.
5. Worker leases the item, reads local and remote refs, checks policy, exports
   the needed local pack through the local Git protocol bridge, pushes to
   GitHub receive-pack, records the result, and updates mirror state.

Inbound GitHub to Orion:

1. Admin action, schedule, or verified GitHub webhook enqueues `INBOUND_FETCH`.
2. Worker leases the item, discovers GitHub refs, checks inbound refspecs and
   local refs, fetches the pack from GitHub upload-pack, applies it to Orion
   through the local receive-pack bridge using the mirror actor, records the
   result, and updates mirror state.
3. Local receive events produced by the mirror actor do not enqueue another
   outbound push for the same mirror and refs.

Default conflict behavior:

- If destination already has the same object id, mark the ref as up to date.
- If destination can fast-forward, update it.
- If destination would need a non-fast-forward update, fail the run with a typed
  conflict and leave both repositories unchanged.
- If a ref delete or forced update is observed and policy does not allow it,
  fail the run with a typed policy rejection.

### Naming

Use `GitMirror` for provider-neutral model names and `GitHubMirror` only for
GitHub-specific validation, webhook, and transport/credential profile classes.

The repository already stores plans under `docs/plans`; this file is the plan
requested for `plans/`.

---

#### Task 1: Add the Git Mirror Module Skeleton

**Files:**

- Modify: `core/pom.xml`
- Create: `core/git-mirror/pom.xml`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/package-info.java`
- Create: `core/git-mirror/src/test/java/pro/deta/orion/git/mirror/GitMirrorModuleTest.java`

**Step 1: Write the failing module smoke test**

```java
package pro.deta.orion.git.mirror;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitMirrorModuleTest {
    @Test
    void modulePackageIsPresent() {
        assertThat(GitMirrorModuleTest.class.getPackageName())
                .isEqualTo("pro.deta.orion.git.mirror");
    }
}
```

**Step 2: Run the test to verify the module is not wired**

Run:

```bash
mvn test -Pdev -q -pl core/git-mirror -am -Dtest=GitMirrorModuleTest
```

Expected: FAIL because `core/git-mirror` is not a Maven module yet.

**Step 3: Add the module**

Add this module to `core/pom.xml` after `git-storage`:

```xml
<module>git-mirror</module>
```

Create `core/git-mirror/pom.xml` with dependencies on:

- `pro.deta.orion.core:common`
- `pro.deta.orion.core:git-common`
- `pro.deta.orion.core:lifecycle-state-machine`
- `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` if the first config
  store uses YAML
- `org.assertj:assertj-core` for tests

Do not depend on `core/git-engine`; mirror core must use `GitRepositoryProvider`
and `GitRepository` boundaries from `common` and `git-common`.

**Step 4: Run the test**

Run:

```bash
mvn test -Pdev -q -pl core/git-mirror -am -Dtest=GitMirrorModuleTest
```

Expected: PASS.

#### Task 2: Define Mirror Configuration and RefSpec Validation

**Files:**

- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/config/GitMirrorConfig.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/config/GitMirrorDirection.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/config/GitMirrorProvider.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/config/GitMirrorRefSpec.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/config/GitMirrorConflictPolicy.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/config/GitMirrorConfigValidator.java`
- Test: `core/git-mirror/src/test/java/pro/deta/orion/git/mirror/config/GitMirrorConfigValidatorTest.java`

**Step 1: Write failing validation tests**

Cover the happy path and one meaningful unsafe case:

```java
@Test
void acceptsGithubHttpsMirrorWithExplicitInboundAndOutboundRefspecs() {
    GitMirrorConfig config = GitMirrorConfig.builder()
            .id("mirror-1")
            .repositoryName("team/project")
            .provider(GitMirrorProvider.GITHUB)
            .remoteUri("https://github.com/acme/project.git")
            .credentialReference("secret:github/acme-project")
            .direction(GitMirrorDirection.BIDIRECTIONAL)
            .outboundRefSpecs(List.of(GitMirrorRefSpec.parse("refs/heads/main:refs/heads/main")))
            .inboundRefSpecs(List.of(GitMirrorRefSpec.parse("refs/heads/main:refs/heads/main")))
            .conflictPolicy(GitMirrorConflictPolicy.fastForwardOnly())
            .enabled(true)
            .build();

    assertThat(new GitMirrorConfigValidator().validate(config)).isEmpty();
}

@Test
void acceptsGithubSshMirrorWithExplicitInboundAndOutboundRefspecs() {
    GitMirrorConfig config = GitMirrorConfig.builder()
            .id("mirror-1")
            .repositoryName("team/project")
            .provider(GitMirrorProvider.GITHUB)
            .remoteUri("git@github.com:acme/project.git")
            .credentialReference("secret:github/acme-project-deploy-key")
            .direction(GitMirrorDirection.BIDIRECTIONAL)
            .outboundRefSpecs(List.of(GitMirrorRefSpec.parse("refs/heads/main:refs/heads/main")))
            .inboundRefSpecs(List.of(GitMirrorRefSpec.parse("refs/heads/main:refs/heads/main")))
            .conflictPolicy(GitMirrorConflictPolicy.fastForwardOnly())
            .enabled(true)
            .build();

    assertThat(new GitMirrorConfigValidator().validate(config)).isEmpty();
}

@Test
void rejectsRemoteUriWithEmbeddedCredentials() {
    GitMirrorConfig config = validGithubConfigBuilder()
            .remoteUri("https://token@github.com/acme/project.git")
            .build();

    assertThat(new GitMirrorConfigValidator().validate(config))
            .anySatisfy(error -> assertThat(error.code()).isEqualTo("REMOTE_URI_CONTAINS_CREDENTIALS"));
}
```

**Step 2: Run tests to verify they fail**

Run:

```bash
mvn test -Pdev -q -pl core/git-mirror -am -Dtest=GitMirrorConfigValidatorTest
```

Expected: FAIL because the config classes do not exist.

**Step 3: Implement the model**

Rules:

- `remoteUri` must be either HTTPS or SSH for `GitMirrorProvider.GITHUB` in the
  first candidate.
- Accepted HTTPS shape: `https://github.com/<owner>/<repo>.git`.
- Accepted SSH shapes:
  - `git@github.com:<owner>/<repo>.git`;
  - `ssh://git@github.com/<owner>/<repo>.git`.
- Reject SSH users other than `git`, non-GitHub hosts, missing owner/repository
  path segments, and paths that do not end in `.git`.
- `credentialReference` is required for private GitHub repositories.
- For HTTPS, `credentialReference` resolves a token credential.
- For SSH, `credentialReference` resolves a structured SSH credential with a
  private-key reference, optional passphrase reference, and known-hosts or pinned
  host-key policy.
- Raw tokens, username/password pairs, embedded HTTPS URI user-info, raw private
  key material, and raw passphrases are invalid.
- Refspec source and destination must be full refs under `refs/heads/` or
  `refs/tags/`.
- Wildcard refspecs are allowed only when source and destination wildcard shape
  match exactly, for example `refs/heads/*:refs/heads/*`.
- Direction values: `OUTBOUND`, `INBOUND`, `BIDIRECTIONAL`.
- Default conflict policy: fast-forward branches, no force, no delete, no tag
  rewrite.

Minimal public shape:

```java
public record GitMirrorConfig(
        String id,
        String repositoryName,
        GitMirrorProvider provider,
        String remoteUri,
        String credentialReference,
        GitMirrorDirection direction,
        List<GitMirrorRefSpec> outboundRefSpecs,
        List<GitMirrorRefSpec> inboundRefSpecs,
        GitMirrorConflictPolicy conflictPolicy,
        boolean enabled) {
}
```

Use ordinary constructors or a small local builder pattern consistent with
existing project style. Avoid Lombok unless surrounding code in this module
already uses it.

**Step 4: Run tests**

Run:

```bash
mvn test -Pdev -q -pl core/git-mirror -am -Dtest=GitMirrorConfigValidatorTest
```

Expected: PASS.

#### Task 3: Add a Durable Mirror Config Store

**Files:**

- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/config/GitMirrorConfigStore.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/config/FileGitMirrorConfigStore.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/config/InMemoryGitMirrorConfigStore.java`
- Test: `core/git-mirror/src/test/java/pro/deta/orion/git/mirror/config/FileGitMirrorConfigStoreTest.java`

**Step 1: Write failing store tests**

```java
@Test
void reloadsMirrorConfigsFromDisk() {
    FileGitMirrorConfigStore store = new FileGitMirrorConfigStore(configFile, validator);
    GitMirrorConfig config = validGithubConfig();

    store.save(config);
    FileGitMirrorConfigStore reloaded = new FileGitMirrorConfigStore(configFile, validator);

    assertThat(reloaded.findByRepository("team/project"))
            .containsExactly(config);
}

@Test
void disabledMirrorIsStoredButNotReturnedForEnabledQueries() {
    GitMirrorConfig disabled = validGithubConfigBuilder().enabled(false).build();
    store.save(disabled);

    assertThat(store.findEnabledByRepository("team/project")).isEmpty();
    assertThat(store.findByRepository("team/project")).containsExactly(disabled);
}
```

**Step 2: Run tests to verify they fail**

Run:

```bash
mvn test -Pdev -q -pl core/git-mirror -am -Dtest=FileGitMirrorConfigStoreTest
```

Expected: FAIL because the store does not exist.

**Step 3: Implement the store**

API:

```java
public interface GitMirrorConfigStore {
    Optional<GitMirrorConfig> findById(String id);
    List<GitMirrorConfig> findByRepository(String repositoryName);
    List<GitMirrorConfig> findEnabledByRepository(String repositoryName);
    List<GitMirrorConfig> findEnabled();
    void save(GitMirrorConfig config);
}
```

Use one YAML or JSON file for the first durable implementation. Validate before
save and after load. Write through a temp file and atomic move so a process crash
does not leave partial mirror config.

**Step 4: Run tests**

Run:

```bash
mvn test -Pdev -q -pl core/git-mirror -am -Dtest=FileGitMirrorConfigStoreTest
```

Expected: PASS.

#### Task 4: Add Queue and Run Record Storage

**Files:**

- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/queue/GitMirrorWorkItem.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/queue/GitMirrorWorkKind.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/queue/GitMirrorWorkState.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/queue/GitMirrorTrigger.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/queue/GitMirrorRunRecord.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/queue/GitMirrorQueueStore.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/queue/FileGitMirrorQueueStore.java`
- Test: `core/git-mirror/src/test/java/pro/deta/orion/git/mirror/queue/FileGitMirrorQueueStoreTest.java`

**Step 1: Write failing queue tests**

```java
@Test
void coalescesPendingWorkByDedupeKey() {
    GitMirrorWorkItem first = outboundWork("mirror-1", "refs/heads/main");
    GitMirrorWorkItem second = outboundWork("mirror-1", "refs/heads/main");

    GitMirrorWorkItem storedFirst = queue.enqueue(first);
    GitMirrorWorkItem storedSecond = queue.enqueue(second);

    assertThat(storedSecond.id()).isEqualTo(storedFirst.id());
    assertThat(queue.pending()).hasSize(1);
}

@Test
void leasesOnlyAvailableWorkAndExpiresLease() {
    GitMirrorWorkItem item = queue.enqueue(outboundWork("mirror-1", "refs/heads/main"));

    Optional<GitMirrorWorkItem> leased = queue.leaseNext("worker-1", Duration.ofMinutes(1));
    Optional<GitMirrorWorkItem> leasedAgain = queue.leaseNext("worker-2", Duration.ofMinutes(1));

    assertThat(leased).contains(item.withState(GitMirrorWorkState.LEASED));
    assertThat(leasedAgain).isEmpty();
}
```

**Step 2: Run tests to verify they fail**

Run:

```bash
mvn test -Pdev -q -pl core/git-mirror -am -Dtest=FileGitMirrorQueueStoreTest
```

Expected: FAIL because queue classes do not exist.

**Step 3: Implement queue storage**

States:

```java
PENDING, LEASED, RUNNING, SUCCEEDED, FAILED_RETRYABLE,
FAILED_PERMANENT, SUPERSEDED, CANCELLED, DEAD_LETTER
```

Work item fields:

- work id
- mirror id
- repository name
- work kind: `OUTBOUND_PUSH`, `INBOUND_FETCH`
- trigger kind: local receive event, manual, schedule, GitHub webhook, retry
- source event id or webhook delivery id when known
- matched ref names
- dedupe key
- attempt count
- next attempt time
- lease owner and lease expiry
- safe diagnostics

Run records are append-only. Never store raw credentials, auth headers, webhook
payloads, or pack bytes.

**Step 4: Run tests**

Run:

```bash
mvn test -Pdev -q -pl core/git-mirror -am -Dtest=FileGitMirrorQueueStoreTest
```

Expected: PASS.

#### Task 5: Enqueue Outbound Work from Local Receive Events

**Files:**

- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/event/GitMirrorReceiveEventHandler.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/event/GitMirrorActor.java`
- Test: `core/git-mirror/src/test/java/pro/deta/orion/git/mirror/event/GitMirrorReceiveEventHandlerTest.java`

**Step 1: Write failing event handler tests**

```java
@Test
void enqueuesOutboundPushForSuccessfulMatchingRefUpdate() {
    GitReceiveOrionEvent event = receiveEvent("team/project", "alice",
            ref("refs/heads/main", GitRefUpdateType.UPDATE, GitRefUpdateResult.OK));

    handler.onReceive(event);

    assertThat(queue.pending())
            .singleElement()
            .satisfies(item -> {
                assertThat(item.kind()).isEqualTo(GitMirrorWorkKind.OUTBOUND_PUSH);
                assertThat(item.mirrorId()).isEqualTo("mirror-1");
                assertThat(item.refNames()).containsExactly("refs/heads/main");
            });
}

@Test
void ignoresEventsProducedByMirrorActor() {
    GitReceiveOrionEvent event = receiveEvent("team/project", GitMirrorActor.USER_NAME,
            ref("refs/heads/main", GitRefUpdateType.UPDATE, GitRefUpdateResult.OK));

    handler.onReceive(event);

    assertThat(queue.pending()).isEmpty();
}
```

**Step 2: Run tests to verify they fail**

Run:

```bash
mvn test -Pdev -q -pl core/git-mirror -am -Dtest=GitMirrorReceiveEventHandlerTest
```

Expected: FAIL because the handler does not exist.

**Step 3: Implement event handling**

Rules:

- Query `GitMirrorConfigStore.findEnabledByRepository(event.getRepositoryName())`.
- Use outbound refspecs only.
- Enqueue only refs with `GitRefUpdateResult.OK`.
- Do not enqueue rejected updates.
- Do not enqueue events whose `userName` is the mirror actor.
- For matching multi-ref pushes, enqueue one coalesced work item per mirror with
  the matching ref subset.
- Dedupe key: `outbound:<mirrorId>:<sortedMatchedRefs>`.

**Step 4: Run tests**

Run:

```bash
mvn test -Pdev -q -pl core/git-mirror -am -Dtest=GitMirrorReceiveEventHandlerTest
```

Expected: PASS.

#### Task 6: Define Remote Git Replication Client Boundary

**Files:**

- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/remote/GitRemoteReplicationClient.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/remote/GitRemoteTransport.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/remote/GitRemoteConnection.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/remote/GitRemoteRef.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/remote/GitRemoteRefSnapshot.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/remote/GitRemotePushRequest.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/remote/GitRemotePushResult.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/remote/GitRemoteFetchRequest.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/remote/GitRemoteFetchResult.java`
- Create: `core/git-mirror/src/test/java/pro/deta/orion/git/mirror/remote/ScriptedGitRemoteReplicationClient.java`
- Test: `core/git-mirror/src/test/java/pro/deta/orion/git/mirror/remote/GitRemoteReplicationClientContractTest.java`

**Step 1: Write failing contract tests for the scripted client**

```java
@Test
void pushReportsRemoteRejectAsTypedResult() {
    ScriptedGitRemoteReplicationClient client = new ScriptedGitRemoteReplicationClient()
            .rejectPush("refs/heads/main", "non-fast-forward");

    GitRemotePushResult result = client.push(pushRequest("refs/heads/main"));

    assertThat(result.refResults())
            .singleElement()
            .satisfies(ref -> {
                assertThat(ref.refName()).isEqualTo("refs/heads/main");
                assertThat(ref.status()).isEqualTo(GitRemoteRefStatus.REJECTED_NON_FAST_FORWARD);
            });
}
```

**Step 2: Run tests to verify they fail**

Run:

```bash
mvn test -Pdev -q -pl core/git-mirror -am -Dtest=GitRemoteReplicationClientContractTest
```

Expected: FAIL because the boundary does not exist.

**Step 3: Implement the boundary**

Keep this boundary provider-neutral:

```java
public interface GitRemoteReplicationClient {
    GitRemoteRefSnapshot listRefs(GitRemoteConnection connection, List<String> refPrefixes);
    GitRemotePushResult push(GitRemotePushRequest request);
    GitRemoteFetchResult fetch(GitRemoteFetchRequest request);
}
```

`GitRemoteConnection` must carry the selected transport explicitly:

```java
public enum GitRemoteTransport {
    HTTPS,
    SSH
}
```

The production implementation should call the native protocol client primitives
and dispatch to smart HTTP(S) or SSH transport based on
`GitRemoteConnection.transport()`. Until those modules exist, keep only the
interface and scripted tests in this module. Do not implement production JGit or
Git CLI fallbacks here.

**Step 4: Run tests**

Run:

```bash
mvn test -Pdev -q -pl core/git-mirror -am -Dtest=GitRemoteReplicationClientContractTest
```

Expected: PASS using the scripted test client.

#### Task 7: Add Local Repository Protocol Bridge

**Files:**

- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/local/LocalGitReplicationBridge.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/local/GitRepositoryLocalReplicationBridge.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/local/LocalGitRefSnapshot.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/local/LocalGitPackExport.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/local/LocalGitPackImport.java`
- Test: `core/git-mirror/src/test/java/pro/deta/orion/git/mirror/local/GitRepositoryLocalReplicationBridgeTest.java`

**Step 1: Write failing bridge tests**

Use a fake `GitRepository` that records upload/receive calls. The bridge should
not unwrap JGit internals.

```java
@Test
void exportsPackThroughGitRepositoryUpload() {
    RecordingGitRepository repository = new RecordingGitRepository("team/project");
    GitRepositoryProvider provider = repositoryProvider(repository);
    GitRepositoryLocalReplicationBridge bridge = new GitRepositoryLocalReplicationBridge(provider);

    bridge.exportPack(exportRequest("team/project", "refs/heads/main"));

    assertThat(repository.uploadCalls()).isEqualTo(1);
    assertThat(repository.unwrapCalls()).isZero();
}

@Test
void importsFetchedPackThroughGitRepositoryReceiveAsMirrorActor() {
    RecordingGitRepository repository = new RecordingGitRepository("team/project");

    bridge.importPack(importRequest("team/project", "refs/heads/main", GitMirrorActor.USER_NAME));

    assertThat(repository.receiveCalls()).isEqualTo(1);
    assertThat(repository.lastReceiveActor()).isEqualTo(GitMirrorActor.USER_NAME);
}
```

**Step 2: Run tests to verify they fail**

Run:

```bash
mvn test -Pdev -q -pl core/git-mirror -am -Dtest=GitRepositoryLocalReplicationBridgeTest
```

Expected: FAIL because the bridge does not exist.

**Step 3: Implement the bridge**

The bridge is the local side of replication:

- export local objects by driving `GitRepository.upload(...)`;
- import remote objects by driving `GitRepository.receive(...)`;
- build local protocol streams with the native protocol primitives;
- never call `repository.unwrap(Repository.class)` or depend on JGit;
- return typed results with safe diagnostics.

If the current native protocol primitives cannot yet generate the local
upload/receive stream, keep the bridge interface and fake implementation, then
make Task 10 depend on completing the protocol primitives plan.

**Step 4: Run tests**

Run:

```bash
mvn test -Pdev -q -pl core/git-mirror -am -Dtest=GitRepositoryLocalReplicationBridgeTest
```

Expected: PASS.

#### Task 8: Implement Outbound Push Worker Logic

**Files:**

- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/worker/GitMirrorWorker.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/worker/GitMirrorOutboundPushWorker.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/worker/GitMirrorSyncPlanner.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/worker/GitMirrorConflict.java`
- Test: `core/git-mirror/src/test/java/pro/deta/orion/git/mirror/worker/GitMirrorOutboundPushWorkerTest.java`

**Step 1: Write failing outbound worker tests**

```java
@Test
void pushesFastForwardRefToGithub() {
    queue.enqueue(outboundWork("mirror-1", "refs/heads/main"));
    remoteClient.setRemoteRef("refs/heads/main", oldId);
    localBridge.setLocalRef("team/project", "refs/heads/main", newId, oldId);

    worker.runOne();

    assertThat(remoteClient.pushRequests())
            .singleElement()
            .satisfies(request -> {
                assertThat(request.connection().remoteUri()).isEqualTo("https://github.com/acme/project.git");
                assertThat(request.refUpdates()).contains(refUpdate("refs/heads/main", oldId, newId));
            });
    assertThat(queue.runs()).singleElement()
            .satisfies(run -> assertThat(run.status()).isEqualTo(GitMirrorRunStatus.SUCCEEDED));
}

@Test
void pushesFastForwardRefToGithubOverSshWhenMirrorUsesSshRemote() {
    configStore.save(validGithubConfigBuilder()
            .remoteUri("ssh://git@github.com/acme/project.git")
            .credentialReference("secret:github/acme-project-deploy-key")
            .build());
    queue.enqueue(outboundWork("mirror-1", "refs/heads/main"));
    remoteClient.setRemoteRef("refs/heads/main", oldId);
    localBridge.setLocalRef("team/project", "refs/heads/main", newId, oldId);

    worker.runOne();

    assertThat(remoteClient.pushRequests())
            .singleElement()
            .satisfies(request -> {
                assertThat(request.connection().transport()).isEqualTo(GitRemoteTransport.SSH);
                assertThat(request.connection().remoteUri()).isEqualTo("ssh://git@github.com/acme/project.git");
                assertThat(request.refUpdates()).contains(refUpdate("refs/heads/main", oldId, newId));
            });
}

@Test
void doesNotPushNonFastForwardWhenPolicyIsFastForwardOnly() {
    queue.enqueue(outboundWork("mirror-1", "refs/heads/main"));
    remoteClient.setRemoteRef("refs/heads/main", divergentRemoteId);
    localBridge.setLocalRef("team/project", "refs/heads/main", newId, oldId);

    worker.runOne();

    assertThat(remoteClient.pushRequests()).isEmpty();
    assertThat(queue.runs()).singleElement()
            .satisfies(run -> assertThat(run.status()).isEqualTo(GitMirrorRunStatus.FAILED_PERMANENT));
}
```

**Step 2: Run tests to verify they fail**

Run:

```bash
mvn test -Pdev -q -pl core/git-mirror -am -Dtest=GitMirrorOutboundPushWorkerTest
```

Expected: FAIL because the worker does not exist.

**Step 3: Implement outbound worker**

Algorithm:

1. Lease next `OUTBOUND_PUSH` work item.
2. Load mirror config and reject if disabled.
3. Resolve matched outbound refspecs.
4. List remote refs through `GitRemoteReplicationClient`.
5. Read local refs through `LocalGitReplicationBridge`.
6. Build a sync plan with create/update/no-op/conflict decisions.
7. Reject force/delete/tag rewrite unless policy allows it.
8. Export local pack for required object ids.
9. Push expected old id and new id to GitHub.
10. Record run result and retry state.

Retry only retryable transport failures. Do not retry policy failures or
non-fast-forward conflicts.

**Step 4: Run tests**

Run:

```bash
mvn test -Pdev -q -pl core/git-mirror -am -Dtest=GitMirrorOutboundPushWorkerTest
```

Expected: PASS.

#### Task 9: Implement Inbound Fetch Worker Logic

**Files:**

- Modify: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/worker/GitMirrorWorker.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/worker/GitMirrorInboundFetchWorker.java`
- Test: `core/git-mirror/src/test/java/pro/deta/orion/git/mirror/worker/GitMirrorInboundFetchWorkerTest.java`

**Step 1: Write failing inbound worker tests**

```java
@Test
void fetchesFastForwardRefFromGithubIntoOrion() {
    queue.enqueue(inboundWork("mirror-1", "refs/heads/main"));
    remoteClient.setRemoteRef("refs/heads/main", newId, oldId);
    localBridge.setLocalRef("team/project", "refs/heads/main", oldId);

    worker.runOne();

    assertThat(remoteClient.fetchRequests())
            .singleElement()
            .satisfies(request -> assertThat(request.refNames()).containsExactly("refs/heads/main"));
    assertThat(localBridge.importRequests())
            .singleElement()
            .satisfies(request -> assertThat(request.actor()).isEqualTo(GitMirrorActor.USER_NAME));
}

@Test
void recordsConflictWhenGithubWouldOverwriteLocalWork() {
    queue.enqueue(inboundWork("mirror-1", "refs/heads/main"));
    remoteClient.setRemoteRef("refs/heads/main", githubCommit);
    localBridge.setLocalRef("team/project", "refs/heads/main", localDivergentCommit);

    worker.runOne();

    assertThat(localBridge.importRequests()).isEmpty();
    assertThat(queue.runs()).singleElement()
            .satisfies(run -> assertThat(run.status()).isEqualTo(GitMirrorRunStatus.FAILED_PERMANENT));
}
```

**Step 2: Run tests to verify they fail**

Run:

```bash
mvn test -Pdev -q -pl core/git-mirror -am -Dtest=GitMirrorInboundFetchWorkerTest
```

Expected: FAIL because inbound worker does not exist.

**Step 3: Implement inbound worker**

Algorithm:

1. Lease next `INBOUND_FETCH` work item.
2. Load mirror config and reject if disabled.
3. Resolve inbound refspecs.
4. List GitHub refs.
5. Read local refs.
6. Build a fast-forward-only sync plan.
7. Fetch required objects from GitHub upload-pack.
8. Import fetched pack through local receive-pack bridge using
   `GitMirrorActor.USER_NAME`.
9. Record run result and retry state.

Do not update local refs outside configured inbound refspec destinations.

**Step 4: Run tests**

Run:

```bash
mvn test -Pdev -q -pl core/git-mirror -am -Dtest=GitMirrorInboundFetchWorkerTest
```

Expected: PASS.

#### Task 10: Add GitHub HTTPS and SSH Credential Profile

**Files:**

- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/github/GitHubMirrorProfile.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/github/GitHubCredentialResolver.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/github/GitHubRemoteUriValidator.java`
- Test: `core/git-mirror/src/test/java/pro/deta/orion/git/mirror/github/GitHubMirrorProfileTest.java`

**Step 1: Write failing GitHub profile tests**

```java
@Test
void buildsHttpsConnectionWithoutExposingToken() {
    GitHubMirrorProfile profile = new GitHubMirrorProfile(secretResolver);
    secretResolver.put("secret:github/acme-project", "ghp_test_token");

    GitRemoteConnection connection = profile.connectionFor(validGithubConfig());

    assertThat(connection.remoteUri()).isEqualTo("https://github.com/acme/project.git");
    assertThat(connection.transport()).isEqualTo(GitRemoteTransport.HTTPS);
    assertThat(connection.safeDisplay()).isEqualTo("github.com/acme/project.git");
    assertThat(connection.toString()).doesNotContain("ghp_test_token");
}

@Test
void buildsSshConnectionWithoutExposingPrivateKeyOrPassphrase() {
    GitHubMirrorProfile profile = new GitHubMirrorProfile(secretResolver);
    secretResolver.putSshCredential(
            "secret:github/acme-project-deploy-key",
            "file:/run/orion/secrets/github-deploy-key",
            "env:GITHUB_DEPLOY_KEY_PASSPHRASE",
            "file:/run/orion/known_hosts");
    GitMirrorConfig config = validGithubConfigBuilder()
            .remoteUri("git@github.com:acme/project.git")
            .credentialReference("secret:github/acme-project-deploy-key")
            .build();

    GitRemoteConnection connection = profile.connectionFor(config);

    assertThat(connection.remoteUri()).isEqualTo("git@github.com:acme/project.git");
    assertThat(connection.transport()).isEqualTo(GitRemoteTransport.SSH);
    assertThat(connection.safeDisplay()).isEqualTo("github.com/acme/project.git");
    assertThat(connection.toString()).doesNotContain("BEGIN OPENSSH PRIVATE KEY");
    assertThat(connection.toString()).doesNotContain("GITHUB_DEPLOY_KEY_PASSPHRASE");
}

@Test
void rejectsNonGithubHostForGithubProvider() {
    GitMirrorConfig config = validGithubConfigBuilder()
            .remoteUri("https://gitlab.com/acme/project.git")
            .build();

    assertThatThrownBy(() -> profile.connectionFor(config))
            .isInstanceOf(GitMirrorConfigurationException.class);
}
```

**Step 2: Run tests to verify they fail**

Run:

```bash
mvn test -Pdev -q -pl core/git-mirror -am -Dtest=GitHubMirrorProfileTest
```

Expected: FAIL because GitHub profile classes do not exist.

**Step 3: Implement GitHub profile**

Rules:

- Allow only these GitHub remote URI shapes for this candidate:
  - `https://github.com/<owner>/<repo>.git`;
  - `git@github.com:<owner>/<repo>.git`;
  - `ssh://git@github.com/<owner>/<repo>.git`.
- Resolve credentials through the secret-reference boundary from
  [the secret-reference task](../02_hierarchical-orion-configuration/05_secret-reference-credential-management.md).
- If the secret store is not implemented yet, provide a narrow adapter that
  supports `env:NAME` and `file:/path` references for this feature, with the same
  safe-display rules.
- Use bearer/basic token auth as required by the smart HTTP client transport.
- Use private-key auth as required by the SSH client transport.
- Require SSH host key verification. The SSH credential must include a
  known-hosts reference or a provider-pinned GitHub host-key policy. Do not
  support trust-all host key behavior.
- Never include token values, private-key bytes, passphrases, known-hosts
  content, auth headers, or SSH command lines with credentials in exceptions,
  logs, queue items, run records, or `toString()`.

**Step 4: Run tests**

Run:

```bash
mvn test -Pdev -q -pl core/git-mirror -am -Dtest=GitHubMirrorProfileTest
```

Expected: PASS.

#### Task 11: Add Manual Sync API and GitHub Webhook Intake

**Files:**

- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/webhook/GitHubWebhookVerifier.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/webhook/GitHubWebhookEventMapper.java`
- Create: `net/http-core/src/main/java/pro/deta/orion/transport/http/OrionGitHubMirrorWebhookRoute.java`
- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/OrionHttpModule.java`
- Create: `net/http-core/src/test/java/pro/deta/orion/transport/http/OrionGitHubMirrorWebhookRouteTest.java`
- Test: `core/git-mirror/src/test/java/pro/deta/orion/git/mirror/webhook/GitHubWebhookVerifierTest.java`

**Step 1: Write failing webhook verifier tests**

```java
@Test
void acceptsValidGithubSha256Signature() {
    GitHubWebhookVerifier verifier = new GitHubWebhookVerifier(secretResolver);
    secretResolver.put("secret:github/webhook", "webhook-secret");

    boolean valid = verifier.verify(
            "secret:github/webhook",
            "sha256=" + hmacSha256("webhook-secret", payload),
            payload);

    assertThat(valid).isTrue();
}

@Test
void rejectsMissingSignature() {
    assertThat(verifier.verify("secret:github/webhook", null, payload)).isFalse();
}
```

**Step 2: Write failing route test**

```java
@Test
void verifiedPushWebhookEnqueuesInboundFetch() throws Exception {
    OrionGitHubMirrorWebhookRoute route = routeWith(validMirrorConfig());
    HttpServletRequest request = githubPushWebhookRequest("team/project", "refs/heads/main");

    OrionHttpResponse response = route.service(request);

    assertThat(response.status()).isEqualTo(202);
    assertThat(queue.pending()).singleElement()
            .satisfies(item -> assertThat(item.kind()).isEqualTo(GitMirrorWorkKind.INBOUND_FETCH));
}
```

**Step 3: Run tests to verify they fail**

Run:

```bash
mvn test -Pdev -q -pl core/git-mirror,net/http-core -am -Dtest=GitHubWebhookVerifierTest,OrionGitHubMirrorWebhookRouteTest
```

Expected: FAIL because webhook classes and route do not exist.

**Step 4: Implement webhook intake**

Rules:

- Route path: `/api/git-mirrors/github/webhook`.
- Verify `X-Hub-Signature-256` with HMAC SHA-256.
- Use configured webhook secret reference from mirror config.
- Validate GitHub repository identity against mirror `remoteUri`.
- Accept only GitHub `push` events in the first candidate.
- Enqueue inbound work only; do not fetch inside the HTTP request.
- Return `202` for accepted deliveries, `401` or `403` for invalid signatures,
  and sanitized `400` for malformed payloads.
- Store delivery id in the work item for dedupe and diagnostics.

Add a manual admin route only if the existing admin route pattern already has a
clear place for it. Otherwise expose the manual enqueue operation as a service
method and add the HTTP admin route in a follow-up.

**Step 5: Run tests**

Run:

```bash
mvn test -Pdev -q -pl core/git-mirror,net/http-core -am -Dtest=GitHubWebhookVerifierTest,OrionGitHubMirrorWebhookRouteTest
```

Expected: PASS.

#### Task 12: Wire Mirror Runtime and Lifecycle

**Files:**

- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/GitMirrorService.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/GitMirrorStateMachine.java`
- Create: `core/git-mirror/src/main/java/pro/deta/orion/git/mirror/GitMirrorModule.java`
- Modify: `core/bootstrap/pom.xml`
- Modify: `core/bootstrap/src/main/java/pro/deta/orion/component/OrionRuntimeModule.java`
- Modify: `core/bootstrap/src/main/java/pro/deta/orion/component/OrionRuntimeStateMachine.java`
- Test: `core/git-mirror/src/test/java/pro/deta/orion/git/mirror/GitMirrorServiceTest.java`
- Test: `core/bootstrap/src/test/java/pro/deta/orion/component/OrionRuntimeModuleTest.java`

**Step 1: Write failing lifecycle tests**

```java
@Test
void startRegistersReceiveEventHandlerAndStartsWorker() {
    GitMirrorService service = new GitMirrorService(eventManager, handler, worker);

    service.onStart();

    assertThat(eventManager.handlersFor(GitReceiveOrionEvent.class)).hasSize(1);
    assertThat(worker.started()).isTrue();
}

@Test
void stopStopsWorkerBeforeReturning() {
    service.onStart();

    service.onStop();

    assertThat(worker.running()).isFalse();
}
```

**Step 2: Run tests to verify they fail**

Run:

```bash
mvn test -Pdev -q -pl core/git-mirror,core/bootstrap -am -Dtest=GitMirrorServiceTest,OrionRuntimeModuleTest
```

Expected: FAIL because service and Dagger wiring do not exist.

**Step 3: Implement lifecycle**

`GitMirrorService` responsibilities:

- register `GitMirrorReceiveEventHandler` on start;
- run a background worker loop only when mirror config is enabled;
- support manual `enqueueInboundFetch(mirrorId, refs)` and
  `enqueueOutboundPush(mirrorId, refs)` service methods;
- stop the worker cleanly;
- expose safe health state: enabled, running, pending count, failed count.

Add `GitMirrorStateMachine` as a normal service lifecycle adapter.

Include `GitMirrorModule` in bootstrap Dagger wiring. Add the mirror state
machine to the runtime aggregate after event manager and repository provider are
available, before external transports are treated as fully ready.

**Step 4: Run tests**

Run:

```bash
mvn test -Pdev -q -pl core/git-mirror,core/bootstrap -am -Dtest=GitMirrorServiceTest,OrionRuntimeModuleTest
```

Expected: PASS.

#### Task 13: Add End-to-End Local Replication Tests

**Files:**

- Create: `tests/integration-test/src/test/java/pro/deta/orion/test/integration/git/GitMirrorReplicationIT.java`
- Modify: `tests/integration-test/pom.xml` if it does not already depend on `core/git-mirror`

**Step 1: Write failing integration tests**

Use local in-process/scripted remotes first. Do not require network or a real
GitHub repository.

```java
@Test
void pushToOrionReplicatesCommitToRemote() {
    GitMirrorFixture fixture = GitMirrorFixture.local();
    fixture.configureGithubMirror("team/project", "refs/heads/main");

    fixture.pushCommitToOrion("team/project", "refs/heads/main", "README.md", "hello");
    fixture.runMirrorWorkerUntilIdle();

    assertThat(fixture.remoteFile("refs/heads/main", "README.md")).isEqualTo("hello");
}

@Test
void remoteCommitIsFetchedIntoOrionWithoutEchoPush() {
    GitMirrorFixture fixture = GitMirrorFixture.local();
    fixture.configureGithubMirror("team/project", "refs/heads/main");

    fixture.pushCommitToRemote("refs/heads/main", "README.md", "from github");
    fixture.enqueueInboundFetch("mirror-1", "refs/heads/main");
    fixture.runMirrorWorkerUntilIdle();

    assertThat(fixture.orionFile("team/project", "refs/heads/main", "README.md"))
            .isEqualTo("from github");
    assertThat(fixture.outboundWorkCreatedByMirrorActor()).isFalse();
}
```

**Step 2: Run tests to verify they fail**

Run:

```bash
mvn test -Pdev -q -pl tests/integration-test -am -Dtest=GitMirrorReplicationIT
```

Expected: FAIL until worker and bridge are complete.

**Step 3: Implement fixtures and fix integration gaps**

Fixture requirements:

- create an Orion repository through existing test support;
- create a local/scripted remote that behaves like GitHub over the remote client
  boundary;
- configure an enabled bidirectional mirror;
- push local commits;
- enqueue inbound fetch;
- drain worker queue deterministically;
- inspect both repositories without using a real network.

If a real smart HTTP client is available by this point, add a second test using a
local Jetty smart HTTP Git route. Keep real github.com compatibility as a manual
or separately gated test, not a routine unit test.

**Step 4: Run tests**

Run:

```bash
mvn test -Pdev -q -pl tests/integration-test -am -Dtest=GitMirrorReplicationIT
```

Expected: PASS.

#### Task 14: Final Verification

**Files:**

- No new files unless documentation gaps are found.

**Step 1: Run focused module tests**

Run:

```bash
mvn test -Pdev -q -pl core/git-mirror,net/http-core,core/bootstrap -am
```

Expected: PASS.

**Step 2: Run integration test**

Run:

```bash
mvn test -Pdev -q -pl tests/integration-test -am -Dtest=GitMirrorReplicationIT
```

Expected: PASS.

**Step 3: Run routine development verification**

Run:

```bash
mvn verify -Pdev
```

Expected: PASS.

**Step 4: Check for secret leakage**

Search:

```bash
rg -n "ghp_|github_pat_|Authorization|X-Hub-Signature|webhook-secret" core/git-mirror net/http-core tests
```

Expected: no committed token values, no raw authorization values in logs or test
fixtures. Test-only literals such as `"webhook-secret"` are acceptable only in
unit tests and must not appear in production default config.

Search:

```bash
rg -n "BEGIN .*PRIVATE KEY|OPENSSH PRIVATE KEY|known_hosts|StrictHostKeyChecking=no|UserKnownHostsFile=/dev/null" core/git-mirror net/http-core tests
```

Expected: no committed private keys, no raw known-hosts content in production
config, and no SSH trust-all behavior. Test-only fixture names such as
`known_hosts` are acceptable only when they refer to a temporary file or test
resource with non-secret fixture data.

### Follow-Up Plans

After this candidate works:

- Add GitHub REST repository creation and webhook installation.
- Add explicit admin API/UI for mirror config CRUD and manual retry.
- Add GitLab profile on the same generic Git mirror core.
- Add force/delete/tag policies with protected-ref integration.
- Add source attribution stronger than `userName` if Git receive events gain a
  durable actor/source field.
- Add real github.com compatibility tests gated behind explicit credentials and
  not run by routine `mvn test -Pdev`.

---

## GitHub and GitLab Repository Mirroring

### Goal

Mirror repositories between Orion and GitHub or GitLab while preserving clear
ownership, credentials, ref update rules, and failure recovery.

This should remain one of the later roadmap items because it depends on stronger
token management, durable event handling, repository update events, and clear
conflict rules.

### Current State

Orion can serve Git repositories through native Git, SSH, and HTTP routes.

`GitInternalService` publishes `GitReceiveOrionEvent` after receive-pack
operations, including repository name, user name, ref updates, update type, and
result.

Repository access control already distinguishes read, write, and create grants.
The repository provider can open and create local Git repositories.

There is no mirror configuration model, no remote credential model, no outbound
Git worker, no webhook endpoint, no durable retry queue, and no conflict policy.

### Non-Goals

Do not start with full bidirectional mirroring.

Do not couple this feature to one provider's REST API when Git transport can
solve the first push and fetch flows.

Do not store GitHub or GitLab tokens in plaintext configuration.

Do not mirror all refs by default without explicit refspec rules.

Do not trigger builds from external webhooks until mirroring and build triggers
have separate durable event paths.

### Scope

Add a mirror configuration model:

- Orion repository name;
- remote provider;
- remote URL;
- direction;
- refspecs;
- credential reference;
- enabled flag;
- last sync state;
- last successful revision;
- failure count and next retry time.

Support directions deliberately:

- outbound only: Orion pushes selected refs to remote;
- inbound only: Orion fetches selected refs from remote;
- bidirectional: later, only after conflict rules are explicit.

Use a durable mirror queue. Git receive events can enqueue outbound sync work,
but the sync operation itself must survive process restart and retry provider
failures.

Use a credential reference that can later be backed by the application-token or
secret-management plan.

### Phased Plan

Phase 1: Mirror model and validation.

Define provider type, remote URL validation, direction, refspecs, and conflict
policy. Add tests for invalid URLs, unsafe refspecs, duplicate mirrors, and
disabled mirrors.

Phase 2: Credential references.

Add mirror credential records or references without storing raw provider tokens
inside mirror config. Start with test credentials and local Git remotes before
integrating hosted providers.

Phase 3: Outbound local mirror worker.

Implement outbound push to a local or file URL remote from an Orion repository.
This proves refspec handling, retries, state updates, and error recording
without provider API complexity.

Phase 4: Outbound provider support.

Support HTTPS or SSH remotes for GitHub and GitLab using configured
credentials. Keep provider REST API usage minimal unless repository creation,
webhook management, or token validation requires it.

Phase 5: Inbound fetch.

Add scheduled or manually triggered inbound fetch. Decide how fetched remote
refs map into Orion refs. Avoid overwriting local work without an explicit
policy.

Phase 6: Webhooks.

Add provider webhook endpoints only after inbound fetch works manually. Verify
webhook signatures, map provider repository identity to mirror config, and
enqueue fetch work rather than performing sync inline.

Phase 7: Bidirectional policy.

Design bidirectional mirroring only after outbound and inbound paths are stable.
The policy must define non-fast-forward handling, force pushes, deleted refs,
tag updates, and simultaneous changes.

### Open Questions

Should Orion create remote repositories, or only mirror to repositories that
already exist?

Should each mirror sync all branches and tags, or require explicit refspecs?

How should force pushes and deleted refs be mirrored?

Should mirror failures block Git pushes, or only record asynchronous sync
failures?

Where should provider credentials live before secret management exists?

Should provider webhooks be configured by Orion automatically or documented as
manual setup?

### Verification

Cover at least these cases:

- mirror config rejects invalid remote URLs and unsafe refspecs;
- disabled mirror config never enqueues sync work;
- Git receive event enqueues outbound sync only for matching refs;
- outbound mirror pushes expected refs to a local test remote;
- failed push records failure reason and schedules retry;
- repeated events coalesce or run idempotently without duplicate state;
- deleted refs and tag updates follow explicit policy;
- inbound fetch imports only configured refs;
- webhook endpoint rejects missing or invalid signatures;
- provider credentials are never logged or returned from admin APIs.

---

## Git Mirror Queue and Provider Webhooks

### Goal

Add a durable mirror queue and provider webhook intake layer for GitHub/GitLab
repository mirroring.

This plan expands the existing mirroring roadmap item with the operational parts
that must be correct before mirroring can run unattended:

- durable mirror work records;
- outbound and inbound mirror job scheduling;
- idempotent retries and coalescing;
- provider webhook signature verification;
- mapping provider events to mirror configs;
- safe worker locking;
- sync result storage;
- admin-triggered dry-run and retry operations;
- observability and redaction.

The first implementation should support manual and event-driven queueing for
configured mirrors. Provider webhooks should enqueue work only; they should not
run Git fetch/push inline inside the HTTP request.

### Current State

the provider-mirroring plan embedded below defines the
high-level mirroring feature: mirror config, refspecs, directions, credential
references, outbound local worker, provider support, inbound fetch, webhooks, and
bidirectional policy. It identifies missing durable queue, webhook endpoint, and
conflict policy, but it does not define the queue record model or worker
semantics.

[the repository event task](../11_git/12_repository-events-reflog-audit.md) defines Git
operation/ref update events that mirror synchronization can consume. This plan
uses those events as enqueue inputs; it does not redefine canonical Git event
schemas.

[the secret-reference task](../02_hierarchical-orion-configuration/05_secret-reference-credential-management.md) defines secret
references and credential resolution. Mirror config should store credential
references and webhook secret references, never raw provider tokens.

the integrated Smart HTTP transport and
the integrated SSH transport define outbound Git
transport adapters. Mirror workers should call those transport clients rather
than implementing Git protocol directly.

[the protected-ref policy task](../11_git/07_access-policy-and-protected-refs.md) defines protected
ref and hidden ref policy. Mirroring must use explicit mirror actor policy for
force pushes, deletes, tags, protected refs, and hidden refs.

There is currently no detailed mirror queue model, webhook request model,
deduplication key, lease protocol, retry policy, or durable run report shape.

### Non-Goals

Do not implement Git fetch, Git push, pack parsing, or protocol transport in this
plan. Mirror workers call native remote Git clients.

Do not implement full bidirectional conflict resolution first. Bidirectional
mirrors should stay disabled until explicit conflict policy is implemented.

Do not create remote repositories through provider REST APIs in the first queue
milestone.

Do not trigger builds directly from provider webhooks. Webhooks enqueue mirror
sync work only.

Do not store raw provider credentials, webhook secrets, auth headers, or remote
URLs with embedded credentials in queue records or logs.

Do not block local Git pushes on mirror completion by default. Local receive
events enqueue async work unless a later policy explicitly requests synchronous
mirroring.

Do not trust provider webhook payloads without signature verification and mirror
config matching.

Do not depend on JGit in production code.

### Mirror Directions

Support directions deliberately.

Outbound mirror:

- Orion is source of truth for selected refs;
- local Git ref update events enqueue push jobs;
- worker pushes selected refs to remote.

Inbound mirror:

- remote provider is source for selected refs;
- scheduled jobs, manual jobs, or verified webhooks enqueue fetch jobs;
- worker fetches selected refs and updates Orion according to policy.

Bidirectional mirror:

- later only;
- requires explicit conflict rules;
- requires loop prevention and source attribution;
- should not be enabled by generic queue behavior.

The queue model should support all directions, but initial worker behavior should
enable outbound and inbound separately.

### Mirror Config Additions

Extend mirror config with queue-facing fields.

Candidate fields:

- mirror id;
- Orion repository id;
- direction;
- enabled flag;
- remote provider;
- remote URL safe display;
- credential reference;
- webhook secret reference;
- inbound refspecs;
- outbound refspecs;
- force/delete/tag policy;
- protected ref policy mode;
- sync schedule;
- retry policy id;
- max concurrent runs;
- last enqueued event id;
- last successful run id;
- last successful local ref snapshot;
- last successful remote observation;
- disabled reason.

Mirror config must not contain raw credentials.

Changes to mirror config should invalidate or re-evaluate pending work when the
change affects direction, refspecs, remote identity, credentials, or policy.

### Queue Record Model

Introduce durable work records.

Candidate value objects:

- `GitMirrorWorkItem`;
- `GitMirrorRun`;
- `GitMirrorQueueStore`;
- `GitMirrorWorkerLease`;
- `GitMirrorTrigger`;
- `GitMirrorSyncPlan`;
- `GitMirrorSyncResult`;
- `GitMirrorRetryPolicy`;
- `GitMirrorProviderEvent`;
- `GitMirrorWebhookDelivery`;
- `GitMirrorRefMapping`;
- `GitMirrorConflictRecord`.

Work item fields:

- work id;
- mirror id;
- repository id;
- direction;
- trigger kind;
- trigger id;
- source event id when applicable;
- provider delivery id when applicable;
- ref names or refspec subset;
- requested old/new ids when known;
- priority;
- dedupe key;
- state;
- attempt count;
- next attempt time;
- lease owner;
- lease expiry;
- created time;
- updated time;
- safe diagnostics.

States:

- `PENDING`;
- `LEASED`;
- `RUNNING`;
- `SUCCEEDED`;
- `FAILED_RETRYABLE`;
- `FAILED_PERMANENT`;
- `CANCELLED`;
- `SUPERSEDED`;
- `PAUSED`;
- `DEAD_LETTER`.

Run records should be append-only summaries for each attempt.

### Trigger Kinds

Supported trigger kinds:

- local Git ref update event;
- manual admin request;
- scheduled sync;
- provider webhook;
- retry;
- startup recovery;
- config changed;
- maintenance repair.

The trigger kind affects priority, dedupe, and diagnostics, but not Git protocol
correctness.

Manual requests should be able to force a new work item even when a similar item
already exists, but they should still not violate mirror policy.

### Dedupe And Coalescing

Repeated events should not create unbounded duplicate work.

Dedupe key inputs:

- mirror id;
- direction;
- refspec group or ref name;
- trigger class;
- local source event id range where applicable;
- provider delivery id where applicable.

Coalescing rules:

- multiple local ref updates on the same branch can coalesce into one outbound
  push of the latest allowed state;
- multiple provider webhooks for the same remote ref can coalesce into one
  inbound fetch;
- manual forced jobs should not be silently coalesced unless caller asks for it;
- failed retryable jobs can be superseded by newer work that covers the same
  refspec;
- delete events must not be dropped unless a newer event makes the final desired
  state clear.

Coalescing must preserve enough source event ids for audit.

### Lease And Worker Model

Workers claim work through leases.

Lease requirements:

- conditional claim from `PENDING` or retryable state;
- lease owner id;
- lease expiry;
- heartbeat or extension for long jobs;
- stale lease recovery;
- maximum attempt count;
- worker shutdown releases or lets lease expire;
- no two workers run the same work item concurrently.

Worker flow:

1. Load enabled mirror config.
2. Claim due work item.
3. Resolve credential references.
4. Build sync plan from current repository and remote observations.
5. Execute Git operation through native transport.
6. Validate result.
7. Store run record.
8. Mark work item succeeded, retryable, permanent failure, or superseded.
9. Emit mirror event/audit record.

Worker leases should be backend-neutral: local file store first, S3-compatible or
database-backed later if needed.

### Retry Policy

Retry must distinguish transient and permanent failures.

Retryable examples:

- remote transport timeout;
- remote temporary unavailable;
- rate limit with retry-after;
- credential provider temporarily unavailable;
- stale local snapshot before operation starts;
- queue lease conflict;
- S3/local storage transient failure.

Permanent examples:

- invalid mirror config;
- credential denied;
- remote auth rejected;
- unsafe refspec;
- protected ref policy denied;
- missing repository where creation is disabled;
- webhook signature invalid;
- unsupported remote capability required by policy.

Policy fields:

- max attempts;
- initial delay;
- max delay;
- exponential backoff factor;
- jitter;
- dead-letter threshold;
- retry-after support;
- manual retry allowed flag.

Retries should preserve run history and safe failure diagnostics.

### Outbound Mirror Worker

Outbound jobs push selected local refs to remote.

Inputs:

- local repository id;
- source ref update events;
- mirror outbound refspecs;
- remote URL and credential reference;
- force/delete/tag policy;
- current local ref snapshot;
- remote advertised refs.

Flow:

1. Resolve mirror config and credentials.
2. Load local ref snapshot.
3. Map local refs to remote refs using outbound refspecs.
4. Filter by policy and source events.
5. Query remote refs through upload-pack/receive-pack advertisement where needed.
6. Build push commands with expected old ids.
7. Build or select pack objects for missing remote objects.
8. Execute receive-pack push through smart HTTP or SSH transport.
9. Parse report-status.
10. Record per-ref result.
11. Mark work item outcome.

Outbound mirror should not push every ref by default. Only configured refspecs
are eligible.

### Inbound Mirror Worker

Inbound jobs fetch remote refs and update Orion refs.

Inputs:

- mirror inbound refspecs;
- provider webhook hints or scheduled trigger;
- remote advertised refs;
- local destination refs;
- conflict policy;
- protected ref policy;
- credentials.

Flow:

1. Resolve mirror config and credentials.
2. Discover remote refs.
3. Map remote refs to local refs.
4. Compare with local destination refs.
5. Reject or plan non-fast-forward updates according to policy.
6. Fetch required objects through native remote fetch.
7. Validate object graph.
8. Publish objects to local repository storage.
9. Update refs through native ref store policy.
10. Emit ref update events with mirror actor.
11. Record per-ref result.

Inbound mirror must not overwrite local work unless policy permits it.

### Webhook Intake

Provider webhooks should be thin HTTP handlers.

Responsibilities:

- validate method and content type;
- read bounded body;
- identify provider from route or headers;
- verify signature before parsing trusted fields;
- parse delivery id;
- parse repository identity;
- parse event type;
- map repository identity to mirror config;
- create or coalesce queue work;
- return provider-appropriate status quickly.

Do not run Git fetch or push inline.

Webhook handler should store a bounded sanitized delivery record for diagnostics,
not raw secrets. Raw payload storage should be disabled by default or redacted.

### GitHub Webhooks

Initial GitHub support:

- `X-GitHub-Event`;
- `X-GitHub-Delivery`;
- `X-Hub-Signature-256`;
- JSON body;
- push event first.

Signature:

- HMAC-SHA256 over raw request body;
- secret from mirror webhook secret reference;
- constant-time comparison;
- reject missing signature when secret is configured;
- reject invalid signature before enqueue.

Push event mapping:

- provider repository id or full name;
- ref;
- before id;
- after id;
- created/deleted/forced flags;
- sender safe identity;
- delivery id.

Unsupported events can return success with ignored status or explicit unsupported
depending on provider expectations and operator preference.

### GitLab Webhooks

Initial GitLab support:

- `X-Gitlab-Event`;
- `X-Gitlab-Token` or supported signature header depending on provider config;
- JSON body;
- push hook first.

Token/signature:

- prefer signed/HMAC verification if configured and supported;
- support shared token verification when that is the provider mechanism;
- compare secrets safely;
- reject missing/invalid token before enqueue.

Push hook mapping:

- project id or path;
- ref;
- before id;
- after id;
- checkout sha when present;
- user safe identity;
- delivery id if available or generated idempotency key.

### Provider Identity Mapping

Webhook repository identity must match mirror config.

Allowed match keys:

- provider type;
- provider repository id;
- owner/name or namespace/path;
- configured remote URL canonical form;
- optional webhook endpoint id.

Rules:

- do not trust only display names;
- handle repository renames only after explicit config update or provider id
  match;
- reject events that map to multiple mirrors unless all matching mirrors are
  intended and enabled;
- do not reveal configured mirror names in unauthorized webhook responses.

The first implementation can require exact configured provider id or exact remote
URL canonical match.

### Refspec And Policy

Refspec handling should be shared by inbound and outbound mirror workers.

Rules:

- reject unsafe refspecs at config time;
- support explicit branch mapping first;
- support wildcard mappings only after tests cover ambiguity;
- reject mapping to hidden/internal refs unless mirror actor policy allows it;
- reject protected ref updates unless mirror policy allows them;
- handle deletes explicitly;
- handle tags explicitly;
- handle force updates explicitly.

Mirror policy should decide desired state before Git transport runs.

### Conflict Handling

Start with simple conflict behavior.

Outbound:

- if remote ref changed unexpectedly, fail retryable or conflict depending on
  policy;
- do not force unless configured;
- do not delete unless configured.

Inbound:

- if local ref changed unexpectedly, fail conflict;
- do not force unless configured;
- do not overwrite local-only commits without explicit policy.

Bidirectional:

- disabled first;
- later requires source attribution, last synced pair, and conflict records.

Conflict records should be visible to admin diagnostics and safe logs.

### Loop Prevention

Mirroring can create loops when Orion and remote both emit events.

Initial loop prevention:

- record mirror actor on ref update events;
- do not enqueue outbound work for inbound mirror updates unless policy says to
  propagate;
- record last successful local and remote ids per ref mapping;
- coalesce provider webhook events that match a just-completed outbound push;
- preserve source operation id in mirror run record.

Provider-specific loop prevention can be added later if APIs expose delivery or
actor metadata reliably.

### Queue Storage

Start with local durable storage.

Local layout example:

```text
<baseDir>/mirrors/
  configs/<mirror-id>.json
  queue/<work-id>.json
  runs/<mirror-id>/<run-id>.json
  deliveries/<provider>/<delivery-id>.json
```

Requirements:

- explicit schema version;
- atomic writes;
- startup reload;
- stale lease recovery;
- bounded completed work retention;
- dead-letter retention;
- corruption diagnostics;
- no raw credentials.

S3 or database-backed queue storage can be added later if multi-node workers are
needed.

### Admin Operations

Add admin operations after queue model is stable.

Useful operations:

- list mirrors;
- list pending work;
- list recent runs;
- enqueue manual sync;
- dry-run sync plan;
- retry failed work;
- cancel pending work;
- pause/resume mirror;
- disable mirror;
- inspect dead-letter item;
- mark superseded after operator confirmation.

Admin responses must include safe remote URLs and credential references only.

Dry-run should compute mapping and planned Git operations without pushing,
fetching objects, or updating refs.

### Security

Webhook and queue security rules:

- verify signatures before trusting payload fields;
- rate-limit webhook endpoints;
- bound request body size;
- use secret references for webhook secrets;
- do not log raw webhook secrets or auth headers;
- do not expose hidden refs in webhook responses;
- require admin permission for manual enqueue/retry/cancel;
- use mirror actor identity for Git policy decisions;
- keep provider payload storage redacted by default.

Provider webhooks should return generic responses for invalid signatures. Detailed
failure reasons go to internal diagnostics only.

### Observability

Metrics:

- work items created by trigger kind;
- work items coalesced;
- work item state counts;
- worker lease conflicts;
- worker run duration;
- retry count by reason;
- dead-letter count;
- webhook deliveries accepted;
- webhook signature failures;
- webhook mapping failures;
- outbound refs pushed;
- inbound refs fetched;
- conflicts detected;
- bytes fetched/pushed when available.

Logs:

- enqueue decisions;
- coalescing decisions;
- worker start/finish;
- permanent failures;
- webhook verification failures;
- provider mapping failures;
- admin manual actions.

Do not log credentials, raw provider tokens, webhook secrets, or object contents.

### Error Model

Typed failures:

- mirror disabled;
- mirror config missing;
- invalid refspec;
- credential resolution failed;
- remote auth failed;
- remote authorization denied;
- remote unavailable;
- transport unsupported;
- protected ref denied;
- hidden ref denied;
- non-fast-forward conflict;
- delete denied;
- force denied;
- provider webhook signature invalid;
- provider webhook unsupported event;
- provider repository unknown;
- webhook body too large;
- queue store unavailable;
- lease conflict;
- stale lease;
- retry exhausted;
- run superseded.

Every failure should indicate retryability.

### Phased Plan

Phase 1: Mirror queue model.

- Define work item, run record, trigger, state, retry policy, and safe
  diagnostics.
- Add serialization with schema version.
- Add redaction tests.

Phase 2: Local queue store.

- Implement atomic local persistence, listing, state transitions, and startup
  reload.
- Add stale lease recovery.
- Add corruption diagnostics.

Phase 3: Enqueue from Git ref events.

- Consume native Git ref update events.
- Match outbound mirror configs and refspecs.
- Create or coalesce outbound work items.

Phase 4: Worker lease and retry loop.

- Claim due work.
- Extend lease for long runs.
- Mark success, retryable failure, permanent failure, and dead-letter.
- Add backoff and jitter.

Phase 5: Outbound local mirror worker.

- Push configured refs to local/file remotes through native remote push
  primitives or scripted fixtures.
- Record per-ref results.
- Handle remote rejects and conflicts.

Phase 6: Manual admin operations.

- Add enqueue, dry-run, list, cancel, and retry operations.
- Keep HTTP/CLI surface minimal and safe.

Phase 7: Inbound scheduled fetch.

- Fetch configured remote refs manually or on schedule.
- Update local refs through mirror actor policy.
- Prevent overwriting local work by default.

Phase 8: Webhook intake model.

- Add provider delivery record, signature verification interface, event parser
  interface, and mapping diagnostics.

Phase 9: GitHub push webhook.

- Verify `X-Hub-Signature-256`.
- Parse push event.
- Map repository and ref.
- Enqueue inbound work.

Phase 10: GitLab push webhook.

- Verify configured token/signature mechanism.
- Parse push hook.
- Map project and ref.
- Enqueue inbound work.

Phase 11: Provider transport integration.

- Use smart HTTP/SSH transport credentials for GitHub/GitLab remotes.
- Keep provider REST API usage out unless needed for later setup validation.

Phase 12: Loop prevention.

- Record mirror actor and last successful mapping.
- Suppress outbound enqueue for inbound mirror updates by default.
- Coalesce webhooks matching just-completed outbound runs.

Phase 13: Bidirectional readiness.

- Add conflict record model and disabled-by-default bidirectional dry-run.
- Do not enable automatic bidirectional sync until policy is explicit.

### Verification

Queue:

- work item persists across restart;
- due work can be leased by one worker only;
- stale lease is recoverable;
- retryable failure schedules next attempt;
- permanent failure does not retry;
- max attempts moves item to dead letter;
- completed work is retained according to policy.

Enqueue:

- disabled mirror never enqueues work;
- ref update outside refspec does not enqueue;
- repeated branch updates coalesce;
- delete update is preserved when policy allows deletes;
- manual enqueue can force a new work item.

Outbound worker:

- pushes configured branch to local test remote;
- does not push unconfigured refs;
- remote non-fast-forward reject records conflict;
- force push happens only when configured;
- credentials are resolved through secret references and redacted.

Inbound worker:

- fetches configured remote branch;
- updates mapped local ref through mirror actor;
- refuses overwrite when local ref diverged and force is disabled;
- delete handling follows policy.

Webhooks:

- GitHub valid signature enqueues work;
- GitHub invalid signature rejects before parsing trusted fields;
- GitLab valid token/signature enqueues work;
- unsupported event is ignored or rejected according to policy;
- unknown repository does not expose mirror config details;
- duplicate delivery id is idempotent.

Loop prevention:

- inbound mirror update does not enqueue outbound work by default;
- outbound push followed by provider webhook coalesces or no-ops when refs match;
- bidirectional mirror remains disabled without explicit policy.

Admin:

- dry-run returns planned ref mappings without Git mutation;
- retry failed work creates or reactivates a work item;
- cancel pending work prevents worker execution;
- list APIs redact credentials and remote URL userinfo.

Production boundary:

- production code has no JGit dependency;
- webhook handlers do not run Git sync inline;
- logs do not include secrets or object contents.

### Rollout

Start with local queue storage and manual enqueue/dry-run so mirror behavior can
be inspected before automation.

Enable outbound mirroring from Git ref events before inbound mirroring.

Enable inbound scheduled/manual fetch before provider webhooks.

Enable provider webhooks only after signature verification, mapping, and queue
idempotency tests are complete.

Keep bidirectional mirroring disabled until explicit conflict and loop-prevention
policy passes dry-run tests.

Keep provider REST API automation out of the first rollout; document manual
webhook setup first.

### Open Questions

Should mirror queue storage live beside repository metadata, in a global mirror
store, or in the same operational store as application tokens/secrets?

Should completed work records be retained by count, age, or both?

Should mirror failures ever block local pushes for selected critical mirrors, or
should all mirror sync remain asynchronous?

Should webhook delivery payloads be stored redacted for debugging, or should only
parsed safe metadata be kept?

Should GitHub/GitLab webhook setup be automated through provider REST APIs later,
or remain an operator task?

Should bidirectional mirrors use one shared ref namespace for remote tracking
refs, or store last-seen remote ids only in mirror state?

### Acceptance Criteria

Mirror work is durable, idempotent, retryable, and recoverable after restart.

Outbound mirror jobs can be enqueued from local Git ref update events and run
asynchronously without blocking the original Git operation.

Inbound mirror jobs can be enqueued manually, on schedule, or by verified
provider webhooks.

GitHub and GitLab webhook handlers verify signatures/tokens, map deliveries to
mirror config, and enqueue work without running Git sync inline.

Workers enforce refspec, protected ref, force/delete/tag, credential, and access
policy before mutating remote or local refs.

Queue records, run records, admin responses, logs, and metrics contain safe
diagnostics only, with no raw credentials or provider secrets.
