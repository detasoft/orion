# Native Git Protocol Client Transport Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add a dedicated JGit-free module that defines transport/session and independent ref/content repository boundaries used by future native Git fetch and push clients.

**Architecture:** `core/git-protocol-client` depends on `git-common`, `git-parser`, and Netty buffers. Transport contracts exchange caller-owned `ByteBuf` chunks; `GitRepositoryRefs` owns listing, resolution, and CAS ref updates while `GitRepositoryContents` streams packs independently. Test-only scripted and in-memory implementations exercise the contracts without introducing a real transport or repository backend.

**Tech Stack:** Java 21, Maven, Netty `ByteBuf`, JUnit 5, AssertJ

---

### Task 1: Create the module and dependency boundary

**Files:**
- Modify: `core/pom.xml`
- Modify: `bom/pom.xml`
- Create: `core/git-protocol-client/pom.xml`
- Create: `core/git-protocol-client/src/test/java/pro/deta/orion/git/client/GitProtocolClientBoundaryTest.java`

**Step 1: Write the failing boundary test**

Create a test that locates the repository root, scans
`core/git-protocol-client/src/main/java`, and reports any source containing
`org.eclipse.jgit`. Read the module POM and assert that it does not contain an
`org.eclipse.jgit` group id.

**Step 2: Run the test to verify the new module is not yet in the reactor**

Run:

```bash
mvn test -Pdev -pl core/git-protocol-client
```

Expected: Maven fails because the selected project does not exist.

**Step 3: Add the minimal module**

Add `git-protocol-client` after `git-parser` in `core/pom.xml`. Add its managed
version in `bom/pom.xml`. Create a module POM with compile dependencies on:

```xml
<dependency>
    <groupId>pro.deta.orion.core</groupId>
    <artifactId>git-common</artifactId>
</dependency>
<dependency>
    <groupId>pro.deta.orion.core</groupId>
    <artifactId>git-parser</artifactId>
</dependency>
<dependency>
    <groupId>io.netty</groupId>
    <artifactId>netty-buffer</artifactId>
    <version>4.2.1.Final</version>
</dependency>
```

Add AssertJ as a test dependency.

**Step 4: Run the boundary test**

Run:

```bash
mvn test -Pdev -q -pl core/git-protocol-client
```

Expected: PASS.

### Task 2: Define services and bounded transport options

**Files:**
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/GitProtocolService.java`
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/GitProtocolTransportOptions.java`
- Create: `core/git-protocol-client/src/test/java/pro/deta/orion/git/client/GitProtocolTransportOptionsTest.java`

**Step 1: Write failing option and service tests**

Cover:

- upload-pack maps to `git-upload-pack`;
- receive-pack maps to `git-receive-pack`;
- positive connect, read, write, and total timeouts plus positive packet and
  pack limits are retained;
- zero or negative timeouts and limits are rejected;
- packet limit below the four-byte pkt-line header is rejected;
- total timeout shorter than an individual timeout is rejected.

**Step 2: Run the focused test and verify compilation fails**

Run:

```bash
mvn test -Pdev -q -pl core/git-protocol-client -Dtest=GitProtocolTransportOptionsTest
```

Expected: FAIL because the production types do not exist.

**Step 3: Implement the minimal value types**

Use:

```java
public enum GitProtocolService {
    UPLOAD_PACK("git-upload-pack"),
    RECEIVE_PACK("git-receive-pack");
}
```

Implement `GitProtocolTransportOptions` as a record with:

```java
Duration connectTimeout
Duration readTimeout
Duration writeTimeout
Duration operationTimeout
int maximumPacketBytes
long maximumPackBytes
```

Validate every value in the canonical constructor. Do not add authentication,
TLS, proxy, or transport-specific fields.

**Step 4: Run the focused test**

Run:

```bash
mvn test -Pdev -q -pl core/git-protocol-client -Dtest=GitProtocolTransportOptionsTest
```

