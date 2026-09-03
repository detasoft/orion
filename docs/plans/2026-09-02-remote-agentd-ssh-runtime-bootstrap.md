# Remote AgentD SSH Runtime Bootstrap Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Upload a platform-compatible AgentD and session-host bundle over verified SSH and launch AgentD detached with its permit supplied only through SSH channel input.

**Architecture:** Add a narrow `agent-provisioning` Maven module with transport-independent validated records and an Apache MINA sshd implementation. One SSH session selects the exact requested platform/version bundle, uploads and verifies it, launches from the verified release, and atomically switches `current` only after launch acknowledgement. A whole-operation watchdog closes the session on timeout.

**Tech Stack:** Java 21, Maven, Apache MINA sshd 2.13.2, JUnit 5, AssertJ, in-process MINA SSH server, POSIX shell fixtures.

---

### Task 1: Add the provisioning module and validated contracts

**Files:**
- Modify: `pom.xml`
- Create: `agent-provisioning/pom.xml`
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/SshEndpoint.java`
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/SshCredentials.java`
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/RemotePlatform.java`
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/RuntimeArtifact.java`
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/RemoteRuntimeBundle.java`
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/RuntimeBundleCatalog.java`
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/AgentdLaunchRequest.java`
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/ProvisioningLaunchPermit.java`
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/ProvisioningOptions.java`
- Test: `agent-provisioning/src/test/java/pro/deta/orion/provisioning/ProvisioningContractsTest.java`

**Step 1: Write failing contract tests**

Cover endpoint host/user/port validation, canonical 64-character lowercase
SHA-256 validation, safe version identifiers, unique platform/version catalog selection,
platform normalization, HTTPS-only server URI, positive generation/frame
limits, positive timeouts, and permit defensive-copy/zero-on-close behavior.

**Step 2: Run the tests and confirm RED**

```bash
mvn test -Pdev -T 4 -q -pl agent-provisioning -am \
  -Dtest=ProvisioningContractsTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: Maven fails because the module and contract types do not exist.

**Step 3: Implement the minimal contracts**

Use immutable records with compact-constructor validation. `RemotePlatform`
maps `Linux`/`Darwin` and `x86_64`/`amd64`/`aarch64`/`arm64` to enums.
`ProvisioningLaunchPermit` owns a copied byte array, rejects CR/LF and empty or
oversized values, returns copies only while open, and overwrites its array on
`close()`. `RuntimeArtifact` computes the local SHA-256 through a factory and
also accepts an expected digest for mismatch tests. The catalog supports
multiple versions per platform, rejects duplicate platform/version pairs, and
returns an actionable unavailable-bundle failure.

**Step 4: Run the focused tests and confirm GREEN**

Run the Step 2 command. Expected: all contract tests pass.

**Step 5: Commit**

```bash
git add pom.xml agent-provisioning
git commit -m "Define remote AgentD bootstrap contracts"
```

### Task 2: Implement a bounded verified SSH operation

**Files:**
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/ProvisioningFailure.java`
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/ProvisioningException.java`
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/RemoteCommandResult.java`
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/PosixShell.java`
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/MinaSshOperation.java`
- Test: `agent-provisioning/src/test/java/pro/deta/orion/provisioning/PosixShellTest.java`
- Test: `agent-provisioning/src/test/java/pro/deta/orion/provisioning/MinaSshOperationTest.java`
- Test support: `agent-provisioning/src/test/java/pro/deta/orion/provisioning/TestSshServer.java`

**Step 1: Write failing SSH and quoting tests**

Start an in-process SSH server with fixed host and authorized client keys.
Assert exact host-key acceptance, changed-host-key rejection, invalid client-key
authentication failure, command stdin/stdout round trip, bounded stderr, and a
whole-operation timeout that closes the session. Quote values containing
spaces and apostrophes and reject NUL/CR/LF.

**Step 2: Run the tests and confirm RED**

```bash
mvn test -Pdev -T 4 -q -pl agent-provisioning -am \
  -Dtest=PosixShellTest,MinaSshOperationTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails because the SSH operation is absent.

**Step 3: Implement the SSH operation**

Configure a fresh `SshClient` per operation with user SSH configuration and
default identities disabled, only public-key authentication enabled, and a
`ServerKeyVerifier` that compares only the configured public key. Use
`connect(...).verify(timeout)`,
`session.auth().verify(timeout)`, and `channel.open().verify(timeout)`. Execute
commands without a PTY, write optional input, close channel input, wait for
`CLOSED` with the command timeout, and cap stdout/stderr capture at 16 KiB.
Schedule one whole-operation deadline on an injected/shared
`ScheduledExecutorService`; its callback requests an immediate non-blocking
close of the SSH client. Classify native connect, authentication, channel-open,
and channel-completion deadline failures as `TIMEOUT`. Never
start a timeout thread or executor task that performs the blocking SSH call.
Map failures to `CONNECTION`, `HOST_IDENTITY`, `AUTHENTICATION`, `REMOTE_COMMAND`,
or `TIMEOUT` without retaining unrestricted output or secrets.

**Step 4: Run the focused tests and confirm GREEN**

Run the Step 2 command. Expected: all SSH and quoting tests pass.

**Step 5: Commit**

```bash
git add agent-provisioning/src
git commit -m "Connect to provisioning targets over verified SSH"
```

### Task 3: Upload and verify versioned runtime bundles

**Files:**
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/RemoteAgentdProvisioner.java`
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/ProvisioningResult.java`
- Test: `agent-provisioning/src/test/java/pro/deta/orion/provisioning/RemoteAgentdProvisionerTest.java`

