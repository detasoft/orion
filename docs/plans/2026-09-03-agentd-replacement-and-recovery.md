# AgentD Replacement and Recovery Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Safely replace one remotely provisioned AgentD on Linux, adopt recoverable partial launches, preserve session-host processes, and bound offline/startup retry behavior.

**Architecture:** Extend the existing `agent-provisioning` SSH transaction with strict process metadata and generation-scoped native identity records. A remote reconciler performs fail-closed inspect/signal/adopt/launch transitions, while a transport-independent recovery loop owns sustained-offline and startup waits plus capped retry backoff.

**Tech Stack:** Java 21, Maven, Apache MINA sshd, JUnit 5, AssertJ, POSIX shell fixtures.

**Platform boundary:** Safe replacement and recovery are Linux-only in this plan.
macOS must fail closed until a separately shipped native inspector can prove exact
process birth, executable, and kernel-lock ownership. Ordinary provisioning may still
select macOS runtime bundles.

---

### Task 1: Define process identity and recovery contracts

**Files:**
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/AgentdProcessIdentity.java`
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/AgentdReplacementResult.java`
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/AgentdRecoveryOptions.java`
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/AgentdAvailability.java`
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/AgentdLaunchAttempt.java`
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/AgentdLaunchAttemptSource.java`
- Modify: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/ProvisioningFailure.java`
- Test: `agent-provisioning/src/test/java/pro/deta/orion/provisioning/AgentdRecoveryContractsTest.java`

**Step 1: Write failing validation tests**

Specify a positive PID and start epoch, bounded non-control native token, absolute
normalized release/executable paths, matching executable beneath the release,
non-null launch/generation, positive termination/startup/offline/backoff durations,
maximum backoff not below initial backoff, and a positive attempt limit. Verify a
launch attempt owns and closes its permit without exposing it from `toString()`.

**Step 2: Run the focused test and confirm RED**

Run:

```bash
make run-test MODULE=agent-provisioning TEST='AgentdRecoveryContractsTest'
```

Expected: compilation fails because the recovery contracts do not exist.

**Step 3: Implement minimal immutable contracts**

Add typed failures for unsafe/malformed identity, uncertain identity, signal
privilege, termination timeout, startup timeout, and exhausted retries. Keep permit
ownership explicit and every diagnostic representation secret-free.

**Step 4: Run the focused test and confirm GREEN**

Run the Step 2 command. Expected: all contract tests pass.

**Step 5: Commit**

```bash
git add agent-provisioning/src
git commit -m "Define AgentD recovery contracts"
```

### Task 2: Publish exact process-owned executable metadata

**Files:**
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/core/AgentProcessMetadata.java`
- Modify: `agentd/src/main/java/pro/deta/orion/agentd/core/AgentProcessLock.java`
- Modify: `agentd/src/test/java/pro/deta/orion/agentd/core/AgentProcessLockTest.java`

**Step 1: Write failing metadata tests**

Require lock metadata version 2 to contain the exact normalized absolute command
reported for the current AgentD process. Reject relative paths and control bytes.
Keep existing owner-only, no-follow, kernel-lock behavior.

**Step 2: Run the focused test and confirm RED**

Run:

```bash
make run-test MODULE=agentd TEST='AgentProcessLockTest'
```

Expected: assertions fail because version 1 metadata has no executable.

**Step 3: Implement metadata version 2**

Resolve `ProcessHandle.current().info().command()` at process startup, normalize it,
and fail startup if an exact absolute command cannot be established. Serialize only
ASCII-safe validated fields and keep the existing atomic kernel-lock lifetime.

**Step 4: Run the focused test and confirm GREEN**

Run the Step 2 command. Expected: all process-lock tests pass.

**Step 5: Commit and verify the project**

```bash
git add agentd/src
git commit -m "Record the exact AgentD executable in lock metadata"
make test
```

### Task 3: Discover, encode, and verify remote process identity

**Files:**
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/RemoteCommandExecutor.java`
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/AgentdProcessRecord.java`
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/RemoteAgentdProcessControl.java`
- Modify: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/MinaSshOperation.java`
- Test: `agent-provisioning/src/test/java/pro/deta/orion/provisioning/AgentdProcessRecordTest.java`
- Test: `agent-provisioning/src/test/java/pro/deta/orion/provisioning/RemoteAgentdProcessControlTest.java`

**Step 1: Write failing record and inspection tests**

Cover strict bounded parsing, missing/duplicate/extra/control fields, owner-only
regular non-symlink requirements, Linux start-token/executable probes, explicit
fail-closed macOS behavior, record publication through a `0600` temporary file plus
rename, exact lock/record/live agreement, and unsafe or malformed state mapping to
typed diagnostics.

**Step 2: Run the focused tests and confirm RED**

Run:

```bash
make run-test MODULE=agent-provisioning \
  TEST='AgentdProcessRecordTest,RemoteAgentdProcessControlTest'
```

Expected: compilation fails because process control is absent.

**Step 3: Implement minimal process inspection**

Make the existing MINA operation implement the package command boundary. Render
platform-specific owner/mode, native start-token, and executable probes with strict
POSIX quoting. Parse only bounded ASCII. Treat the AgentD lock as corroborating
process-owned state and the generation record as launcher-observed state; require
both plus the live process to agree.

**Step 4: Run the focused tests and confirm GREEN**

Run the Step 2 command. Expected: all record/control tests pass.

**Step 5: Commit and verify the project**