Expected: PASS.

### Task 3: Define the ref repository port

**Files:**
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/repository/GitRepositoryRefs.java`
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/repository/GitRepositoryRef.java`
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/repository/GitRefQuery.java`
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/repository/GitRefUpdateRequest.java`
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/repository/GitRefUpdateOutcome.java`
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/repository/GitRepositoryAccessException.java`
- Create: `core/git-protocol-client/src/test/java/pro/deta/orion/git/client/repository/InMemoryGitRepositoryRefs.java`
- Create: `core/git-protocol-client/src/test/java/pro/deta/orion/git/client/repository/GitRepositoryRefsContractTest.java`

**Step 1: Write failing ref contract tests**

Cover:

- listing refs by prefix returns names and commit ids;
- ref records preserve optional peeled ids and symbolic-ref targets;
- resolving a known ref returns its commit id and an unknown ref is empty;
- creating a missing ref requires an absent expected old id;
- updating a ref succeeds only when the expected old commit matches;
- a stale update leaves the existing commit unchanged;
- an update to unavailable content returns `MISSING_COMMIT`.

The in-memory implementation receives a predicate that reports whether a commit
is available; this keeps refs independent from concrete content storage.

**Step 2: Run the focused test and verify compilation fails**

Run:

```bash
mvn test -Pdev -q -pl core/git-protocol-client -am \
  -Dtest=GitRepositoryRefsContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the repository ref contracts do not exist.

**Step 3: Implement the minimal ref contracts**

Define:

```java
public interface GitRepositoryRefs {
    List<GitRepositoryRef> listRefs(GitRefQuery query)
            throws GitRepositoryAccessException;
    Optional<GitObjectId> resolveCommit(String refName)
            throws GitRepositoryAccessException;
    GitRefUpdateOutcome updateRef(GitRefUpdateRequest request)
            throws GitRepositoryAccessException;
}
```

`GitRefUpdateRequest` contains the ref name, optional expected old commit id,
and required new commit id. `GitRefUpdateOutcome` contains `UPDATED`, `STALE`,
`MISSING_COMMIT`, and `REJECTED`. Do not expose filesystem paths or backend
transaction types.

**Step 4: Implement the test-only in-memory ref store**

Use ordinary maps and loops. Make update compare-and-set behavior atomic with a
synchronized method; do not couple it to the in-memory pack store.

**Step 5: Run the focused tests**

Run the command from Step 2.

Expected: PASS.

### Task 4: Define the pack content repository port

**Files:**
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/repository/GitRepositoryContents.java`
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/repository/GitPackId.java`
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/repository/GitPackReader.java`
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/repository/GitPackWriter.java`
- Create: `core/git-protocol-client/src/test/java/pro/deta/orion/git/client/repository/InMemoryGitRepositoryContents.java`
- Create: `core/git-protocol-client/src/test/java/pro/deta/orion/git/client/repository/GitRepositoryContentsContractTest.java`

**Step 1: Write failing pack content contract tests**

Cover:

- writing multiple binary chunks and completing publishes one pack id;
- reading that id returns the exact bytes in chunks;
- writing does not change the source buffer reader index;
- callers own and release buffers returned by the reader;
- closing an incomplete writer aborts publication;
- opening an unknown pack fails with a typed repository error;
- completing or closing repeatedly is harmless only where explicitly allowed.

**Step 2: Run the focused test and verify compilation fails**

Run:

```bash
mvn test -Pdev -q -pl core/git-protocol-client -am \
  -Dtest=GitRepositoryContentsContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because the content contracts do not exist.

**Step 3: Implement the minimal content contracts**

Define:

```java
public interface GitRepositoryContents {
    GitPackReader openPack(GitPackId packId)
            throws GitRepositoryAccessException;
    GitPackWriter beginPack() throws GitRepositoryAccessException;
}
```

