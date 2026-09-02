# Server-Launched AgentD Identity Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Authenticate a server-launched AgentD with a single-use permit, keep its reconnect token only in
memory, and prevent concurrent AgentD processes with a kernel-backed lock beside session metadata.

**Architecture:** Orion Server supplies the stable agent ID, launch generation, launch ID, server endpoint,
and state directory as non-secret process parameters, then supplies the launch permit only through stdin.
AgentD locks `<stateDir>/agentd.lock`, opens its outbound Jetty HTTP/2 control stream, sends an authenticated
`HELLO`, and accepts an in-memory reconnect token from `WELCOME`. Server-side permit validation and SSH
process management remain in the queued agent-session-server and remote-machine-provisioning tasks.

**Tech Stack:** Java 21, `java.nio.channels.FileLock`, CBOR Agent protocol, Jetty 12 low-level HTTP/2 client,
JUnit 5, AssertJ, Maven reactor.

---

## Execution Notes

- Work in `.worktrees/agentd-identity-registration-6a91f2` on
  `codex/agentd-identity-registration-6a91f2`.
- The branch contains an earlier persistent-identity prototype. Introduce the
  approved launch-authentication behavior first, then remove the obsolete
  identity and credential files in a separate commit.
- Use `@superpowers:test-driven-development` for every behavior change and
  `@superpowers:verification-before-completion` before declaring the task done.
- Run every Maven command outside the sandbox because AgentD transport tests
  bind loopback sockets.
- After every non-documentation commit, run `make test`. If a failure needs a
  follow-up fix commit, reuse the exact subject of the commit that introduced
  it so the commits can be squashed later.
- Do not implement SSH access, server-side token persistence, retry scheduling,
  or SHA-256 deployment in this leaf. Their updated task nodes now consume the
  contracts created here.

### Task 1: Extend the Agent protocol with launch authentication tails

**Files:**

- Create: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/AgentGeneration.java`
- Create: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/AgentLaunchId.java`
- Create: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/AgentAuthentication.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/AgentMessage.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/AgentProtocolCodec.java`
- Modify: `agent-protocol/src/main/java/pro/deta/orion/agent/protocol/ProtocolValidation.java`
- Modify: `agent-protocol/src/test/java/pro/deta/orion/agent/protocol/AgentProtocolCodecTest.java`
- Modify: `agent-protocol/src/test/java/pro/deta/orion/agent/protocol/AgentProtocolFixtureTest.java`
- Modify: `agent-protocol/protocol/README.md`

**Step 1: Add failing round-trip and validation tests**

Keep the frozen eight-field version-one `HELLO` fixture readable. Add tests
that construct an authenticated `HELLO` with an appended authentication tail
and a `WELCOME` with an appended reconnect token:

```java
AgentAuthentication authentication = new AgentAuthentication(
        new AgentGeneration(7),
        new AgentLaunchId(UUID.fromString("10010203-0405-0607-0809-0a0b0c0d0e0f")),
        AgentAuthentication.Kind.LAUNCH_PERMIT,
        ProtocolBytes.copyOf(new byte[32]));
AgentMessage.Hello hello = new AgentMessage.Hello(
        AgentProtocolVersion.CURRENT,
        JournalFormatVersion.CURRENT,
        AGENT_ID,
        INSTANCE_ID,
        "1.0.0",
        MACHINE,
        Map.of(),
        Optional.of(authentication));

assertThat(codec.decode(codec.encode(hello))).isEqualTo(hello);
```

Cover both credential kinds, zero or negative generations, undersized and
oversized credentials, a partially present authentication tail, and a legacy
fixture with `Optional.empty()` authentication.

For `WELCOME`, assert that the old five-field form remains decodable while the
new six-field form preserves a 32-byte reconnect token. `ProtocolBytes` must
continue to redact contents from `toString()`.

**Step 2: Run the protocol tests and confirm the expected failure**

Run:

```bash
mvn test -Pdev -T 4 -q -pl agent-protocol \
  -Dtest=AgentProtocolCodecTest,AgentProtocolFixtureTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails because `AgentGeneration`, `AgentLaunchId`,
`AgentAuthentication`, and the authentication fields do not exist.

**Step 3: Add bounded protocol value types**

Implement the value types without adding a dependency:

```java
public record AgentGeneration(long value) {
    public AgentGeneration {
        if (value <= 0) {
            throw new IllegalArgumentException("agent generation must be positive");
        }
    }
}

public record AgentLaunchId(UUID value) {
    public AgentLaunchId {
        Objects.requireNonNull(value, "value");
    }
}
```

`AgentAuthentication` contains generation, launch ID, kind, and opaque
credential bytes. Define wire codes `1` for `LAUNCH_PERMIT` and `2` for
`RECONNECT_TOKEN`. Require credential sizes from 32 through 512 bytes and keep
the value represented as redacting `ProtocolBytes`.

**Step 4: Append optional fields without breaking the frozen v1 prefix**

Extend `AgentMessage.Hello` with `Optional<AgentAuthentication>` and
`AgentMessage.Welcome` with `Optional<ProtocolBytes> reconnectToken`. Retain
overloaded constructors for the frozen forms so existing callers and fixtures
produce exactly eight and five fields.

Encode authenticated `HELLO` as:

```text
[type, protocolVersion, journalVersion, agentId, instanceId, agentVersion,
 machine, capabilities, generation, launchId, credentialKind, credentialBytes]
```

Encode authenticated `WELCOME` as:

```text
[type, protocolVersion, journalVersion, connectionId, configuration,
 reconnectToken]
```

Change `knownFields(...)` to decode all provided fields after checking the
existing minimum. Reject a `HELLO` with nine through eleven fields instead of
silently treating it as unauthenticated. Continue ignoring fields after the
known tail for forward compatibility.

**Step 5: Document the appended fields**

Update `agent-protocol/protocol/README.md` with exact positions, wire codes,
bounds, optional-prefix compatibility, and the rule that the server control
task must reject missing authentication even though the generic v1 codec can
still decode the frozen unauthenticated fixture.

**Step 6: Run focused tests**

Run the command from Step 2.

Expected: all `AgentProtocolCodecTest` and `AgentProtocolFixtureTest` tests pass
and `agent-hello-v1.hex` remains unchanged.

**Step 7: Commit and run the project test suite**

```bash
git add agent-protocol
git commit -m "Extend Agent protocol with launch authentication"
make test
```

Expected: the commit succeeds and `make test` reports every regular Maven
module successful.

### Task 2: Parse server launch identity and the stdin-only permit

**Files:**

- Create: `agentd/src/main/java/pro/deta/orion/agentd/core/AgentLaunchContext.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/core/LaunchPermit.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/core/LaunchPermitReader.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/core/AgentConfiguration.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/core/AgentConfigurationTest.java`
- Create: `agentd/src/test/java/pro/deta/orion/agentd/core/LaunchPermitReaderTest.java`

**Step 1: Add failing configuration tests**

Require the server launcher to pass these non-secret options:

```text
--server https://orion.example
--state-dir /var/lib/orion-agent
--agent-id agent-01KABC
--generation 7
--launch-id 10010203-0405-0607-0809-0a0b0c0d0e0f
--agent-version 1.2.3
```

Assert that `AgentConfiguration` exposes typed `AgentId`, `AgentGeneration`,
and `AgentLaunchId`, derives `sessionsDirectory()` and `processLockFile()` from
the one state directory, and requires every launch field. Retain HTTPS URI
validation and frame bounds.

Do not add a test whose only purpose is proving that the old persistent files
are absent; those prototype tests are removed in Task 6.

**Step 2: Add failing permit-input tests**

Define stdin as one base64url-without-padding line that decodes to 32 through
512 random bytes. Test a valid 32-byte value, empty input, invalid base64,
multiple lines, decoded values outside the bounds, and input larger than the
encoded bound.

```java
byte[] secret = new byte[32];
String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(secret) + "\n";

try (LaunchPermit permit = reader.read(
        new ByteArrayInputStream(encoded.getBytes(StandardCharsets.US_ASCII)))) {
    assertThat(permit.copyBytes()).containsExactly(secret);
    assertThat(permit.toString()).isEqualTo("LaunchPermit[REDACTED]");
}
```

Assert that thrown messages never contain the supplied line.

**Step 3: Run focused tests and confirm the expected failure**

