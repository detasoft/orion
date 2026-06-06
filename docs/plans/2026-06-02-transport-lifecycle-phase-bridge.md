# Generic Lifecycle State Machine Adapters Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Introduce reusable lifecycle state-machine adapters before migrating more application lifecycle code onto state machines.

**Architecture:** Keep the already-committed `TransportLifecycleBarrier` phase bridge as a temporary compatibility layer for the old task graph. Build two reusable primitives in `core/lifecycle-state-machine`: one adapter for leaf services with `start/stop/enabled/running` behavior, and one adapter for aggregate/group machines that propagate `START` and `STOP` to children. Then migrate one leaf transport and the transport aggregate to prove the API against production-shaped code without starting the root application lifecycle migration yet.

**Tech Stack:** Java 21, JUnit 5, Maven with the `dev` profile, Orion lifecycle state-machine module, existing transport adapters.

---

## Baseline

The earlier phase-bridge part is already complete in commit `ddd5f68`:

- `TransportLifecycleBarrier` is documented as a no-op legacy phase bridge.
- `TransportLifecycleBarrierTest` verifies that it registers only `TRANSPORTS_START/STOP` anchors.
- Full `mvn test -Pdev` passed after that commit.

Do not continue the old plan's Task 3/Task 4 as-is. The next useful step is to reduce state-machine adapter boilerplate before expanding the application lifecycle tree.

### Task 1: Add Generic Leaf Service Adapter

**Files:**
- Create: `core/lifecycle-state-machine/src/main/java/pro/deta/orion/lifecycle/state/ServiceLifecycleStateMachineAdapter.java`
- Test: `core/lifecycle-state-machine/src/test/java/pro/deta/orion/lifecycle/state/ServiceLifecycleStateMachineAdapterTest.java`
- Read: `net/http-core/src/main/java/pro/deta/orion/transport/http/JettyHTTPServerStateMachine.java`
- Read: `net/git-transport/src/main/java/pro/deta/orion/transport/git/GitSshTransportStateMachine.java`

**Step 1: Write the failing test**

Create `ServiceLifecycleStateMachineAdapterTest` with these tests:

```java
package pro.deta.orion.lifecycle.state;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.DISABLED;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.ERR;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.FIN;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.RUNNING;

class ServiceLifecycleStateMachineAdapterTest {
    @Test
    void enabledServiceStartsToRunningAndStopsToFinished() {
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        ServiceLifecycleStateMachineAdapter adapter = new ServiceLifecycleStateMachineAdapter("service", lifecycle);

        StateTransitionResult start = adapter.start();

        assertThat(start.actionResult()).isSameAs(Void.EMPTY);
        assertThat(adapter.currentState()).isEqualTo(RUNNING);
        assertThat(lifecycle.startCalls).isEqualTo(1);

        adapter.stop();

        assertThat(adapter.currentState()).isEqualTo(FIN);
        assertThat(lifecycle.stopCalls).isEqualTo(1);
    }

    @Test
    void disabledServiceStartsToDisabledAfterCallingStartHook() {
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.enabled = false;
        lifecycle.running = false;
        ServiceLifecycleStateMachineAdapter adapter = new ServiceLifecycleStateMachineAdapter("service", lifecycle);

        adapter.start();

        assertThat(adapter.currentState()).isEqualTo(DISABLED);
        assertThat(lifecycle.startCalls).isEqualTo(1);
    }

    @Test
    void enabledServiceThatDoesNotRunMovesToError() {
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.running = false;
        ServiceLifecycleStateMachineAdapter adapter = new ServiceLifecycleStateMachineAdapter("service", lifecycle);

        adapter.start();

        assertThat(adapter.currentState()).isEqualTo(ERR);
    }

    @Test
    void startFailureMovesToErrorAndCanStillBeStopped() {
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        lifecycle.startFailure = new IllegalStateException("boom");
        ServiceLifecycleStateMachineAdapter adapter = new ServiceLifecycleStateMachineAdapter("service", lifecycle);

        assertThatThrownBy(adapter::start)
                .isInstanceOf(StateTransitionFailedException.class)
                .hasCause(lifecycle.startFailure);
        assertThat(adapter.currentState()).isEqualTo(ERR);

        adapter.stop();

        assertThat(adapter.currentState()).isEqualTo(FIN);
        assertThat(lifecycle.stopCalls).isEqualTo(1);
    }

    @Test
    void stopBeforeStartFinishesWithoutCallingStopHook() {
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        ServiceLifecycleStateMachineAdapter adapter = new ServiceLifecycleStateMachineAdapter("service", lifecycle);

        adapter.stop();

        assertThat(adapter.currentState()).isEqualTo(FIN);
        assertThat(lifecycle.startCalls).isZero();
        assertThat(lifecycle.stopCalls).isZero();
    }

    private static final class RecordingLifecycle implements ServiceLifecycleStateMachineAdapter.ServiceLifecycle {
        private boolean enabled = true;
        private boolean running = true;
        private int startCalls;
        private int stopCalls;
        private RuntimeException startFailure;

        @Override
        public void onStart() {
            startCalls++;
            if (startFailure != null) {
                throw startFailure;
            }
        }

        @Override
        public void onStop() {
            stopCalls++;
            running = false;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }
}
```

