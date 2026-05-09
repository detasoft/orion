# Lifecycle Flow Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make `OrionApplicationLifecycle` explicit, readable, and easy to modify by moving phase order and in-phase task order into first-class definitions.

**Architecture:** Keep `ApplicationState` as the public state enum for now, but introduce a `LifecycleFlow` table as the only source of truth for phase transitions. Then introduce named lifecycle tasks with explicit dependencies so startup/shutdown order is described as a graph instead of scattered integer priorities. All lifecycle listeners should register named tasks through `ApplicationStateListenerRegistrar.task(...)`.

**Tech Stack:** Java 21, Maven, JUnit 5, AssertJ, existing `OrionExecutor` and lifecycle package.

---

## Design Summary

The lifecycle used to have two hidden order systems:

- phase order is hard-coded in private methods such as `onInitStage()`, `onStartStage()`, and `doShutdown()`;
- listener order was encoded as integer priorities plus a non-obvious `waitForCompletion()` barrier.

The target model separates those concerns:

- `LifecycleFlow` says which application phase follows which phase;
- `LifecycleTaskPlan` says which named tasks run inside a phase and what they depend on;
- dependency edges are the only barrier between task execution groups.

The first milestone should make this obvious:

```text
STARTUP:
  INIT -> STARTING
  STARTING -> UP
  INIT -> FAILED on failure
  STARTING -> FAILED on failure

SHUTDOWN:
  UP -> BEGIN_SHUTDOWN
  BEGIN_SHUTDOWN -> STOPPING
  STOPPING -> OFF
  BEGIN_SHUTDOWN -> FAILED on failure
  STOPPING -> FAILED on failure
```

The second milestone should make task order obvious:

```text
INIT:
  JGIT_RUNTIME -> EVENT_MANAGER -> ACL_INIT -> GIT_BACKED_INTERNAL_STORAGE_INIT

STARTING:
  REPOSITORY_STORAGE -> ACL_LOAD -> TRANSPORTS

STOPPING:
  TRANSPORTS_STOP -> EVENT_MANAGER_STOP -> EXECUTOR_STOP
```

## Commit Strategy

Use small commits. Prefer one commit per task. Run focused tests before each commit. Run `mvn test -Pdev` after the full refactor.

Do not rename `ApplicationState` in the first pass. Renaming to `LifecyclePhase` is a later cleanup after behavior is explicit and tested.

## Task 1: Characterize Current Lifecycle Behavior

**Files:**

- Modify: `core/common/src/test/java/pro/deta/orion/lifecycle/OrionApplicationLifecycleTest.java`

**Step 1: Add tests for current phase transitions**

Add tests that prove the current public behavior before refactoring:

```java
@Test
void startupRunsInitThenStartingAndEndsUp() {
    List<String> events = Collections.synchronizedList(new ArrayList<>());
    OrionApplicationStageEventListener init = registrar ->
            registrar.task(ApplicationState.INIT, new LifecycleTaskId("TEST_INIT"), () -> {
                events.add("init");
                return OrionStageCallResult.EMPTY;
            });
    OrionApplicationStageEventListener starting = registrar ->
            registrar.task(ApplicationState.STARTING, new LifecycleTaskId("TEST_STARTING"), () -> {
                events.add("starting");
                return OrionStageCallResult.EMPTY;
            });

    ApplicationState result = runLifecycle(Set.of(init, starting));

    assertThat(result).isEqualTo(ApplicationState.UP);
    assertThat(events).containsExactly("init", "starting");
}
```

Add failure characterization:

```java
@Test
void failingInitMovesApplicationToFailedAndDoesNotRunStarting() {
    List<String> events = Collections.synchronizedList(new ArrayList<>());
    OrionApplicationStageEventListener init = registrar ->
            registrar.task(ApplicationState.INIT, new LifecycleTaskId("TEST_INIT"), () -> {
                events.add("init");
                throw new IllegalStateException("boom");
            });
    OrionApplicationStageEventListener starting = registrar ->
            registrar.task(ApplicationState.STARTING, new LifecycleTaskId("TEST_STARTING"), () -> {
                events.add("starting");
                return OrionStageCallResult.EMPTY;
            });

    ApplicationState result = runLifecycle(Set.of(init, starting));

    assertThat(result).isEqualTo(ApplicationState.FAILED);
    assertThat(events).containsExactly("init");
}
```

Add shutdown characterization:

```java
@Test
void shutdownRunsStoppingAndEndsOff() {
    List<String> events = Collections.synchronizedList(new ArrayList<>());
    OrionApplicationStageEventListener stopping = registrar ->
            registrar.task(ApplicationState.STOPPING, new LifecycleTaskId("TEST_STOPPING"), () -> {
                events.add("stopping");
                return OrionStageCallResult.EMPTY;
            });

    try (TestLifecycleContext context = new TestLifecycleContext(Set.of(stopping))) {
        context.lifecycle().runApplication();
        context.lifecycle().beginShutdown();
        context.lifecycle().waitForShutdown();

        assertThat(context.stateHolder().getState()).isEqualTo(ApplicationState.OFF);
        assertThat(events).containsExactly("stopping");
    }
}
```

Introduce small test helpers in the test class only:

```java
private ApplicationState runLifecycle(Set<OrionApplicationStageEventListener> listeners) {
    try (TestLifecycleContext context = new TestLifecycleContext(listeners)) {
        return context.lifecycle().runApplication();
    }
}
```

**Step 2: Run focused tests**

Run:

