# AgentD Session Discovery Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Rebuild AgentD's immutable local-session cache from durable session directories and reconcile it after filesystem notification loss.

**Architecture:** Parse metadata into a deliberately narrow manifest that excludes journal-derived, lifecycle, and operation-sequence state. Build complete snapshots using injected host/journal probes, atomically replace the registry, and use `WatchService` notifications and periodic polling only to trigger full rescans.

**Tech Stack:** Java 21 records and NIO, Jackson Core streaming JSON parsing, JUnit 5, AssertJ, Maven.

---

### Task 1: Forward-compatible manifest boundary

**Files:**
- Modify: `agentd/pom.xml`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/SessionManifest.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/SessionManifestReader.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/JsonSessionManifestReader.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/session/JsonSessionManifestReaderTest.java`

1. Write tests that parse the current fixture shape and a future manifest with the removed fields absent.
2. Assert unknown `operationSequence`, `latestTimestamp`, and other future fields do not enter the model.
3. Add invalid/mismatched identity and unsafe endpoint descriptor cases.
4. Run the focused test and confirm it fails because the reader does not exist.
5. Add only Jackson Core and implement a bounded streaming reader with strict duplicate/type checks.
6. Run the focused test and confirm it passes.

### Task 2: Atomic full-scan reconciliation

**Files:**
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/HostProbe.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/HostObservation.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/JournalProbe.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/JournalObservation.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/FileSystemJournalProbe.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/LocalSession.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/LocalSessionState.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/DiscoveryIssue.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/DiscoverySnapshot.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/SessionRegistry.java`
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/SessionDiscovery.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/session/SessionDiscoveryTest.java`

1. Write tests for empty/populated startup, live and dead probes, and shallow legacy/CBOR journal presence.
2. Run the focused test and confirm the missing discovery API is the failure.
3. Implement immutable observations, sessions, issues, snapshots, and atomic registry replacement.
4. Implement a sorted, no-follow full scan that isolates per-directory failures.
5. Run the focused test and confirm it passes.
6. Add failing cases for invalid metadata, unreadable journal, incomplete concurrent creation, replacement/reload, removal, and fresh-registry restart.
7. Implement the minimal classification and isolation behavior, then rerun the focused tests.

### Task 3: Watch and periodic reconciliation

**Files:**
- Create: `agentd/src/main/java/pro/deta/orion/agentd/session/SessionDiscoveryMonitor.java`
- Test: `agentd/src/test/java/pro/deta/orion/agentd/session/SessionDiscoveryMonitorTest.java`

1. Write deterministic tests proving initial, ordinary-event, `OVERFLOW`, and periodic triggers all execute full reconciliation.
2. Run the focused test and confirm it fails because the monitor is absent.
3. Implement one long-lived monitor using timed `WatchService.poll`, event draining, and clean close.
4. Add a missed-notification test that becomes visible on the periodic trigger and an independent reconciliation-failure recovery test.
5. Run all AgentD session discovery tests.

### Task 4: Verification and commits

**Files:**
- Review every file above and `agentd/src/main/java/pro/deta/orion/agentd/session/package-info.java`.

1. Check changed class-level `@AiRule` comments if any and keep lines within repository limits.
2. Run `mvn test -Pdev -T 4 -q -pl agentd -am -Dtest='*Session*Discovery*,JsonSessionManifestReaderTest' -Dsurefire.failIfNoSpecifiedTests=false`.
3. Commit the implementation in logical single-line commits.
4. Run `mvn verify -Pdev -T 4` outside the sandbox.
5. Run the required post-commit `make test` outside the sandbox.
6. Record exact results and leave the branch/worktree intact for review.