`GitPackReader.read()` returns the next caller-owned `ByteBuf` or `null` at end
of pack. `GitPackWriter.write(ByteBuf)` reads bytes by absolute index and leaves
caller ownership unchanged. `complete()` durably publishes the content and
returns its `GitPackId`; closing before completion aborts.

**Step 4: Implement the test-only in-memory content store**

Store immutable byte arrays keyed by a deterministic SHA-256 fixture id. The
hash is a test implementation detail, not a production pack-id policy.

**Step 5: Run the focused tests**

Run the command from Step 2.

Expected: PASS.

### Task 5: Define typed failures and transport/session contracts

**Files:**
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/GitProtocolTransport.java`
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/GitProtocolSession.java`
- Create: `core/git-protocol-client/src/main/java/pro/deta/orion/git/client/GitProtocolTransportException.java`
- Create: `core/git-protocol-client/src/test/java/pro/deta/orion/git/client/ScriptedGitProtocolTransport.java`
- Create: `core/git-protocol-client/src/test/java/pro/deta/orion/git/client/GitProtocolTransportContractTest.java`

**Step 1: Write the failing scripted contract tests**

The test-only scripted transport records the service, URI, and options. Its
session owns queues of expected outbound byte arrays and inbound byte arrays,
allocates inbound buffers, and records close calls.

Cover:

- upload-pack and receive-pack open with the exact URI and options;
- outbound binary chunks are checked in order without changing the source
  buffer reader index;
- inbound chunks preserve exact bytes and become caller-owned;
- an unexpected write becomes a non-retryable `WRITE` failure;
- a configured read failure preserves `READ` phase and retryability;
- try-with-resources closes after success and after failure;
- repeated close is harmless.

**Step 2: Run the contract test and verify compilation fails**

Run:

```bash
mvn test -Pdev -q -pl core/git-protocol-client -Dtest=GitProtocolTransportContractTest
```

Expected: FAIL because the contracts do not exist.

**Step 3: Implement the contracts**

Define:

```java
public interface GitProtocolTransport {
    GitProtocolSession open(
            GitProtocolService service,
            URI remoteUri,
            GitProtocolTransportOptions options)
            throws GitProtocolTransportException;
}
```

```java
public interface GitProtocolSession extends AutoCloseable {
    void write(ByteBuf chunk) throws GitProtocolTransportException;
    ByteBuf read() throws GitProtocolTransportException;
    @Override
    void close() throws GitProtocolTransportException;
}
```

Make `GitProtocolTransportException` a checked exception with `Phase` values
`OPEN`, `WRITE`, `READ`, and `CLOSE`, plus a `retryable` flag. Require a
non-empty sanitized message and optional cause; do not store a URI or raw
protocol bytes.

**Step 4: Implement the test-only scripted transport**

Keep the fixture under `src/test/java`. Compare outbound bytes using absolute
buffer reads so ownership and reader indexes remain unchanged. Return a fresh
buffer for every inbound script entry. Release any unread scripted inbound
buffers during close.

**Step 5: Run all module tests**

Run:

```bash
mvn test -Pdev -q -pl core/git-protocol-client
```

Expected: PASS.

### Task 6: Verify the reactor and finish task tracking

**Files:**
- Modify: `TASKS.md`

**Step 1: Run routine development verification**

Run:

```bash
mvn verify -Pdev -q -pl core/git-protocol-client -am
```

Expected: PASS for the new module and required dependencies.

**Step 2: Mark the current task complete**

Change the selected task to checked and remove its owner line. Keep these
follow-up tasks under `## Next`:

- scripted native upload-pack and receive-pack clients;
- production backends for independent ref and content ports;
- first real native Git client transport and end-to-end remote fetch/push
  compatibility tests.

Do not alter other task ownership or active work.

**Step 3: Review the final diff**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; only files from this task are ready to stage,
while unrelated clone and native-storage changes remain unstaged.