**Step 2: Run test to verify it fails**

Run:

```bash
mvn test -Pdev -q -pl core/lifecycle-state-machine -am -Dtest=ServiceLifecycleStateMachineAdapterTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because `ServiceLifecycleStateMachineAdapter` does not exist.

**Step 3: Implement the adapter**

Create `ServiceLifecycleStateMachineAdapter`:

```java
package pro.deta.orion.lifecycle.state;

import java.util.Objects;

import static pro.deta.orion.lifecycle.state.StandardStateDefinition.DISABLED;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.ERR;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.FIN;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.NEW;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.RUNNING;

/**
 * Reusable state-machine adapter for leaf services with synchronous start/stop lifecycle hooks.
 */
public final class ServiceLifecycleStateMachineAdapter {
    private final ServiceLifecycle lifecycle;
    private final ActionBinding<Void> start = ActionId.START.bind(this::startService);
    private final ActionBinding<Void> stop = ActionId.STOP.bind(this::stopService);
    private final StateMachineDefinition definition;
    private final StateMachine stateMachine;

    public ServiceLifecycleStateMachineAdapter(String name, ServiceLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        definition = StateMachineDefinition.define()
                .name(name)
                .from(NEW, DISABLED).on(start).to(DISABLED, RUNNING, ERR).post(this::resolveStartState)
                .from(NEW, DISABLED).on(stop).to(FIN, ERR)
                .from(RUNNING).on(stop).to(FIN, ERR)
                .from(ERR).on(stop).to(FIN, ERR)
                .build();
        stateMachine = definition.newStateMachine();
    }

    public StateMachineDefinition definition() {
        return definition;
    }

    public ActionBinding<Void> startAction() {
        return start;
    }

    public ActionBinding<Void> stopAction() {
        return stop;
    }

    public StateMachine stateMachine() {
        return stateMachine;
    }

    public StateMachineDefinition.State currentState() {
        return stateMachine.currentState();
    }

    public StateTransitionResult start() {
        return stateMachine.execute(start, Void.EMPTY);
    }

    public StateTransitionResult stop() {
        return stateMachine.execute(stop, Void.EMPTY);
    }

    private Void startService(Void ignored) throws Exception {
        lifecycle.onStart();
        return Void.EMPTY;
    }

    private StateMachineDefinition.State resolveStartState(StateTransitionResult result) {
        if (result.failed()) {
            return result.defaultState();
        }
        if (!lifecycle.isEnabled()) {
            return DISABLED;
        }
        return lifecycle.isRunning() ? RUNNING : ERR;
    }

    private Void stopService(Void ignored) throws Exception {
        StateMachineDefinition.State currentState = stateMachine.currentState();
        if (RUNNING.equals(currentState) || ERR.equals(currentState)) {
            lifecycle.onStop();
        }
        return Void.EMPTY;
    }

    public interface ServiceLifecycle {
        void onStart() throws Exception;

        void onStop() throws Exception;

        boolean isEnabled();

        boolean isRunning();
    }
}
```

If `StandardStateDefinition` does not expose `RUNNING` or `DISABLED`, add those states there first, with tests in `StateMachineTest` or this adapter test. Do not create duplicate state constants inside every adapter; states compare by name, but shared constants keep the API clear.

**Step 4: Run test to verify it passes**

Run:

```bash
mvn test -Pdev -q -pl core/lifecycle-state-machine -am -Dtest=ServiceLifecycleStateMachineAdapterTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 5: Commit**