```bash
mvn test -Pdev -pl core/common -Dtest=OrionApplicationLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS. These tests document current behavior before changes.

**Step 3: Commit**

```bash
git add core/common/src/test/java/pro/deta/orion/lifecycle/OrionApplicationLifecycleTest.java
git commit -m "test: characterize application lifecycle behavior"
```

## Task 2: Introduce Explicit LifecycleFlow

**Files:**

- Create: `core/common/src/main/java/pro/deta/orion/lifecycle/flow/LifecycleFlow.java`
- Create: `core/common/src/main/java/pro/deta/orion/lifecycle/flow/LifecycleStep.java`
- Create: `core/common/src/test/java/pro/deta/orion/lifecycle/flow/LifecycleFlowTest.java`

**Step 1: Write failing flow tests**

Create `LifecycleFlowTest`:

```java
class LifecycleFlowTest {
    @Test
    void startupFlowDocumentsPhaseOrder() {
        assertThat(LifecycleFlow.STARTUP.steps())
                .extracting(LifecycleStep::from, LifecycleStep::success, LifecycleStep::failure)
                .containsExactly(
                        tuple(ApplicationState.INIT, ApplicationState.STARTING, ApplicationState.FAILED),
                        tuple(ApplicationState.STARTING, ApplicationState.UP, ApplicationState.FAILED));
    }

    @Test
    void shutdownFlowDocumentsPhaseOrder() {
        assertThat(LifecycleFlow.SHUTDOWN.steps())
                .extracting(LifecycleStep::from, LifecycleStep::success, LifecycleStep::failure)
                .containsExactly(
                        tuple(ApplicationState.UP, ApplicationState.BEGIN_SHUTDOWN, ApplicationState.FAILED),
                        tuple(ApplicationState.BEGIN_SHUTDOWN, ApplicationState.STOPPING, ApplicationState.FAILED),
                        tuple(ApplicationState.STOPPING, ApplicationState.OFF, ApplicationState.FAILED));
    }

    @Test
    void flowDescriptionIsReadable() {
        assertThat(LifecycleFlow.STARTUP.describe()).contains(
                "STARTUP:",
                "INIT -> STARTING",
                "STARTING -> UP",
                "INIT -> FAILED on failure",
                "STARTING -> FAILED on failure");
    }
}
```

**Step 2: Run tests and verify failure**

Run:

```bash
mvn test -Pdev -pl core/common -Dtest=LifecycleFlowTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because `LifecycleFlow` does not exist.

**Step 3: Implement LifecycleStep**

Create:

```java
package pro.deta.orion.lifecycle.flow;

import pro.deta.orion.ApplicationState;

import java.util.Objects;

public record LifecycleStep(
        ApplicationState from,
        ApplicationState success,
        ApplicationState failure,
        boolean runListeners
) {
    public LifecycleStep {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(success, "success");
        Objects.requireNonNull(failure, "failure");
    }

    public static Builder from(ApplicationState from) {
        return new Builder(from);
    }

    public static final class Builder {
        private final ApplicationState from;
        private ApplicationState success;
        private ApplicationState failure = ApplicationState.FAILED;
        private boolean runListeners = true;

        private Builder(ApplicationState from) {
            this.from = from;
        }

        public Builder to(ApplicationState success) {
            this.success = success;
            return this;
        }

        public Builder onFailure(ApplicationState failure) {
            this.failure = failure;
            return this;
        }

        public Builder transitionOnly() {
            this.runListeners = false;
            return this;
        }

        public LifecycleStep build() {
            return new LifecycleStep(from, success, failure, runListeners);
        }
    }
}
```

**Step 4: Implement LifecycleFlow**

Create:

```java
package pro.deta.orion.lifecycle.flow;

import pro.deta.orion.ApplicationState;

import java.util.List;
import java.util.Objects;

public record LifecycleFlow(String name, List<LifecycleStep> steps) {
    public static final LifecycleFlow STARTUP = new LifecycleFlow("STARTUP", List.of(
            LifecycleStep.from(ApplicationState.INIT)
                    .to(ApplicationState.STARTING)
                    .onFailure(ApplicationState.FAILED)
                    .build(),
            LifecycleStep.from(ApplicationState.STARTING)
                    .to(ApplicationState.UP)
                    .onFailure(ApplicationState.FAILED)
                    .build()));

    public static final LifecycleFlow SHUTDOWN = new LifecycleFlow("SHUTDOWN", List.of(
            LifecycleStep.from(ApplicationState.UP)
                    .to(ApplicationState.BEGIN_SHUTDOWN)
                    .onFailure(ApplicationState.FAILED)
                    .transitionOnly()
                    .build(),
            LifecycleStep.from(ApplicationState.BEGIN_SHUTDOWN)
                    .to(ApplicationState.STOPPING)
                    .onFailure(ApplicationState.FAILED)
                    .transitionOnly()
                    .build(),
            LifecycleStep.from(ApplicationState.STOPPING)
                    .to(ApplicationState.OFF)
                    .onFailure(ApplicationState.FAILED)
                    .build()));

    public LifecycleFlow {
        Objects.requireNonNull(name, "name");
        steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Lifecycle flow must contain at least one step");
        }
    }

    public String describe() {
        StringBuilder builder = new StringBuilder(name).append(":\n");
        for (LifecycleStep step : steps) {
            builder.append("  ")
                    .append(step.from())
                    .append(" -> ")
                    .append(step.success())
                    .append('\n');
        }
        for (LifecycleStep step : steps) {
            builder.append("  ")
                    .append(step.from())
                    .append(" -> ")
                    .append(step.failure())
                    .append(" on failure\n");
        }
        return builder.toString();
    }
}
```

**Step 5: Run tests and commit**

Run:

```bash
mvn test -Pdev -pl core/common -Dtest=LifecycleFlowTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

Commit:

```bash
git add core/common/src/main/java/pro/deta/orion/lifecycle/flow core/common/src/test/java/pro/deta/orion/lifecycle/flow/LifecycleFlowTest.java
git commit -m "feat: define explicit lifecycle flow"
```

## Task 3: Execute Lifecycle Through LifecycleFlow

**Files:**

- Modify: `core/common/src/main/java/pro/deta/orion/lifecycle/OrionApplicationLifecycle.java`
- Modify: `core/common/src/test/java/pro/deta/orion/lifecycle/OrionApplicationLifecycleTest.java`

**Step 1: Write failing flow execution tests**

Extend `OrionApplicationLifecycleTest`:

```java
@Test
void exposesStartupAndShutdownFlowDescriptions() {
    try (TestLifecycleContext context = new TestLifecycleContext(Set.of())) {
        assertThat(context.lifecycle().describeFlows()).contains(
                "STARTUP:",
                "INIT -> STARTING",
                "STARTING -> UP",
                "SHUTDOWN:",
                "UP -> BEGIN_SHUTDOWN",
                "STOPPING -> OFF");
    }
}
```

Add a test that proves `BEGIN_SHUTDOWN` is now visible in the flow:

```java
@Test
void beginShutdownUsesShutdownFlow() {
    try (TestLifecycleContext context = new TestLifecycleContext(Set.of())) {
        context.lifecycle().runApplication();
        context.lifecycle().beginShutdown();
        context.lifecycle().waitForShutdown();

        assertThat(context.stateHolder().getState()).isEqualTo(ApplicationState.OFF);
    }
}
```

**Step 2: Run tests and verify failure**

Run:

```bash
mvn test -Pdev -pl core/common -Dtest=OrionApplicationLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because lifecycle still uses private hard-coded methods and does not expose descriptions.

**Step 3: Replace hard-coded startup with runFlow**

In `OrionApplicationLifecycle`, add:

```java
private ApplicationState runFlow(LifecycleFlow flow) {
    for (LifecycleStep step : flow.steps()) {
        if (step.runListeners() && !onStage(step.from())) {
            applicationStateHolder.moveStateFrom(step.from(), step.failure());
            return applicationStateHolder.getState();
        }
        applicationStateHolder.moveStateFrom(step.from(), step.success());
    }
    return applicationStateHolder.getState();
}
```

Change:

```java
public ApplicationState runApplication() {
    return runFlow(LifecycleFlow.STARTUP);
}
```

Remove or stop using `onInitStage()`, `onStartStage()`, and `switchStage(...)` after tests pass.

**Step 4: Replace shutdown with shutdown flow**

Change:

```java
private void doShutdown() {
    log.info("System shutdown process initiated.");
    runFlow(LifecycleFlow.SHUTDOWN);
}

public void beginShutdown() {
    doInLock(() -> {
        orionProvider.getOrionExecutor().submit(this::doShutdown);
    });
}
```

Important: `runFlow(LifecycleFlow.SHUTDOWN)` owns `UP -> BEGIN_SHUTDOWN`, so do not move state to `BEGIN_SHUTDOWN` before calling it.

**Step 5: Add describeFlows**

Add:

```java
public String describeFlows() {
    return LifecycleFlow.STARTUP.describe() + "\n" + LifecycleFlow.SHUTDOWN.describe();
}
```

**Step 6: Run tests and commit**

Run:

```bash
mvn test -Pdev -pl core/common -Dtest=LifecycleFlowTest,OrionApplicationLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

Commit:

```bash
git add core/common/src/main/java/pro/deta/orion/lifecycle/OrionApplicationLifecycle.java core/common/src/test/java/pro/deta/orion/lifecycle/OrionApplicationLifecycleTest.java
git commit -m "refactor: execute lifecycle from explicit flow"
```

## Task 4: Add Named Lifecycle Task IDs

**Files:**

- Create: `core/common/src/main/java/pro/deta/orion/lifecycle/task/LifecycleTaskId.java`
- Create: `core/common/src/main/java/pro/deta/orion/lifecycle/task/OrionLifecycleTasks.java`
- Create: `core/common/src/test/java/pro/deta/orion/lifecycle/task/LifecycleTaskIdTest.java`

**Step 1: Write failing task id tests**

Create:

```java
class LifecycleTaskIdTest {
    @Test
    void taskIdsHaveReadableNames() {
        assertThat(OrionLifecycleTasks.ACL_LOAD.toString()).isEqualTo("ACL_LOAD");
        assertThat(OrionLifecycleTasks.TRANSPORTS_START.toString()).isEqualTo("TRANSPORTS_START");
    }

