# Stalled SSH Operation Watchdog Baseline Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make the real-SSH watchdog test reliably reach the stalled command while preserving the production
whole-operation deadline.

**Architecture:** Keep `MinaSshOperation` unchanged because its watchdog correctly covers connection,
authentication, and command execution. Adjust only the integration-style test deadline so SSH setup has a
realistic scheduling margin and the longer remote command still exceeds the whole-operation limit.

**Tech Stack:** Java 21, Apache MINA SSHD, JUnit 5, AssertJ, Maven

---

### Task 1: Stabilize the stalled-operation scenario

**Files:**

- Modify: `agent-provisioning/src/test/java/pro/deta/orion/provisioning/MinaSshOperationTest.java:267`
- Modify: `docs/plans/current-work/remote-machine-provisioning/TASK.md`
- Modify: `docs/plans/current-work/remote-machine-provisioning/stalled-operation-watchdog-baseline/TASK.md`

**Step 1: Confirm the existing regression**

Run:

```bash
make run-test MODULE=agent-provisioning \
  TEST='MinaSshOperationTest#wholeOperationWatchdogClosesAStalledSession'
```

Expected before the fix: `MinaSshOperation.open` can fail with `SSH provisioning operation timed out`
because the 100 ms whole-operation watchdog closes the session during authentication.

**Step 2: Apply the minimal test correction**

Change the test options to retain the one-second connect and authentication limits, the five-second command
limit, and a two-second whole-operation limit:

```java
ProvisioningOptions shortOperation = new ProvisioningOptions(
        Duration.ofSeconds(1), Duration.ofSeconds(1),
        Duration.ofSeconds(5), Duration.ofSeconds(2));
```

Keep the remote command as `sleep 5`, so the whole-operation watchdog remains the first applicable deadline
after setup succeeds.

**Step 3: Verify the focused scenario repeatedly**

Run the focused `make run-test` command five consecutive times.

Expected: all five invocations pass, each asserting `ProvisioningFailure.TIMEOUT` from command execution.

**Step 4: Verify the complete project**

Run:

```bash
make test
```

Expected: `BUILD SUCCESS` with no failures or errors.

**Step 5: Complete task tracking and commit**

Mark the parent task entry complete, set the leaf task status to complete, and remove its owner line. Stage
only the test, plan, and task-tree files, then create a single-line logical implementation commit.
