# GitHub Commit Replication Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add the first GitHub synchronization candidate that can replicate selected commits from Orion to GitHub and receive selected commits from GitHub into Orion.

**Architecture:** Build provider-neutral Git mirror core with a GitHub transport profile that supports HTTPS and SSH remotes from the first candidate. Treat bidirectional sync as two explicit one-way flows sharing one config, queue, worker, source attribution, and fast-forward conflict rules; do not add automatic merge/rebase behavior in the first candidate.

**Tech Stack:** Java 21, Maven, JUnit 5, AssertJ, Dagger, `OrionEventManager`, `GitRepositoryProvider`, `GitRepository.upload/receive`, native Git protocol client primitives from `docs/plans/2026-05-14-native-git-protocol-client-primitives.md`, smart HTTP client transport from `docs/plans/2026-05-15-git-smart-http-transport-adapters.md`, and SSH client transport from `docs/plans/2026-05-15-git-ssh-transport-adapters.md`.

---

## Decision

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

## Scope

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

## Data Flow

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

## Naming

Use `GitMirror` for provider-neutral model names and `GitHubMirror` only for
GitHub-specific validation, webhook, and transport/credential profile classes.

The repository already stores plans under `docs/plans`; this file is the plan
requested for `plans/`.

---

### Task 1: Add the Git Mirror Module Skeleton

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

**Step 5: Commit**

```bash
git add core/pom.xml core/git-mirror
git commit -m "feat: add git mirror module"
```

---

### Task 2: Define Mirror Configuration and RefSpec Validation

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

**Step 5: Commit**

```bash
git add core/git-mirror/src/main/java/pro/deta/orion/git/mirror/config core/git-mirror/src/test/java/pro/deta/orion/git/mirror/config
git commit -m "feat: add git mirror configuration model"
```

---

### Task 3: Add a Durable Mirror Config Store

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

**Step 5: Commit**

```bash
git add core/git-mirror/src/main/java/pro/deta/orion/git/mirror/config core/git-mirror/src/test/java/pro/deta/orion/git/mirror/config
git commit -m "feat: add durable git mirror config store"
```

---

### Task 4: Add Queue and Run Record Storage

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

**Step 5: Commit**

```bash
git add core/git-mirror/src/main/java/pro/deta/orion/git/mirror/queue core/git-mirror/src/test/java/pro/deta/orion/git/mirror/queue
git commit -m "feat: add durable git mirror queue"
```

---

### Task 5: Enqueue Outbound Work from Local Receive Events

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

**Step 5: Commit**

```bash
git add core/git-mirror/src/main/java/pro/deta/orion/git/mirror/event core/git-mirror/src/test/java/pro/deta/orion/git/mirror/event
git commit -m "feat: enqueue outbound mirror work from git receive events"
```

---

### Task 6: Define Remote Git Replication Client Boundary

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

**Step 5: Commit**

```bash
git add core/git-mirror/src/main/java/pro/deta/orion/git/mirror/remote core/git-mirror/src/test/java/pro/deta/orion/git/mirror/remote
git commit -m "feat: define remote git replication client boundary"
```

---

### Task 7: Add Local Repository Protocol Bridge

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

**Step 5: Commit**

```bash
git add core/git-mirror/src/main/java/pro/deta/orion/git/mirror/local core/git-mirror/src/test/java/pro/deta/orion/git/mirror/local
git commit -m "feat: add local git replication bridge"
```

---

### Task 8: Implement Outbound Push Worker Logic

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

**Step 5: Commit**

```bash
git add core/git-mirror/src/main/java/pro/deta/orion/git/mirror/worker core/git-mirror/src/test/java/pro/deta/orion/git/mirror/worker
git commit -m "feat: add outbound github mirror worker"
```

---

### Task 9: Implement Inbound Fetch Worker Logic

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

**Step 5: Commit**

```bash
git add core/git-mirror/src/main/java/pro/deta/orion/git/mirror/worker core/git-mirror/src/test/java/pro/deta/orion/git/mirror/worker
git commit -m "feat: add inbound github mirror worker"
```

---

### Task 10: Add GitHub HTTPS and SSH Credential Profile

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
  `docs/plans/2026-05-15-secret-reference-credential-management.md`.
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

**Step 5: Commit**

```bash
git add core/git-mirror/src/main/java/pro/deta/orion/git/mirror/github core/git-mirror/src/test/java/pro/deta/orion/git/mirror/github
git commit -m "feat: add github mirror transport profile"
```

---

### Task 11: Add Manual Sync API and GitHub Webhook Intake

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

**Step 6: Commit**

```bash
git add core/git-mirror/src/main/java/pro/deta/orion/git/mirror/webhook core/git-mirror/src/test/java/pro/deta/orion/git/mirror/webhook net/http-core/src/main/java/pro/deta/orion/transport/http/OrionGitHubMirrorWebhookRoute.java net/http-core/src/main/java/pro/deta/orion/transport/http/OrionHttpModule.java net/http-core/src/test/java/pro/deta/orion/transport/http/OrionGitHubMirrorWebhookRouteTest.java
git commit -m "feat: enqueue github mirror fetches from webhooks"
```

---

### Task 12: Wire Mirror Runtime and Lifecycle

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

**Step 5: Commit**

```bash
git add core/git-mirror/src/main/java/pro/deta/orion/git/mirror core/git-mirror/src/test/java/pro/deta/orion/git/mirror core/bootstrap/pom.xml core/bootstrap/src/main/java/pro/deta/orion/component/OrionRuntimeModule.java core/bootstrap/src/main/java/pro/deta/orion/component/OrionRuntimeStateMachine.java core/bootstrap/src/test/java/pro/deta/orion/component/OrionRuntimeModuleTest.java
git commit -m "feat: wire git mirror runtime"
```

---

### Task 13: Add End-to-End Local Replication Tests

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

**Step 5: Commit**

```bash
git add tests/integration-test/src/test/java/pro/deta/orion/test/integration/git/GitMirrorReplicationIT.java tests/integration-test/pom.xml
git commit -m "test: cover github mirror replication flows"
```

---

### Task 14: Final Verification

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

**Step 5: Commit fixes if needed**

If verification fixes are required, commit with a single-line message matching
the logical change.

---

## Follow-Up Plans

After this candidate works:

- Add GitHub REST repository creation and webhook installation.
- Add explicit admin API/UI for mirror config CRUD and manual retry.
- Add GitLab profile on the same generic Git mirror core.
- Add force/delete/tag policies with protected-ref integration.
- Add source attribution stronger than `userName` if Git receive events gain a
  durable actor/source field.
- Add real github.com compatibility tests gated behind explicit credentials and
  not run by routine `mvn test -Pdev`.