    @Test
    void taskIdsRejectBlankNames() {
        assertThatThrownBy(() -> new LifecycleTaskId(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

**Step 2: Run tests and verify failure**

Run:

```bash
mvn test -Pdev -pl core/common -Dtest=LifecycleTaskIdTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because classes do not exist.

**Step 3: Implement task id**

Create:

```java
package pro.deta.orion.lifecycle.task;

import java.util.Objects;

public record LifecycleTaskId(String value) {
    public LifecycleTaskId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Lifecycle task id must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
```

Create:

```java
package pro.deta.orion.lifecycle.task;

public final class OrionLifecycleTasks {
    public static final LifecycleTaskId JGIT_RUNTIME = new LifecycleTaskId("JGIT_RUNTIME");
    public static final LifecycleTaskId EVENT_MANAGER = new LifecycleTaskId("EVENT_MANAGER");
    public static final LifecycleTaskId ACL_INIT = new LifecycleTaskId("ACL_INIT");
    public static final LifecycleTaskId GIT_BACKED_INTERNAL_STORAGE_INIT =
            new LifecycleTaskId("GIT_BACKED_INTERNAL_STORAGE_INIT");
    public static final LifecycleTaskId REPOSITORY_STORAGE = new LifecycleTaskId("REPOSITORY_STORAGE");
    public static final LifecycleTaskId ACL_LOAD = new LifecycleTaskId("ACL_LOAD");
    public static final LifecycleTaskId TRANSPORTS_START = new LifecycleTaskId("TRANSPORTS_START");
    public static final LifecycleTaskId HTTP_TRANSPORT_START = new LifecycleTaskId("HTTP_TRANSPORT_START");
    public static final LifecycleTaskId GIT_TRANSPORT_START = new LifecycleTaskId("GIT_TRANSPORT_START");
    public static final LifecycleTaskId SSH_TRANSPORT_START = new LifecycleTaskId("SSH_TRANSPORT_START");
    public static final LifecycleTaskId TRANSPORTS_STOP = new LifecycleTaskId("TRANSPORTS_STOP");
    public static final LifecycleTaskId EVENT_MANAGER_STOP = new LifecycleTaskId("EVENT_MANAGER_STOP");
    public static final LifecycleTaskId EXECUTOR_STOP = new LifecycleTaskId("EXECUTOR_STOP");

    private OrionLifecycleTasks() {
    }
}
```

**Step 4: Run tests and commit**

Run:

```bash
mvn test -Pdev -pl core/common -Dtest=LifecycleTaskIdTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

Commit:

```bash
git add core/common/src/main/java/pro/deta/orion/lifecycle/task core/common/src/test/java/pro/deta/orion/lifecycle/task/LifecycleTaskIdTest.java
git commit -m "feat: add named lifecycle task ids"
```

## Task 5: Add Lifecycle Task Registration API Beside Existing Priority API

**Files:**

- Create: `core/common/src/main/java/pro/deta/orion/lifecycle/task/LifecycleTaskRegistration.java`
- Create: `core/common/src/main/java/pro/deta/orion/lifecycle/task/LifecycleTaskDefinition.java`
- Modify: `core/common/src/main/java/pro/deta/orion/lifecycle/ApplicationStateListenerRegistrar.java`
- Modify: `core/common/src/main/java/pro/deta/orion/lifecycle/OrionApplicationLifecycle.java`
- Create: `core/common/src/test/java/pro/deta/orion/lifecycle/task/LifecycleTaskRegistrationTest.java`

**Step 1: Write failing registration tests**

Create tests:

```java
@Test
void taskRegistrationCapturesDependencies() {
    LifecycleTaskRegistration registration = new LifecycleTaskRegistration(
            ApplicationState.STARTING,
            OrionLifecycleTasks.ACL_LOAD,
            () -> OrionStageCallResult.EMPTY);

    registration.after(OrionLifecycleTasks.REPOSITORY_STORAGE);

    assertThat(registration.definition().after()).containsExactly(OrionLifecycleTasks.REPOSITORY_STORAGE);
}
```

Add registrar API test through a fake listener:

```java
OrionApplicationStageEventListener acl = registrar ->
        registrar.task(ApplicationState.STARTING, OrionLifecycleTasks.ACL_LOAD, () -> OrionStageCallResult.EMPTY)
                .after(OrionLifecycleTasks.REPOSITORY_STORAGE);
```

**Step 2: Run tests and verify failure**

Run:

```bash
mvn test -Pdev -pl core/common -Dtest=LifecycleTaskRegistrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because task registration API does not exist.

**Step 3: Implement definitions**

Create immutable definition:

```java
public record LifecycleTaskDefinition(
        ApplicationState phase,
        LifecycleTaskId id,
        Callable<OrionStageCallResult> call,
        List<LifecycleTaskId> after,
        int waitForCompletionSecs
) {
}
```

Create mutable builder-style registration:

```java
public final class LifecycleTaskRegistration {
    private final ApplicationState phase;
    private final LifecycleTaskId id;
    private final Callable<OrionStageCallResult> call;
    private final List<LifecycleTaskId> after = new ArrayList<>();
    private int waitForCompletionSecs;

    public LifecycleTaskRegistration after(LifecycleTaskId dependency) { ... }
    public LifecycleTaskRegistration waitForCompletionSecs(int seconds) { ... }
    public LifecycleTaskDefinition definition() { ... }
}
```

**Step 4: Add registrar method**

In `ApplicationStateListenerRegistrar` add:

```java
default LifecycleTaskRegistration task(
        ApplicationState phase,
        LifecycleTaskId id,
        Callable<OrionStageCallResult> call) {
    LifecycleTaskRegistration registration = new LifecycleTaskRegistration(phase, id, call);
    register(registration);
    return registration;
}

LifecycleTaskRegistration register(LifecycleTaskRegistration registration);
```

In `OrionApplicationLifecycle`, add a list:

```java
private final List<LifecycleTaskRegistration> lifecycleTaskRegistrations = new CopyOnWriteArrayList<>();
```

and implement `register(LifecycleTaskRegistration registration)`.

**Step 5: Run tests and commit**

Run:

```bash
mvn test -Pdev -pl core/common -Dtest=LifecycleTaskRegistrationTest,OrionApplicationLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS, with old listener API still working.

Commit:

```bash
git add core/common/src/main/java/pro/deta/orion/lifecycle/ApplicationStateListenerRegistrar.java core/common/src/main/java/pro/deta/orion/lifecycle/OrionApplicationLifecycle.java core/common/src/main/java/pro/deta/orion/lifecycle/task core/common/src/test/java/pro/deta/orion/lifecycle/task/LifecycleTaskRegistrationTest.java
git commit -m "feat: add explicit lifecycle task registration"
```

## Task 6: Build and Validate LifecycleTaskPlan

**Files:**

- Create: `core/common/src/main/java/pro/deta/orion/lifecycle/task/LifecycleTaskPlan.java`
- Create: `core/common/src/main/java/pro/deta/orion/lifecycle/task/LifecycleTaskPlanner.java`
- Create: `core/common/src/test/java/pro/deta/orion/lifecycle/task/LifecycleTaskPlannerTest.java`

**Step 1: Write failing planner tests**

Add tests:

```java
@Test
void ordersTasksByDependencies() {
    LifecycleTaskDefinition storage = task(REPOSITORY_STORAGE);
    LifecycleTaskDefinition acl = task(ACL_LOAD, after(REPOSITORY_STORAGE));
    LifecycleTaskDefinition transports = task(TRANSPORTS_START, after(ACL_LOAD));

    LifecycleTaskPlan plan = LifecycleTaskPlanner.plan(ApplicationState.STARTING, List.of(transports, acl, storage));

    assertThat(plan.executionGroups())
            .flatExtracting(group -> group.tasks().stream().map(LifecycleTaskDefinition::id).toList())
            .containsExactly(REPOSITORY_STORAGE, ACL_LOAD, TRANSPORTS_START);
}
```

Add validation tests:

- duplicate task id in one phase fails;
- dependency on unknown task id fails;
- dependency cycle fails;
- dependent tasks declare their prerequisites with `after(...)`.

**Step 2: Run tests and verify failure**

Run:

```bash
mvn test -Pdev -pl core/common -Dtest=LifecycleTaskPlannerTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because planner does not exist.

**Step 3: Implement planner**

Implement a small topological sort using loops, not streams by default.

Plan shape:

```java
public record LifecycleTaskPlan(ApplicationState phase, List<ExecutionGroup> executionGroups) {
    public record ExecutionGroup(List<LifecycleTaskDefinition> tasks) {
    }

    public String describe() { ... }
}
```

Planner rules:

- only definitions for the requested phase are considered;
- duplicate ids fail fast;
- missing dependency ids fail fast;
- cycles fail with a message that includes at least one task id in the cycle;
- tasks with no dependencies can share the same execution group.

**Step 4: Add readable plan description**

Example output:

```text
STARTING:
  1. REPOSITORY_STORAGE
  2. ACL_LOAD after REPOSITORY_STORAGE
  3. TRANSPORTS_START after ACL_LOAD
```

**Step 5: Run tests and commit**

Run:

```bash
mvn test -Pdev -pl core/common -Dtest=LifecycleTaskPlannerTest,LifecycleTaskRegistrationTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

Commit:

```bash
git add core/common/src/main/java/pro/deta/orion/lifecycle/task/LifecycleTaskPlan.java core/common/src/main/java/pro/deta/orion/lifecycle/task/LifecycleTaskPlanner.java core/common/src/test/java/pro/deta/orion/lifecycle/task/LifecycleTaskPlannerTest.java
git commit -m "feat: validate lifecycle task dependencies"
```

## Task 7: Execute New Lifecycle Tasks

**Files:**

- Modify: `core/common/src/main/java/pro/deta/orion/lifecycle/OrionApplicationLifecycle.java`
- Modify: `core/common/src/test/java/pro/deta/orion/lifecycle/OrionApplicationLifecycleTest.java`
- Create: `core/common/src/test/java/pro/deta/orion/lifecycle/task/LifecycleTaskExecutionTest.java`

**Step 1: Write failing execution tests**

Create tests:

```java
@Test
void explicitTasksRunInDependencyOrder() {
    List<String> events = Collections.synchronizedList(new ArrayList<>());
    OrionApplicationStageEventListener listener = registrar -> {
        registrar.task(ApplicationState.STARTING, REPOSITORY_STORAGE, () -> {
            events.add("storage");
            return OrionStageCallResult.EMPTY;
        });
        registrar.task(ApplicationState.STARTING, ACL_LOAD, () -> {
            events.add("acl");
            return OrionStageCallResult.EMPTY;
        }).after(REPOSITORY_STORAGE);
        registrar.task(ApplicationState.STARTING, TRANSPORTS_START, () -> {
            events.add("transports");
            return OrionStageCallResult.EMPTY;
        }).after(ACL_LOAD);
    };

    runLifecycle(Set.of(listener));

    assertThat(events).containsExactly("storage", "acl", "transports");
}
```

Add failure propagation test:

```java
@Test
void failingExplicitTaskMovesApplicationToFailed() {
    // Register a failing named task and assert STARTING fails without running dependent tasks.
}
```

**Step 2: Run tests and verify failure**

Run:

```bash
mvn test -Pdev -pl core/common -Dtest=LifecycleTaskExecutionTest,OrionApplicationLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because lifecycle still ignores explicit task registrations.

**Step 3: Execute explicit tasks first if present**

In `onStage(ApplicationState state)`, change behavior:

- collect task registrations for `state`;
- build `LifecycleTaskPlan` and execute it when tasks exist;
- return success when the phase has no registered tasks.

**Step 4: Implement task execution**

For each execution group:

- submit all tasks in the group to `OrionExecutor`;
- wait for every task callable before moving to the next group;
- if a task returns `OrionStageCallResult` with futures to wait for, wait for those futures before moving to the next group.

Keep task execution results local to `OrionApplicationLifecycle`.

**Step 5: Run tests and commit**

Run:

```bash
mvn test -Pdev -pl core/common -Dtest=LifecycleTaskExecutionTest,OrionApplicationLifecycleTest,LifecycleTaskPlannerTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

Commit:

```bash
git add core/common/src/main/java/pro/deta/orion/lifecycle/OrionApplicationLifecycle.java core/common/src/test/java/pro/deta/orion/lifecycle/OrionApplicationLifecycleTest.java core/common/src/test/java/pro/deta/orion/lifecycle/task/LifecycleTaskExecutionTest.java
git commit -m "feat: execute explicit lifecycle tasks"
```

## Task 8: Migrate Core INIT Tasks To Explicit Task API

**Files:**

- Modify: `core/git-engine/src/main/java/pro/deta/orion/git/OrionJGitRuntime.java`
- Modify: `core/common/src/main/java/pro/deta/orion/event/OrionEventManager.java`
- Modify: `core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java`
- Modify: `core/acl/src/main/java/pro/deta/orion/acl/storage/GitAccessControlStorage.java`
- Modify: `core/common/src/test/java/pro/deta/orion/lifecycle/OrionApplicationLifecycleTest.java`

**Step 1: Write failing order test**

Add a test that asserts plan description for INIT contains explicit ids in order:

```java
assertThat(context.lifecycle().describeTaskPlan(ApplicationState.INIT)).contains(
        "JGIT_RUNTIME",
        "EVENT_MANAGER",
        "ACL_INIT");
```

Add a stronger ordering assertion if `describeTaskPlan` returns machine-readable groups.

**Step 2: Run tests and verify failure**

Run:

```bash
mvn test -Pdev -pl core/common,core/acl,core/git-engine -am -Dtest=OrionApplicationLifecycleTest,OrionJGitRuntimeTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL until components migrate.

**Step 3: Migrate JGit runtime**

Change:

```java
registrar.task(ApplicationState.INIT, OrionLifecycleTasks.JGIT_RUNTIME, this::install);
```

to:

```java
registrar.task(ApplicationState.INIT, OrionLifecycleTasks.JGIT_RUNTIME, this::install);
```

**Step 4: Migrate event manager**

Register:

```java
registrar.task(ApplicationState.INIT, OrionLifecycleTasks.EVENT_MANAGER, this::onInit)
        .after(OrionLifecycleTasks.JGIT_RUNTIME)
        .waitForCompletionSecs(2);
```

**Step 5: Migrate ACL init**

Register:

```java
registrar.task(ApplicationState.INIT, OrionLifecycleTasks.ACL_INIT, this::onInit)
        .after(OrionLifecycleTasks.EVENT_MANAGER);
```

**Step 6: Migrate internal Git-backed storage init**

Register:

```java
registrar.task(ApplicationState.INIT, OrionLifecycleTasks.GIT_BACKED_INTERNAL_STORAGE_INIT, this::onInit)
        .after(OrionLifecycleTasks.ACL_INIT);
```

Keep the `isEnabled()` guard inside `GitAccessControlStorage.registerToStage`.

**Step 7: Run tests and commit**

Run:

```bash
mvn test -Pdev -pl core/common,core/acl,core/git-engine -am -Dtest=OrionApplicationLifecycleTest,OrionJGitRuntimeTest,AccessControlStorageTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

Commit:

```bash
git add core/git-engine/src/main/java/pro/deta/orion/git/OrionJGitRuntime.java core/common/src/main/java/pro/deta/orion/event/OrionEventManager.java core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java core/acl/src/main/java/pro/deta/orion/acl/storage/GitAccessControlStorage.java core/common/src/test/java/pro/deta/orion/lifecycle/OrionApplicationLifecycleTest.java
git commit -m "refactor: migrate init lifecycle tasks"
```

## Task 9: Migrate STARTING Tasks To Explicit Task API

**Files:**

- Modify: `core/git-storage/src/main/java/pro/deta/orion/git/storage/GitBackedInternalStorage.java`
- Modify: `core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java`
- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/JettyHTTPServer.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/GitNativeTransportService.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/GitSshTransportService.java`
- Modify: `core/common/src/test/java/pro/deta/orion/lifecycle/OrionApplicationLifecycleTest.java`

**Step 1: Write failing STARTING plan test**

Expected description:

```text
STARTING:
  REPOSITORY_STORAGE
  ACL_LOAD after REPOSITORY_STORAGE
  TRANSPORTS_START after ACL_LOAD
  HTTP_TRANSPORT_START after TRANSPORTS_START
  GIT_TRANSPORT_START after TRANSPORTS_START
  SSH_TRANSPORT_START after TRANSPORTS_START
```

Add assertion:

```java
assertThat(context.lifecycle().describeTaskPlan(ApplicationState.STARTING)).contains(
        "REPOSITORY_STORAGE",
        "ACL_LOAD after REPOSITORY_STORAGE",
        "TRANSPORTS_START after ACL_LOAD",
        "HTTP_TRANSPORT_START after TRANSPORTS_START");
```

**Step 2: Run tests and verify failure**

Run:

```bash
mvn test -Pdev -pl core/common,core/acl,core/git-storage,net/http-core,net/git-transport -am -Dtest=OrionApplicationLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL until registrations migrate.

**Step 3: Migrate repository storage**

In `GitBackedInternalStorage`:

```java
registrar.task(ApplicationState.STARTING, OrionLifecycleTasks.REPOSITORY_STORAGE, this::onStart);
```

**Step 4: Migrate ACL load**

In `OrionAccessControlServiceImpl`:

```java
registrar.task(ApplicationState.STARTING, OrionLifecycleTasks.ACL_LOAD, this::onStart)
        .after(OrionLifecycleTasks.REPOSITORY_STORAGE);
```

This task also fixes the currently confusing transport-before-ACL ordering.

**Step 5: Add transport barrier task**

If useful, add a no-op task owned by lifecycle or a small static helper:

```java
registrar.task(ApplicationState.STARTING, OrionLifecycleTasks.TRANSPORTS_START, () -> OrionStageCallResult.EMPTY)
        .after(OrionLifecycleTasks.ACL_LOAD);
```

If adding no-op task inside the lifecycle core feels too magical, make each transport depend directly on `ACL_LOAD`.

**Step 6: Migrate HTTP transport**

In `JettyHTTPServer`:

```java
registrar.task(ApplicationState.STARTING, OrionLifecycleTasks.HTTP_TRANSPORT_START, this::onStart)
        .after(OrionLifecycleTasks.TRANSPORTS_START);
```

**Step 7: Migrate Git native and SSH transports**

Use:

```java
.after(OrionLifecycleTasks.TRANSPORTS_START)
```

Each transport start task should complete only after its server socket is ready or after startup has failed.
Document this in a test or comment only if non-obvious.

**Step 8: Run tests and commit**

Run:

```bash
mvn test -Pdev -pl core/common,core/acl,core/git-storage,net/http-core,net/git-transport -am -Dtest=OrionApplicationLifecycleTest,AccessControlStorageTest,JettyHTTPServerTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

Commit:

```bash
git add core/git-storage/src/main/java/pro/deta/orion/git/storage/GitBackedInternalStorage.java core/acl/src/main/java/pro/deta/orion/acl/OrionAccessControlServiceImpl.java net/http-core/src/main/java/pro/deta/orion/transport/http/JettyHTTPServer.java net/git-transport/src/main/java/pro/deta/orion/transport/git/GitNativeTransportService.java net/git-transport/src/main/java/pro/deta/orion/transport/git/GitSshTransportService.java core/common/src/test/java/pro/deta/orion/lifecycle/OrionApplicationLifecycleTest.java
git commit -m "refactor: migrate startup lifecycle tasks"
```

## Task 10: Migrate STOPPING Tasks To Explicit Task API

**Files:**

- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/JettyHTTPServer.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/GitNativeTransportService.java`
- Modify: `net/git-transport/src/main/java/pro/deta/orion/transport/git/GitSshTransportService.java`
- Modify: `core/common/src/main/java/pro/deta/orion/event/OrionEventManager.java`
- Modify: `core/common/src/main/java/pro/deta/orion/internal/OrionExecutor.java`
- Modify: `core/common/src/test/java/pro/deta/orion/lifecycle/OrionApplicationLifecycleTest.java`

**Step 1: Write failing STOPPING plan test**

Expected:

```text
STOPPING:
  TRANSPORTS_STOP
  HTTP_TRANSPORT_STOP after TRANSPORTS_STOP
  GIT_TRANSPORT_STOP after TRANSPORTS_STOP
  SSH_TRANSPORT_STOP after TRANSPORTS_STOP
  EVENT_MANAGER_STOP after HTTP_TRANSPORT_STOP, GIT_TRANSPORT_STOP, SSH_TRANSPORT_STOP
  EXECUTOR_STOP after EVENT_MANAGER_STOP
```

If separate stop task ids are needed, add them to `OrionLifecycleTasks`.

**Step 2: Run tests and verify failure**

Run:

```bash
mvn test -Pdev -pl core/common,net/http-core,net/git-transport -am -Dtest=OrionApplicationLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL until stop registrations migrate.

**Step 3: Add stop task ids if needed**

Add constants:

```java
HTTP_TRANSPORT_STOP
GIT_TRANSPORT_STOP
SSH_TRANSPORT_STOP
```

**Step 4: Migrate stop methods**

Each transport stop task should depend on `TRANSPORTS_STOP` or directly be part of the first stop group.

Event manager should stop after transports.

Executor should stop last:

```java
registrar.task(ApplicationState.STOPPING, OrionLifecycleTasks.EXECUTOR_STOP, this::onStop)
        .after(OrionLifecycleTasks.EVENT_MANAGER_STOP);
```

**Step 5: Run tests and commit**

Run:

```bash
mvn test -Pdev -pl core/common,net/http-core,net/git-transport -am -Dtest=OrionApplicationLifecycleTest,JettyHTTPServerTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

Commit:

```bash
git add net/http-core/src/main/java/pro/deta/orion/transport/http/JettyHTTPServer.java net/git-transport/src/main/java/pro/deta/orion/transport/git/GitNativeTransportService.java net/git-transport/src/main/java/pro/deta/orion/transport/git/GitSshTransportService.java core/common/src/main/java/pro/deta/orion/event/OrionEventManager.java core/common/src/main/java/pro/deta/orion/internal/OrionExecutor.java core/common/src/test/java/pro/deta/orion/lifecycle/OrionApplicationLifecycleTest.java
git commit -m "refactor: migrate shutdown lifecycle tasks"
```

## Task 11: Remove Priority-Based Registration

**Files:**

- Modify: `core/common/src/main/java/pro/deta/orion/lifecycle/ApplicationStateListenerRegistrar.java`
- Modify: `core/common/src/test/java/pro/deta/orion/lifecycle/OrionApplicationLifecycleTest.java`

**Step 1: Search for old API usage**

Run:

```bash
rg -n "register\\(ApplicationState|\\.priority\\(|\\.waitForCompletion" core net integration tests -g '*.java'
```

Expected: no runtime code uses the old priority API after Tasks 8-10.

**Step 2: Remove old registration surface**

Remove the old `register` overload and priority-based listener execution path. Keep only:

```java
LifecycleTaskRegistration register(LifecycleTaskRegistration registration);
```

**Step 3: Update tests**

Remove compatibility tests for priority-based listeners and keep coverage on named task execution.

**Step 4: Run tests and commit**

Run:

```bash
mvn test -Pdev -pl core/common -Dtest=OrionApplicationLifecycleTest,LifecycleTaskExecutionTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

Commit:

```bash
git add core/common/src/main/java/pro/deta/orion/lifecycle/ApplicationStateListenerRegistrar.java core/common/src/main/java/pro/deta/orion/lifecycle/OrionApplicationLifecycle.java core/common/src/test/java/pro/deta/orion/lifecycle/OrionApplicationLifecycleTest.java
git commit -m "refactor: remove priority lifecycle registration"
```

## Task 12: Add Lifecycle Diagnostics

**Files:**

- Modify: `core/common/src/main/java/pro/deta/orion/lifecycle/OrionApplicationLifecycle.java`
- Create: `core/common/src/main/java/pro/deta/orion/lifecycle/task/LifecyclePlanDescription.java` if formatting grows too large.
- Modify: `core/common/src/test/java/pro/deta/orion/lifecycle/OrionApplicationLifecycleTest.java`

**Step 1: Write failing diagnostics test**

Add:

```java
@Test
void describeLifecycleShowsFlowsAndTaskPlans() {
    try (TestLifecycleContext context = new TestLifecycleContext(Set.of(startingTasks()))) {
        assertThat(context.lifecycle().describeLifecycle()).contains(
                "STARTUP:",
                "INIT -> STARTING",
                "STARTING:",
                "REPOSITORY_STORAGE",
                "ACL_LOAD after REPOSITORY_STORAGE");
    }
}
```

**Step 2: Run tests and verify failure**

Run:

```bash
mvn test -Pdev -pl core/common -Dtest=OrionApplicationLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because `describeLifecycle()` does not exist.

**Step 3: Implement diagnostics**

Add:

```java
public String describeLifecycle() {
    StringBuilder builder = new StringBuilder();
    builder.append(describeFlows()).append('\n');
    for (ApplicationState phase : ApplicationState.values()) {
        String taskPlan = describeTaskPlan(phase);
        if (!taskPlan.isBlank()) {
            builder.append(taskPlan).append('\n');
        }
    }
    return builder.toString();
}
```

Add logging at startup:

```java
log.debug("Lifecycle service map before initialization:\n{}", describeServiceMap());
log.debug("Lifecycle plan:\n{}", describeLifecycle());
```

Keep lifecycle dumps at `debug` because they are diagnostic startup output.

**Step 4: Run tests and commit**

Run:

```bash
mvn test -Pdev -pl core/common -Dtest=OrionApplicationLifecycleTest,LifecycleTaskPlannerTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

Commit:

```bash
git add core/common/src/main/java/pro/deta/orion/lifecycle/OrionApplicationLifecycle.java core/common/src/main/java/pro/deta/orion/lifecycle/task/LifecyclePlanDescription.java core/common/src/test/java/pro/deta/orion/lifecycle/OrionApplicationLifecycleTest.java
git commit -m "feat: describe lifecycle flow and task plan"
```

## Task 13: Documentation

**Files:**

- Create: `docs/lifecycle.md`
- Modify: `README.md`

**Step 1: Write lifecycle documentation**

Document:

- the phase flow table;
- what each phase means;
- how to register a new lifecycle task;
- how `after(...)` defines execution barriers;
- how `OrionStageCallResult` waits for background futures;
- why integer priorities are deprecated.

Include example:

```java
registrar.task(ApplicationState.STARTING, MY_SERVICE_START, this::onStart)
        .after(OrionLifecycleTasks.ACL_LOAD);
```

**Step 2: Link from README**

Add a short section:

```markdown
# Lifecycle

Startup and shutdown order is defined by explicit lifecycle flows and named task dependencies.
See [docs/lifecycle.md](docs/lifecycle.md).
```

**Step 3: Check docs references**

Run:

```bash
rg -n "priority\\(|waitForCompletion\\(|Lifecycle" README.md docs core/common/src/main/java
```

Expected: docs explain new API; old priority references only appear in deprecated compatibility code.

**Step 4: Commit**

```bash
git add docs/lifecycle.md README.md
git commit -m "docs: document application lifecycle flow"
```

## Task 14: Final Verification

**Files:**

- No planned source changes.

**Step 1: Run lifecycle-focused tests**

Run:

```bash
mvn test -Pdev -pl core/common -Dtest=LifecycleFlowTest,LifecycleTaskIdTest,LifecycleTaskRegistrationTest,LifecycleTaskPlannerTest,LifecycleTaskExecutionTest,OrionApplicationLifecycleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 2: Run affected module tests**

Run:

```bash
mvn test -Pdev -pl core/common,core/acl,core/git-storage,core/git-engine,net/http-core,net/git-transport,core/bootstrap -am -Dtest=OrionApplicationLifecycleTest,AccessControlStorageTest,FileGitRepositoryProviderTest,JettyHTTPServerTest,OrionRuntimeModuleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 3: Run full regular test suite**

Run:

```bash
mvn test -Pdev
```

Expected: PASS.

If it fails once with JGit temporary `.probe-*` cleanup, rerun once and report both outputs. Do not hide a repeated failure.

**Step 4: Inspect old API usage**

Run:

```bash
rg -n "register\\(ApplicationState|\\.priority\\(|\\.waitForCompletion" core net integration tests -g '*.java'
```

Expected: only deprecated compatibility code and explicit compatibility tests remain.

**Step 5: Inspect working tree**

Run:

```bash
git status --short
```

Expected: clean.

**Step 6: Final report**

Report:

- final flow description;
- task plan description for `INIT`, `STARTING`, and `STOPPING`;
- commits created;
- test commands and results;
- remaining deprecated APIs, if any.