```bash
git add core/lifecycle-state-machine/src/main/java/pro/deta/orion/lifecycle/state/ServiceLifecycleStateMachineAdapter.java core/lifecycle-state-machine/src/test/java/pro/deta/orion/lifecycle/state/ServiceLifecycleStateMachineAdapterTest.java core/lifecycle-state-machine/src/main/java/pro/deta/orion/lifecycle/state/StandardStateDefinition.java
git commit -m "feat: add generic service lifecycle adapter"
```

### Task 2: Migrate HTTP Transport Leaf Adapter

**Files:**
- Modify: `net/http-core/src/main/java/pro/deta/orion/transport/http/JettyHTTPServerStateMachine.java`
- Modify: `net/http-core/src/test/java/pro/deta/orion/transport/http/JettyHTTPServerStateMachineTest.java`
- Read: `core/lifecycle-state-machine/src/main/java/pro/deta/orion/lifecycle/state/ServiceLifecycleStateMachineAdapter.java`

**Step 1: Write the failing test**

Extend `JettyHTTPServerStateMachineTest` with behavior that proves the wrapper delegates to the generic adapter while preserving the public transport API:

```java
@Test
void stateMachineDefinitionComesFromGenericServiceAdapter() {
    RecordingJettyHTTPServer server = new RecordingJettyHTTPServer();
    JettyHTTPServerStateMachine machine = new JettyHTTPServerStateMachine(() -> server);

    assertEquals("http", machine.stateMachine().name());
    assertEquals(Set.of(machine.startAction().id(), machine.stopAction().id()), machine.stateMachine().availableActions());
}
```

Add the `java.util.Set` import.

**Step 2: Run test to verify it fails**

Run:

```bash
mvn test -Pdev -q -pl net/http-core -am -Dtest=JettyHTTPServerStateMachineTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: It may pass before migration because current handwritten code exposes the same behavior. If it passes, continue with the migration and rely on the existing plus new tests as the preservation contract. Do not change the runtime behavior to force an artificial red.

**Step 3: Replace handwritten state-machine wiring with the generic adapter**

Change `JettyHTTPServerStateMachine` to keep the same public methods and constants, but delegate state-machine construction and execution:

```java
private final ServiceLifecycleStateMachineAdapter adapter;

@Inject
public JettyHTTPServerStateMachine(Provider<JettyHTTPServer> serverProvider) {
    this.serverProvider = serverProvider;
    adapter = new ServiceLifecycleStateMachineAdapter("http", new ServiceLifecycleStateMachineAdapter.ServiceLifecycle() {
        @Override
        public void onStart() {
            resolveServer().onStart();
        }

        @Override
        public void onStop() {
            JettyHTTPServer currentServer = server;
            if (currentServer != null) {
                currentServer.onStop();
            }
        }

        @Override
        public boolean isEnabled() {
            return resolveServer().isEnabled();
        }

        @Override
        public boolean isRunning() {
            return resolveServer().isRunning();
        }
    });
}
```

Then delegate:

```java
public ActionBinding<Void> startAction() {
    return adapter.startAction();
}

public ActionBinding<Void> stopAction() {
    return adapter.stopAction();
}

public StateMachine stateMachine() {
    return adapter.stateMachine();
}

public State currentState() {
    return adapter.currentState();
}

public StateTransitionResult start() {
    return adapter.start();
}

public StateTransitionResult stop() {
    return adapter.stop();
}
```

Remove local `ActionBinding`, `StateMachineDefinition`, `StateMachine`, `startHttpTransport`, `stopHttpTransport`, and `resolveStartState` fields/methods that become unused. Preserve the class-level `@AiRule`.

**Step 4: Run test to verify it passes**

Run:

```bash
mvn test -Pdev -q -pl net/http-core -am -Dtest=JettyHTTPServerStateMachineTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 5: Commit**

```bash
git add net/http-core/src/main/java/pro/deta/orion/transport/http/JettyHTTPServerStateMachine.java net/http-core/src/test/java/pro/deta/orion/transport/http/JettyHTTPServerStateMachineTest.java
git commit -m "refactor: use generic adapter for http lifecycle"
```

### Task 3: Add Generic Aggregate Lifecycle Adapter