```bash
git add agent-provisioning/src
git commit -m "Verify remote AgentD process identity"
make test
```

### Task 4: Terminate exactly one proven AgentD

**Files:**
- Modify: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/RemoteAgentdProcessControl.java`
- Modify: `agent-provisioning/src/test/java/pro/deta/orion/provisioning/RemoteAgentdProcessControlTest.java`

**Step 1: Write failing termination tests**

Assert full record/lock/native/executable re-verification occurs in the same remote
transaction immediately before `TERM` and again before `KILL`. Cover identity mismatch
and unreadable state sending no signal, permission denial, TERM confirmation, PID
reuse before KILL being treated as old-process exit without signalling the reused PID,
and confirmation timeout. Run a child sentinel and prove only the AgentD PID is
targeted and the child survives.

**Step 2: Run the test and confirm RED**

Run:

```bash
make run-test MODULE=agent-provisioning TEST='RemoteAgentdProcessControlTest'
```

Expected: tests fail because termination is not implemented.

**Step 3: Implement bounded exact-PID termination**

Signal only the recorded numeric PID. Poll with the injected monotonic clock/sleeper.
Treat disappearance or changed native token as termination. Re-verify every field in
the KILL command before signalling. Preserve bounded remote diagnostics and distinguish
identity uncertainty, privilege failure, and confirmation timeout.

**Step 4: Run the focused test and confirm GREEN**

Run the Step 2 command. Expected: all process-control tests pass and the child sentinel
remains alive.

**Step 5: Commit and verify the project**

```bash
git add agent-provisioning/src
git commit -m "Terminate only a verified remote AgentD"
make test
```

### Task 5: Reconcile launch and atomic version state

**Files:**
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/RemoteAgentdReconciler.java`
- Modify: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/RemoteAgentdProvisioner.java`
- Modify: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/ProvisioningResult.java`
- Modify: `agent-provisioning/src/test/java/pro/deta/orion/provisioning/RemoteAgentdProvisionerTest.java`
- Create: `agent-provisioning/src/test/java/pro/deta/orion/provisioning/RemoteAgentdReconcilerTest.java`
- Modify: `agent-provisioning/src/test/resources/fixtures/agentd`

**Step 1: Write failing reconcile tests**

Cover old-process verified termination followed by launch, actual PID discovery from
process-owned lock metadata rather than `$!`, native identity publication, and
`current` switch only after exact lock/live proof. Cover crash adoption before identity
publication, after publication but before `current`, and after `current`; staged release
without a process; invalid live mismatch with no launch; termination timeout with no
launch; and atomic current preservation when launch proof fails.

**Step 2: Run the focused tests and confirm RED**

Run:

```bash
make run-test MODULE=agent-provisioning \
  TEST='RemoteAgentdProvisionerTest,RemoteAgentdReconcilerTest'
```

Expected: tests fail because launch identity and reconciliation are absent.

**Step 3: Implement launch/adoption protocol**

Reuse one verified SSH operation and the existing immutable release installer. Keep the
permit on channel input. After detached start, poll the process-owned lock for the exact
requested launch, take its actual PID, derive and recheck the native token/executable,
atomically publish the generation record, and only then switch `current`. On restart,
adopt an exact live requested launch and finish missing publication or `current` commit
without duplicate launch. Fail closed on every other disagreement.

**Step 4: Run focused and module tests**

Run the Step 2 command, then:

```bash
make run-test MODULE=agent-provisioning TEST='*Test'
```

Expected: all provisioning tests pass.

**Step 5: Commit and verify the project**

```bash
git add agent-provisioning/src
git commit -m "Reconcile remote AgentD launch state"
make test
```

### Task 6: Bound offline recovery, startup, and retries

**Files:**
- Create: `agent-provisioning/src/main/java/pro/deta/orion/provisioning/AgentdRecovery.java`
- Create: `agent-provisioning/src/test/java/pro/deta/orion/provisioning/AgentdRecoveryTest.java`

**Step 1: Write failing recovery state-machine tests**

Use deterministic fake availability, monotonic clock, sleeper, launch source, and
reconciler boundaries. Cover reconnection before sustained-offline timeout, one-attempt
success, already-launched target remaining in `AWAITING_ONLINE` without duplicate
launch, startup timeout followed by a fresh launch ID/permit, termination of the prior
attempt before retry, capped exponential backoff, and retry exhaustion.

**Step 2: Run the focused test and confirm RED**

Run:

```bash
make run-test MODULE=agent-provisioning TEST='AgentdRecoveryTest'
```

Expected: compilation fails because the recovery loop is absent.

**Step 3: Implement the synchronous recovery loop**

Wait for sustained offline first. For each attempt, reconcile remote state once and
wait for that exact launch to become online for the configured startup timeout. On
timeout, close the attempt, ask the caller-owned source for a fresh revoked/reissued
generation and permit, sleep with capped exponential backoff, and require the next
reconcile to terminate the prior attempt before launching. Stop after the configured
attempt bound with typed diagnostics. Allocate no timeout threads.

**Step 4: Run focused and module tests**

Run the Step 2 command, then:

```bash
make run-test MODULE=agent-provisioning TEST='*Test'
```

Expected: all provisioning tests pass.

**Step 5: Commit and verify**

```bash
git add agent-provisioning/src
git commit -m "Bound AgentD offline recovery retries"
make test
mvn verify -Pdev -T 4
git diff --check
```
