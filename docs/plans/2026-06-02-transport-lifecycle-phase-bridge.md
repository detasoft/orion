# Transport Lifecycle Phase Bridge Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Document and test that `TransportLifecycleBarrier` is a transitional lifecycle phase bridge, not a transport endpoint starter.

**Architecture:** Keep the current two-layer transport lifecycle. `TRANSPORTS_START` and `TRANSPORTS_STOP` remain legacy task-graph phase boundaries for dependencies around transports. `TRANSPORT_LIFECYCLE_START` and `TRANSPORT_LIFECYCLE_STOP` remain the concrete aggregate state-machine tasks that start and stop transport endpoints.

**Tech Stack:** Java 21, JUnit 5, Maven with the `dev` profile, Orion lifecycle task planner, lifecycle state-machine module.

---

### Task 1: Add A Local Contract Test For The Phase Bridge

**Files:**
- Create: `net/transport/src/test/java/pro/deta/orion/transport/TransportLifecycleBarrierTest.java`
- Read: `net/transport/src/main/java/pro/deta/orion/transport/TransportLifecycleBarrier.java`

**Step 1: Write the failing test**

Create `TransportLifecycleBarrierTest` with a minimal in-test registrar. The test should prove the barrier registers only phase anchors and returns empty lifecycle results:

```java
package pro.deta.orion.transport;

import org.junit.jupiter.api.Test;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.lifecycle.ApplicationStateListenerRegistrar;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.lifecycle.task.LifecycleTaskDefinition;
import pro.deta.orion.lifecycle.task.LifecycleTaskId;
import pro.deta.orion.lifecycle.task.LifecycleTaskRegistration;
import pro.deta.orion.lifecycle.task.OrionLifecycleTasks;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransportLifecycleBarrierTest {
    @Test
    void registersTransportPhaseAnchorsWithoutStartingTransportEndpoints() throws Exception {
        OrionConfiguration configuration = new OrionConfiguration();
        configuration.getTransport().getGit().setEnabled(true);
        TransportLifecycleBarrier barrier = new TransportLifecycleBarrier(configuration);
        RecordingRegistrar registrar = new RecordingRegistrar();

        barrier.registerToStage(registrar);

        LifecycleTaskDefinition start = registrar.definition(OrionLifecycleTasks.TRANSPORTS_START);
        assertEquals(ApplicationState.STARTING, start.phase());
        assertEquals("TransportLifecycleBarrier", start.serviceName());
        assertEquals(OrionLifecycleTasks.ACL_LOAD, start.after().getFirst());
        assertSame(OrionStageCallResult.EMPTY, start.call().call());

        LifecycleTaskDefinition stop = registrar.definition(OrionLifecycleTasks.TRANSPORTS_STOP);
        assertEquals(ApplicationState.STOPPING, stop.phase());
        assertEquals("TransportLifecycleBarrier", stop.serviceName());
        assertEquals(OrionLifecycleTasks.TRANSPORT_LIFECYCLE_STOP, stop.after().getFirst());
        assertSame(OrionStageCallResult.EMPTY, stop.call().call());
    }

    @Test
    void sourceDocumentsTransitionalPhaseBridgeRule() throws Exception {
        String source = Files.readString(Path.of(
                "net/transport/src/main/java/pro/deta/orion/transport/TransportLifecycleBarrier.java"));

        assertTrue(source.contains("@AiRule Keep this class as a no-op phase bridge"));
        assertTrue(source.contains("does not start or stop endpoints"));
        assertTrue(source.contains("TransportLifecycleStateMachine"));
    }

    private static final class RecordingRegistrar implements ApplicationStateListenerRegistrar {
        private final Map<LifecycleTaskId, LifecycleTaskRegistration> registrations = new LinkedHashMap<>();

        @Override
        public LifecycleTaskRegistration register(LifecycleTaskRegistration registration) {
            registrations.put(registration.definition().id(), registration);
            return registration;
        }

        private LifecycleTaskDefinition definition(LifecycleTaskId id) {
            return registrations.get(id).definition();
        }
    }
}
```

**Step 2: Run test to verify it fails**

Run:

```bash
mvn test -Pdev -q -pl net/transport -am -Dtest=TransportLifecycleBarrierTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: FAIL in `sourceDocumentsTransitionalPhaseBridgeRule` because `TransportLifecycleBarrier` does not yet have the `@AiRule` class-level bridge contract.

**Step 3: Commit**

Do not commit yet. This task is intentionally test-only and will be committed with Task 2 after the class-level contract comment is added.

### Task 2: Add The Class-Level Transitional Bridge Rule

**Files:**
- Modify: `net/transport/src/main/java/pro/deta/orion/transport/TransportLifecycleBarrier.java`
- Test: `net/transport/src/test/java/pro/deta/orion/transport/TransportLifecycleBarrierTest.java`

**Step 1: Add class-level comment only**

Add a class-level comment above `TransportLifecycleBarrier`:

```java
/**
 * Legacy application lifecycle bridge for the transport phase boundaries.
 *
 * <p>{@code TRANSPORTS_START} and {@code TRANSPORTS_STOP} are ordering anchors for services that must start before
 * or stop after all transport endpoints. This class does not start or stop endpoints; the concrete transport aggregate
 * is owned by {@link TransportLifecycleStateMachine} through {@code TRANSPORT_LIFECYCLE_START} and
 * {@code TRANSPORT_LIFECYCLE_STOP}.</p>
 *
 * <p>@AiRule Keep this class as a no-op phase bridge until the application lifecycle task graph is replaced by a root
 * lifecycle state machine.</p>
 */
```

Do not add method or constructor comments.

**Step 2: Run the targeted test**

Run:

```bash
mvn test -Pdev -q -pl net/transport -am -Dtest=TransportLifecycleBarrierTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 3: Commit**

Stage only the barrier test and barrier class:

```bash
git add net/transport/src/main/java/pro/deta/orion/transport/TransportLifecycleBarrier.java net/transport/src/test/java/pro/deta/orion/transport/TransportLifecycleBarrierTest.java
git commit -m "test: document transport lifecycle phase bridge"
```

### Task 3: Keep The Existing Runtime Task Graph Contract Explicit

**Files:**
- Modify: `core/bootstrap/src/test/java/pro/deta/orion/component/OrionRuntimeModuleTest.java`

**Step 1: Strengthen existing assertions**

Update the two service-map tests to keep both layers visible and ordered:

```java
assertTrue(serviceMap.contains("TransportLifecycleBarrier: TRANSPORTS_START after ACL_LOAD"));
assertTrue(serviceMap.contains("TransportLifecycleStateMachine: TRANSPORT_LIFECYCLE_START after TRANSPORTS_START"));
assertTrue(serviceMap.contains("TransportLifecycleStateMachine: TRANSPORT_LIFECYCLE_STOP"));
assertTrue(serviceMap.contains("TransportLifecycleBarrier: TRANSPORTS_STOP"));
```

For enabled transports, keep the existing stricter assertion:

```java
assertTrue(serviceMap.contains("TransportLifecycleBarrier: TRANSPORTS_STOP after TRANSPORT_LIFECYCLE_STOP"));
```

Do not assert that `TRANSPORTS_*` starts or stops endpoints.

**Step 2: Run the targeted runtime module test**

Run:

```bash
mvn test -Pdev -q -pl core/bootstrap -am -Dtest=OrionRuntimeModuleTest -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 3: Commit**

```bash
git add core/bootstrap/src/test/java/pro/deta/orion/component/OrionRuntimeModuleTest.java
git commit -m "test: keep transport lifecycle phase boundaries visible"
```

### Task 4: Document The Migration Direction And Verify

**Files:**
- Modify: `core/lifecycle-state-machine/README.md`
- Modify: `net/transport/src/main/java/pro/deta/orion/transport/TransportLifecycleBarrier.java`
- Test: `net/transport/src/test/java/pro/deta/orion/transport/TransportLifecycleBarrierTest.java`
- Test: `core/bootstrap/src/test/java/pro/deta/orion/component/OrionRuntimeModuleTest.java`

**Step 1: Add a short README note**

In the lifecycle state-machine README, near the aggregate lifecycle section, add:

```markdown
Application lifecycle migration should preserve phase boundaries as explicit aggregate nodes. For transports,
`TRANSPORTS_START` and `TRANSPORTS_STOP` are legacy task-graph phase anchors today; when the application lifecycle moves
to a root state machine, model those anchors as parent/phase nodes and keep concrete endpoint startup in the transport
aggregate.
```

**Step 2: Run module checks**

Run:

```bash
mvn test -Pdev -q -pl net/transport,core/bootstrap,core/lifecycle-state-machine -am -Dsurefire.failIfNoSpecifiedTests=false
```

Expected: PASS.

**Step 3: Commit**

```bash
git add core/lifecycle-state-machine/README.md
git commit -m "docs: describe transport lifecycle state machine migration"
```

**Step 4: Final verification**

Run the routine development verification:

```bash
mvn verify -Pdev
```

Expected: PASS. If this is being done as part of a final commit workflow, run `mvn test -Pdev` after the last commit as required by repository instructions.