**Files:**
- Create: `core/lifecycle-state-machine/src/main/java/pro/deta/orion/lifecycle/state/AggregateLifecycleStateMachineAdapter.java`
- Test: `core/lifecycle-state-machine/src/test/java/pro/deta/orion/lifecycle/state/AggregateLifecycleStateMachineAdapterTest.java`
- Read: `net/transport/src/main/java/pro/deta/orion/transport/TransportLifecycleStateMachine.java`

**Step 1: Write the failing test**

Create `AggregateLifecycleStateMachineAdapterTest` with tests covering:

- sequential child propagation starts all children and resolves to `RUNNING` when at least one child is `RUNNING`;
- all-disabled children resolve aggregate to `DISABLED`;
- a child failure resolves aggregate to `ERR`;
- `stop()` propagates to children and reaches `FIN`.

Use simple child machines built with `ServiceLifecycleStateMachineAdapter`:

```java
@Test
void sequentialAggregateResolvesRunningWhenAnyChildRuns() {
    ServiceLifecycleStateMachineAdapter running = new ServiceLifecycleStateMachineAdapter(
            "running",
            new RecordingLifecycle(true, true));
    ServiceLifecycleStateMachineAdapter disabled = new ServiceLifecycleStateMachineAdapter(
            "disabled",
            new RecordingLifecycle(false, false));
    AggregateLifecycleStateMachineAdapter aggregate = AggregateLifecycleStateMachineAdapter.define("aggregate")
            .child("running", running.stateMachine())
            .child("disabled", disabled.stateMachine())
            .build();

    aggregate.start();

    assertThat(aggregate.currentState()).isEqualTo(StandardStateDefinition.RUNNING);
    assertThat(aggregate.childStatuses().get("running").state()).isEqualTo(StandardStateDefinition.RUNNING);
    assertThat(aggregate.childStatuses().get("disabled").state()).isEqualTo(StandardStateDefinition.DISABLED);
}
```

Add the other cases with the same pattern. Keep the test focused on aggregate policy, not transport classes.

**Step 2: Run test to verify it fails**

Run:

```bash
mvn test -Pdev -q -pl core/lifecycle-state-machine -am -Dtest=AggregateLifecycleStateMachineAdapterTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL because `AggregateLifecycleStateMachineAdapter` does not exist.

**Step 3: Implement the adapter**

Create a builder-based adapter around `AggregateStateMachine`:

```java
public final class AggregateLifecycleStateMachineAdapter {
    private final AggregateStateMachine aggregateStateMachine;

    public static Builder define(String name) {
        return new Builder(name);
    }

    public StateMachineDefinition.State currentState() {
        return aggregateStateMachine.currentState();
    }

    public Map<String, StateMachineStatus> childStatuses() {
        return aggregateStateMachine.childStatuses();
    }

    public StateMachineStatus status() {
        return aggregateStateMachine.status();
    }

    public AggregateStateMachine aggregateStateMachine() {
        return aggregateStateMachine;
    }

    public List<StateTransitionResult> start() {
        return aggregateStateMachine.start();
    }

    public List<StateTransitionResult> stop() {
        return aggregateStateMachine.stop();
    }
}
```

The builder should support:

```java
child(String name, StateMachine child)
childPropagationMode(StateMachineDefinition.ChildPropagationMode mode)
build()
```

Default `childPropagationMode` should be `SEQUENTIAL`. The generated definition should:

```java
.from(NEW, DISABLED).on(ActionId.START).to(DISABLED, RUNNING, ERR).post(this::resolveStartState)
.from(NEW, DISABLED).on(ActionId.STOP).to(FIN, ERR)
.from(RUNNING).on(ActionId.STOP).to(FIN, ERR)
.from(ERR).on(ActionId.STOP).to(FIN, ERR)
```

`resolveStartState` should:

- return `result.defaultState()` on failure;
- return `ERR` if any direct child state is `ERR`;
- return `RUNNING` if any direct child state is `RUNNING`;
- otherwise return `DISABLED`.

**Step 4: Run test to verify it passes**

Run:

```bash
mvn test -Pdev -q -pl core/lifecycle-state-machine -am -Dtest=AggregateLifecycleStateMachineAdapterTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 5: Commit**

```bash
git add core/lifecycle-state-machine/src/main/java/pro/deta/orion/lifecycle/state/AggregateLifecycleStateMachineAdapter.java core/lifecycle-state-machine/src/test/java/pro/deta/orion/lifecycle/state/AggregateLifecycleStateMachineAdapterTest.java
git commit -m "feat: add generic aggregate lifecycle adapter"
```