```bash
mvn test -Pdev -T 4 -q -pl agentd -am \
  -Dtest=AgentConfigurationTest,LaunchPermitReaderTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails for missing launch fields and permit classes.

**Step 4: Implement launch configuration and secret input**

Remove the default state directory: `stateDir` is server-owned configuration
and must be explicit. Add `processLockFile()` returning
`stateDirectory.resolve("agentd.lock")`.

`LaunchPermit` owns a defensive byte-array copy, redacts `toString()`, and
implements `AutoCloseable` by zeroing its bytes. `LaunchPermitReader` uses a
bounded read, strict US-ASCII/base64url decoding, and generic error messages.
It must not convert the secret line into a long-lived Java `String` where a
bounded byte-oriented decode is practical.

`AgentLaunchContext` combines the server-assigned fields with a fresh
`AgentInstanceId` generated once per process and the launch permit:

```java
public record AgentLaunchContext(
        AgentId agentId,
        AgentGeneration generation,
        AgentLaunchId launchId,
        AgentInstanceId instanceId,
        LaunchPermit permit
) implements AutoCloseable {
    public static AgentLaunchContext create(AgentConfiguration configuration, LaunchPermit permit) {
        return new AgentLaunchContext(
                configuration.agentId(),
                configuration.generation(),
                configuration.launchId(),
                new AgentInstanceId(UUID.randomUUID()),
                permit);
    }
}
```

**Step 5: Run focused tests**

Run the command from Step 3.

Expected: all selected tests pass.

**Step 6: Commit and run the project test suite**

```bash
git add agentd/src/main/java/pro/deta/orion/agentd/core \
  agentd/src/test/java/pro/deta/orion/agentd/core
git commit -m "Accept server-assigned AgentD launch context"
make test
```

Expected: the commit and all regular Maven tests succeed.

### Task 3: Enforce one local AgentD with a kernel-backed file lock

**Files:**

- Create: `agentd/src/main/java/pro/deta/orion/agentd/core/AgentProcessMetadata.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/core/AgentProcessLock.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/core/AgentAlreadyRunningException.java`
- Create: `agentd/src/test/java/pro/deta/orion/agentd/core/AgentProcessLockTest.java`

**Step 1: Add failing lock-lifecycle tests**

Use `@TempDir` as the state directory. Cover:

- first acquisition writes diagnostic metadata;
- a concurrent acquisition of the same path fails with
  `AgentAlreadyRunningException`;
- closing the first lock lets another process object acquire the unchanged
  lock file;
- a pre-existing stale file does not prevent acquisition;
- a symbolic-link lock path is rejected;
- closing twice is safe.

The core assertion is:

```java
try (AgentProcessLock first = lock(stateDir, FIRST_METADATA)) {
    first.start();
    AgentProcessLock second = lock(stateDir, SECOND_METADATA);
    assertThatExceptionOfType(AgentAlreadyRunningException.class)
            .isThrownBy(second::start);
}

AgentProcessLock replacement = lock(stateDir, SECOND_METADATA);
replacement.start();
replacement.close();
assertThat(stateDir.resolve("agentd.lock")).exists();
```

**Step 2: Run the lock test and confirm the expected failure**

```bash
mvn test -Pdev -T 4 -q -pl agentd -am \
  -Dtest=AgentProcessLockTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails because the process lock does not exist.

**Step 3: Implement the lock as an `AgentService`**

Create the configured state directory if necessary, validate that the lock path
is not a symbolic link, and open it using `CREATE`, `WRITE`, and
`LinkOption.NOFOLLOW_LINKS`. On POSIX filesystems, create it as `0600` and
reject an existing lock file writable by group or others.

Call `FileChannel.tryLock()`. Treat both a `null` result and
`OverlappingFileLockException` as an already-running AgentD. Never infer
ownership from file contents and never unlink the file.

Hold the channel and `FileLock` until `close()`. Write and `force(true)` this
versioned, non-secret diagnostic format only after acquiring the lock:

```text
version=1
pid=<positive decimal>
startEpochMillis=<non-negative decimal>
launchId=<UUID>
generation=<positive decimal>
```

Obtain PID and process start time from `ProcessHandle.current()`. Keep an
injectable package-private metadata constructor for deterministic tests rather
than adding a public test-only method.

**Step 4: Run the lock tests**

Run the command from Step 2.

Expected: all lock tests pass, including reacquisition with the stale file
still present.

**Step 5: Commit and run the project test suite**

```bash
git add agentd/src/main/java/pro/deta/orion/agentd/core \
  agentd/src/test/java/pro/deta/orion/agentd/core/AgentProcessLockTest.java
git commit -m "Lock AgentD beside session metadata"
make test
```