**Step 1: Write failing deployment tests**

Use the live SSH server's real POSIX command runner against a temporary remote
root. Cover exact platform/version selection, both artifact uploads, remote digest
checks, mode `0700`, atomic `current` symlink replacement, unsupported platform/version,
digest mismatch leaving the previous `current` target unchanged, and retry that
replaces only the same launch ID's partial staging directory.

**Step 2: Run the tests and confirm RED**

```bash
mvn test -Pdev -T 4 -q -pl agent-provisioning -am \
  -Dtest=RemoteAgentdProvisionerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: compilation fails because the provisioner is absent.

**Step 3: Implement the deployment transaction**

Probe `uname -s` and `uname -m`, select the exact requested-version bundle, validate all paths beneath
the configured install root, create a launch-specific `0700` staging directory,
and upload each file with a `cat > quoted-path` exec channel. On Linux run
`sha256sum`; on macOS run `shasum -a 256`. Require exact canonical digest
matches before chmod or publication. Rename staging to `releases/<version>`.
Treat an existing matching immutable release as an idempotent retry;
never modify an unrelated release or active target.

**Step 4: Run the focused and module tests**

Run the Step 2 command, then:

```bash
mvn test -Pdev -T 4 -q -pl agent-provisioning -am \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: all provisioning module tests pass.

**Step 5: Commit**

```bash
git add agent-provisioning/src
git commit -m "Install verified AgentD runtime bundles"
```

### Task 4: Launch AgentD detached and prove the session-host boundary

**Files:**
- Modify: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/RemoteAgentdProvisioner.java`
- Modify: `agent-provisioning/src/test/java/pro/deta/orion/provisioning/RemoteAgentdProvisionerTest.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/core/AgentConfiguration.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/AgentdMain.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/core/AgentConfigurationTest.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/core/AgentAssemblyTest.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/AgentdMainTest.java`
- Create: `agent-provisioning/src/test/resources/fixtures/agentd`
- Create: `agent-provisioning/src/test/resources/fixtures/session-host`

**Step 1: Write failing detached-launch tests**

Create an executable AgentD fixture that reads one permit line, waits until its
SSH parent/channel is gone, invokes the sibling `session-host` fixture, and
writes only non-secret markers. Assert the SSH provisioning call returns and
its session closes before both fixture markers appear. Assert the permit is
absent from the command, process arguments, environment capture, stdout,
stderr, and log. Add a launch-failure test with no activation change. Feed the
provisioner's generated argument vector through the real AgentD configuration
parser so unsupported launch options cannot pass on fixture behavior alone.

**Step 2: Run the test and confirm RED**

Run the Task 3 focused command. Expected: assertions fail because AgentD is not
launched.

**Step 3: Implement detached launch**

After publishing the verified release, execute a POSIX bootstrap command that reads one permit line
from channel stdin, pipes it directly to `setsid -f <release>/agentd` with all
AgentD stdout/stderr redirected to `<installRoot>/logs/<launchId>.log`, and
disconnects stdin after the permit pipe closes. Pass only the validated
non-secret AgentD arguments and a `--session-host` path to the bundled binary.
Make that path a required AgentD configuration field and preserve it through
AgentD assembly for the session-runtime layer; command routing remains outside
this leaf.
Wait for a fixed acknowledgement emitted only after detachment setup succeeds,
then atomically switch `current` to the launched release and close SSH. A launch
failure leaves the previous `current` target intact and retains the verified
release. Clear every temporary permit copy in a `finally` block.

**Step 4: Run focused and module tests**

```bash
mvn test -Pdev -T 4 -q -pl agent-provisioning -am \
  -Dtest=RemoteAgentdProvisionerTest \
  -Dsurefire.failIfNoSpecifiedTests=false
mvn test -Pdev -T 4 -q -pl agent-provisioning -am \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: the detached fixture survives SSH closure, invokes session-host, and
all module tests pass.

**Step 5: Commit**

```bash
git add agent-provisioning/src
git commit -m "Launch provisioned AgentD independently of SSH"
```

### Task 5: Review, verify, and squash the completed leaf

**Files:**
- Review: `docs/reviews/RULES.md`
- Modify: `docs/plans/current-work/remote-machine-provisioning/TASK.md`
- Delete: `docs/plans/current-work/remote-machine-provisioning/ssh-runtime-bootstrap/TASK.md`

**Step 1: Review the complete diff**

Check every task requirement and blocking review rule, especially that no
dedicated platform or virtual thread exists solely to enforce an I/O timeout.
Confirm credentials and permit bytes cannot appear in `toString`, exceptions,
commands, logs, or test diagnostics. Apply all blocking review fixes.

**Step 2: Run full development verification outside the sandbox**

```bash
mvn verify -Pdev -T 4
```

Expected: `BUILD SUCCESS` for the complete reactor.

**Step 3: Prepare dedicated-worktree completion state**

Delete the completed leaf directory and remove its parent link. Keep the three
remaining sibling tasks. Squash every task-branch commit after the real base
into one commit with this exact subject:

```text
Bootstrap remote AgentD runtime over SSH [task: remote-machine-provisioning/ssh-runtime-bootstrap]
```

Do not cherry-pick to `main`, delete the branch, or remove the worktree until a
future explicit user gate.

**Step 4: Run post-commit tests**

```bash
make test
```

Expected: `BUILD SUCCESS`. If a fix is required, commit it with the exact same
subject so it can be included in the final squash.

**Step 5: Record the handoff**

Report the pool/leaf paths, worktree, branch, base/head SHAs, changed files,
exact verification commands and results, known risks, worktree cleanliness,
and that integration/cleanup remain gated.