### Task 4: Migrate Transport Aggregate And Document Application Lifecycle Direction

**Files:**
- Modify: `net/transport/src/main/java/pro/deta/orion/transport/TransportLifecycleStateMachine.java`
- Modify: `net/transport/src/test/java/pro/deta/orion/transport/TransportLifecycleStateMachineTest.java`
- Modify: `core/lifecycle-state-machine/README.md`
- Read: `net/transport/src/main/java/pro/deta/orion/transport/TransportLifecycleBarrier.java`

**Step 1: Add or strengthen transport aggregate preservation tests**

In `TransportLifecycleStateMachineTest`, add a test that checks the transport aggregate still exposes the same child names and state resolution after migration:

```java
@Test
void transportAggregateUsesGenericAggregatePolicyWithStableChildNames() throws Exception {
    OrionConfiguration configuration = configuration(true, true, true);
    TransportLifecycleStateMachine machine = machine(configuration, new RecordingGitNativeTransportService(configuration));
    RecordingRegistrar registrar = new RecordingRegistrar();

    machine.registerToStage(registrar);
    registrar.definition(OrionLifecycleTasks.TRANSPORT_LIFECYCLE_START).call().call();

    assertEquals(StandardStateDefinition.RUNNING, machine.currentState());
    assertTrue(machine.aggregateStateMachine().childStatuses().containsKey("git-native"));
    assertTrue(machine.aggregateStateMachine().childStatuses().containsKey("git-ssh"));
    assertTrue(machine.aggregateStateMachine().childStatuses().containsKey("http"));
}
```

If an equivalent assertion already exists, keep it and do not duplicate the same check.

**Step 2: Run test before migration**

Run:

```bash
mvn test -Pdev -q -pl net/transport -am -Dtest=TransportLifecycleStateMachineTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS. This is a preservation test before refactor.

**Step 3: Refactor `TransportLifecycleStateMachine` to use `AggregateLifecycleStateMachineAdapter`**

Replace handwritten `StateMachineDefinition`/`AggregateStateMachine` wiring with:

```java
extends AggregateLifecycleStateMachineAdapter

super(AggregateLifecycleStateMachineAdapter.define("transports")
        .childPropagationMode(StateMachineDefinition.ChildPropagationMode.SEQUENTIAL)
        .child("git-native", gitNativeTransport.stateMachine())
        .child("git-ssh", gitSshTransport.stateMachine())
        .child("http", jettyHttpTransport.stateMachine())
        .buildAggregateStateMachine());
```

Keep these public methods stable:

```java
public AggregateStateMachine aggregateStateMachine()
public State currentState()
public List<StateTransitionResult> start()
public List<StateTransitionResult> stop()
```

If `definition()` is still needed only by tests, either delegate through the aggregate adapter or update tests to avoid raw definition access.

**Step 4: Add README direction**

In `core/lifecycle-state-machine/README.md`, add a short note near the aggregate section:

```markdown
Application lifecycle migration should build a root aggregate from reusable leaf and aggregate adapters. Keep phase
boundaries such as transport startup as explicit aggregate nodes, and keep concrete endpoint startup in child service
adapters. The root application machine can then choose startup and shutdown order without reintroducing lifecycle task
ids.
```

**Step 5: Run module checks**

Run:

```bash
mvn test -Pdev -q -pl core/lifecycle-state-machine,net/http-core,net/transport -am -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 6: Commit**

```bash
git add net/transport/src/main/java/pro/deta/orion/transport/TransportLifecycleStateMachine.java net/transport/src/test/java/pro/deta/orion/transport/TransportLifecycleStateMachineTest.java core/lifecycle-state-machine/README.md
git commit -m "refactor: use generic adapter for transport aggregate"
```

**Step 7: Final verification**

Run:

```bash
mvn verify -Pdev
```

Expected: PASS.

After the final commit, run the repository-required post-commit check:

```bash
mvn test -Pdev
```

Expected: PASS.

## Out Of Scope

- Do not replace `OrionApplicationLifecycle` or `LifecycleTaskPlanner` in this plan.
- Do not migrate `JGitRuntime` or `EventManager` yet.
- Do not introduce root application startup/shutdown ordering until service and aggregate adapters have been proven on transport code.