Expected: the commit and all regular Maven tests succeed.

### Task 4: Authenticate the control stream and retain only an in-memory reconnect token

**Files:**

- Create: `agentd/src/main/java/pro/deta/orion/agentd/core/ReconnectToken.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/core/AgentControlService.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/core/AgentHandshake.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/core/AgentConnection.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/core/HandshakeException.java`
- Rewrite: `agentd/src/test/java/pro/deta/orion/agentd/core/AgentHandshakeTest.java`
- Create: `agentd/src/test/java/pro/deta/orion/agentd/core/AgentControlServiceTest.java`

**Step 1: Rewrite the handshake tests for server-assigned launch identity**

Build `HELLO` from `AgentLaunchContext` and assert exact protocol, journal,
agent, instance, generation, launch, version, machine, capability, credential
kind, and permit bytes. Accept only a `WELCOME` with current versions and a
bounded reconnect token.

```java
AgentMessage.Hello hello = handshake.initialHello(context, "2.4.1", MACHINE, capabilities);

assertThat(hello.authentication()).hasValueSatisfying(authentication -> {
    assertThat(authentication.generation()).isEqualTo(context.generation());
    assertThat(authentication.launchId()).isEqualTo(context.launchId());
    assertThat(authentication.kind()).isEqualTo(AgentAuthentication.Kind.LAUNCH_PERMIT);
});
```

Test rejection of missing reconnect token and unsupported versions without
changing the current connection state. `AgentConnection` owns the
`ReconnectToken` and clears the previous token when a later `WELCOME` replaces
it.

**Step 2: Add failing control-service tests with a fake transport**

The fake `AgentTransport` records control bytes and lets the test deliver a
server item. Cover:

- callbacks are registered before `connect()`;
- startup connects, sends authenticated `HELLO`, receives `WELCOME`, and
  exposes the negotiated connection;
- malformed CBOR, a non-`WELCOME` first message, an unauthenticated `WELCOME`,
  transport failure, and handshake timeout fail startup with redacted errors;
- closing the service closes transport and clears both launch and reconnect
  credentials.

Use a package-private constructor with an injected `Duration` so timeout tests
finish immediately. The production timeout is an internal safety bound below
the server's default 60-second startup timeout, not another machine
configuration option.

**Step 3: Run focused tests and confirm the expected failure**

```bash
mvn test -Pdev -T 4 -q -pl agentd -am \
  -Dtest=AgentHandshakeTest,AgentControlServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: tests fail because authenticated control orchestration and the
in-memory reconnect token are not implemented.

**Step 4: Implement the handshake and control service**

`AgentHandshake` must create a fresh message rather than retaining an encoded
permit indefinitely. `ReconnectToken` follows the same defensive-copy,
redacted-`toString()`, and zero-on-close contract as `LaunchPermit`.

`AgentControlService.start()` performs this ordered sequence:

1. register control and transport-signal callbacks;
2. connect the transport;
3. encode and send authenticated `HELLO`;
4. wait for exactly one valid `WELCOME` within the internal handshake timeout;
5. expose `AgentConnection` only after successful validation.

Decode failures and unexpected messages become `HandshakeException` without
including raw CBOR or credentials. `close()` completes any pending startup,
closes the negotiated connection, closes the launch context, and closes the
transport. Do not implement the later reconnect scheduler here; retain the
token so `platform-status-and-resilience` can consume it.

**Step 5: Run focused tests**

Run the command from Step 3.

Expected: all selected tests pass.

**Step 6: Commit and run the project test suite**

```bash
git add agentd/src/main/java/pro/deta/orion/agentd/core \
  agentd/src/test/java/pro/deta/orion/agentd/core
git commit -m "Authenticate the AgentD control handshake"
make test
```

Expected: the commit and all regular Maven tests succeed.

### Task 5: Wire lock and authenticated handshake into production startup

**Files:**

- Create: `agentd/src/main/java/pro/deta/orion/agentd/platform/LocalMachineInfo.java`
- Create: `agentd/src/test/java/pro/deta/orion/agentd/platform/LocalMachineInfoTest.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/core/Agent.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/AgentdMain.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/AgentdMainTest.java`
- Create: `agentd/src/test/java/pro/deta/orion/agentd/core/AgentAssemblyTest.java`
- Create: `agentd/src/test/java/pro/deta/orion/agentd/core/AgentControlLivePeerTest.java`

**Step 1: Add failing platform and assembly tests**

Test that `LocalMachineInfo` returns non-blank hostname, operating system, and
architecture from bounded JDK sources. Optional hostname lookup failure must
fall back to a safe non-blank value and must not prevent startup.

Add a package-private Agent assembly seam that accepts an `AgentTransport` and
machine information. Assert the service order is process lock first and
authenticated control second, so a second process cannot connect before it
fails local exclusion. Do not expose a public method solely for this test.

**Step 2: Add a failing real Jetty handshake test**

Start the existing test TLS/HTTP2 peer pattern, decode the first received
control CBOR as authenticated `HELLO`, and return an authenticated `WELCOME`.
Use the real `JettyHttp2Transport` and `AgentControlService`. Assert that the
service reaches a negotiated connection and that the permit never appears in
the request URI or HTTP headers.

This test is the production-path proof that the new classes are not dead
abstractions.

**Step 3: Extend `AgentdMainTest` for stdin bootstrap**

Introduce a package-private launcher seam in `AgentdMain.run(...)`. Test that:

- `--help` and invalid configuration do not read stdin;
- valid arguments read exactly one permit and hand an `AgentLaunchContext` to
  the Agent factory;
- invalid permit input returns configuration exit code `2` with a redacted
  message;
- an already-held process lock or rejected handshake returns startup exit code
  `1` without printing credential data.

**Step 4: Run the new tests and confirm the expected failure**

```bash
mvn test -Pdev -T 4 -q -pl agentd -am \
  -Dtest=LocalMachineInfoTest,AgentAssemblyTest,AgentControlLivePeerTest,AgentdMainTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation or assertions fail because production assembly still
creates an Agent with no services and `AgentdMain` does not read stdin.

**Step 5: Implement production assembly**

Make the normal main path equivalent to:

```java
AgentConfiguration configuration = AgentConfiguration.parse(args);
LaunchPermit permit = new LaunchPermitReader().read(input);
AgentLaunchContext context = AgentLaunchContext.create(configuration, permit);

try (Agent agent = Agent.create(configuration, context)) {
    agent.start();
    agent.awaitTermination();
}
```

`Agent.create(...)` constructs the default JDK-truststore
`SslContextFactory.Client`, the existing `JettyHttp2Transport`, an
`AgentProcessLock`, and `AgentControlService`. Pass services to
`AgentLifecycle` in lock-then-control order. Closing happens in reverse order,
so transport and tokens close before the process lock is released.

Do not detach inside AgentD. Detachment is a responsibility of the remote SSH
launcher task; this process simply stops depending on stdin after reading the
permit.

**Step 6: Run focused and module tests**

Run the command from Step 4, then:

```bash
mvn test -Pdev -T 4 -q -pl agentd -am \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: the production assembly test, live HTTP/2 handshake, existing
transport tests, and all AgentD tests pass.

**Step 7: Commit and run the project test suite**

```bash
git add agentd/src/main agentd/src/test
git commit -m "Wire AgentD launch authentication into startup"
make test
```

Expected: the commit and all regular Maven tests succeed.

### Task 6: Remove the abandoned persistent-identity prototype

The behavior replacement is complete in the preceding commits. Remove the
legacy implementation and its behavior-specific tests in this separate commit,
as required for concept replacement.

**Files:**

- Delete: `agentd/src/main/java/pro/deta/orion/agentd/core/AgentIdentity.java`
- Delete: `agentd/src/main/java/pro/deta/orion/agentd/core/AgentRegistration.java`
- Delete: `agentd/src/main/java/pro/deta/orion/agentd/core/BootstrapToken.java`
- Delete: `agentd/src/main/java/pro/deta/orion/agentd/core/FileAgentIdentityStore.java`
- Delete: `agentd/src/main/java/pro/deta/orion/agentd/core/FileMachineCredentialStore.java`
- Delete: `agentd/src/main/java/pro/deta/orion/agentd/core/MachineCredential.java`
- Delete: `agentd/src/main/java/pro/deta/orion/agentd/core/ProtectedCredentialStore.java`
- Delete: `agentd/src/main/java/pro/deta/orion/agentd/core/RegistrationClient.java`
- Delete: `agentd/src/main/java/pro/deta/orion/agentd/core/RegistrationException.java`
- Delete: `agentd/src/test/java/pro/deta/orion/agentd/core/AgentRegistrationTest.java`
- Delete: `agentd/src/test/java/pro/deta/orion/agentd/core/FileAgentIdentityStoreTest.java`
- Delete: `agentd/src/test/java/pro/deta/orion/agentd/core/FileMachineCredentialStoreTest.java`
- Modify: `agentd/pom.xml`

**Step 1: Confirm the new production path no longer references the prototype**

```bash
rg -n -e "AgentIdentity|AgentRegistration|BootstrapToken" \
  -e "FileAgentIdentityStore|FileMachineCredentialStore" \
  -e "MachineCredential|ProtectedCredentialStore|RegistrationClient" \
  agentd/src/main agentd/src/test
```

Expected: only the files scheduled for deletion remain. If a new production
class still imports one, fix the new design before deleting anything.

**Step 2: Delete the obsolete files and dependency**

Remove the listed classes and tests. Remove the runtime `key-material`
dependency added solely by the prototype from `agentd/pom.xml`. Keep the
existing test-scoped `common` dependency used by live TLS tests.

Do not add tests asserting that `identity.json`, credential files, or the old
classes are absent. The positive launch-context, lock, redaction, and
production-handshake tests define the replacement behavior.

**Step 3: Run AgentD and protocol tests**

```bash
mvn test -Pdev -T 4 -q -pl agent-protocol,agentd -am \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: both modules and their reactor dependencies pass without the
`key-material` AgentD runtime dependency.

**Step 4: Commit and run the project test suite**

```bash
git add agentd/pom.xml agentd/src/main agentd/src/test
git commit -m "Remove persistent AgentD identity storage"
make test
```

Expected: the cleanup commit and all regular Maven tests succeed.

### Task 7: Verify the complete task and request review

**Files:**

- Review: `docs/reviews/RULES.md`
- Review: `docs/plans/2026-09-02-agentd-server-launched-identity-design.md`
- Review: `docs/plans/current-work/agentd/identity-and-registration/TASK.md`
- Review: all changed files from Tasks 1 through 6

**Step 1: Check formatting, dependencies, and abandoned concepts**

```bash
git diff --check main...HEAD
rg -n "identity\.json|FileMachineCredentialStore|persistent machine credential|PKCS#12|PKCS12" \
  agentd agent-protocol docs/plans/current-work/agentd docs/plans/2026-09-02-agentd.md
```

Expected: `git diff --check` is silent. Search results appear only where the
approved design explicitly rejects the old alternatives; no production code or
active task requires persistent AgentD identity.

Confirm source lines are at most 112 characters except rare lines within the
repository's allowed 135-character exception.

**Step 2: Run focused reactor tests**

```bash
mvn test -Pdev -T 4 -q -pl agent-protocol,agentd -am \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: all selected modules pass.

**Step 3: Run routine development verification**

```bash
mvn verify -Pdev -T 4
```

Expected: the development reactor passes. If an unrelated environment-backed
integration module fails, capture the exact failure and distinguish it from
AgentD and Agent protocol results; do not claim a clean verification.

**Step 4: Run the required regular project suite**

```bash
make test
```

Expected: all regular Maven modules pass.

**Step 5: Review against blocking rules**

Read `docs/reviews/RULES.md` completely. Verify in particular that:

- `Agent.create(...)` uses the new lock and control service in production;
- no new implementation is reachable only from tests;
- protocol tails remain bounded and frozen prefixes stay compatible;
- secrets are absent from CLI, environment, persistence, logs, and exception
  messages;
- expected authentication failures use normal handshake/result flow;
- closing AgentD cannot terminate a native session-host process.

Use `@superpowers:requesting-code-review` for the primary review checkpoint.
Address every blocking finding and repeat Steps 1 through 4 after fixes.

**Step 6: Hand completion back to the Orion task orchestrator**

Do not merge the branch. The orchestrator must squash every task-branch commit
to:

```text
Authenticate server-launched AgentD [task: agentd/identity-and-registration]
```

The squash must delete
`docs/plans/current-work/agentd/identity-and-registration/` and remove its link
from `docs/plans/current-work/agentd/TASK.md`. The orchestrator then
cherry-picks the squashed commit to `main`, runs the required post-commit
`make test`, and removes the worktree and branch before reporting completion.
