package pro.deta.orion.lifecycle.task;

import org.junit.jupiter.api.Test;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.OrionThreadFactory;
import pro.deta.orion.lifecycle.ApplicationStateHolder;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.util.OrionProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.lifecycle.task.OrionLifecycleTasks.ACL_LOAD;
import static pro.deta.orion.lifecycle.task.OrionLifecycleTasks.REPOSITORY_STORAGE;
import static pro.deta.orion.lifecycle.task.OrionLifecycleTasks.TRANSPORTS_START;

class LifecycleTaskExecutionTest {
    @Test
    void explicitTasksRunInDependencyOrder() {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        OrionApplicationStageEventListener listener = registrar -> {
            registrar.task(ApplicationState.STARTING, TRANSPORTS_START, () -> {
                events.add("transports");
                return OrionStageCallResult.EMPTY;
            }).after(ACL_LOAD);
            registrar.task(ApplicationState.STARTING, ACL_LOAD, () -> {
                events.add("acl");
                return OrionStageCallResult.EMPTY;
            }).after(REPOSITORY_STORAGE);
            registrar.task(ApplicationState.STARTING, REPOSITORY_STORAGE, () -> {
                events.add("storage");
                return OrionStageCallResult.EMPTY;
            });
        };

        ApplicationState result = runLifecycle(Set.of(listener));

        assertThat(result).isEqualTo(ApplicationState.UP);
        assertThat(events).containsExactly("storage", "acl", "transports");
    }

    @Test
    void failingExplicitTaskMovesApplicationToFailed() {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        OrionApplicationStageEventListener listener = registrar -> {
            registrar.task(ApplicationState.INIT, REPOSITORY_STORAGE, () -> {
                events.add("explicit");
                throw new IllegalStateException("boom");
            });
            registrar.task(ApplicationState.STARTING, TRANSPORTS_START, () -> {
                events.add("starting");
                return OrionStageCallResult.EMPTY;
            });
        };

        ApplicationState result = runLifecycle(Set.of(listener));

        assertThat(result).isEqualTo(ApplicationState.FAILED);
        assertThat(events).containsExactly("explicit");
    }

    @Test
    void exposesExplicitTaskPlanDescription() {
        OrionApplicationStageEventListener listener = registrar -> {
            registrar.task(ApplicationState.STARTING, REPOSITORY_STORAGE, () -> OrionStageCallResult.EMPTY);
            registrar.task(ApplicationState.STARTING, ACL_LOAD, () -> OrionStageCallResult.EMPTY)
                    .after(REPOSITORY_STORAGE);
        };

        try (TestLifecycleContext context = new TestLifecycleContext(Set.of(listener))) {
            assertThat(context.lifecycle().describeTaskPlan(ApplicationState.STARTING)).contains(
                    "STARTING:",
                    "REPOSITORY_STORAGE",
                    "ACL_LOAD after REPOSITORY_STORAGE");
        }
    }

    private ApplicationState runLifecycle(Set<OrionApplicationStageEventListener> listeners) {
        try (TestLifecycleContext context = new TestLifecycleContext(listeners)) {
            return context.lifecycle().runApplication();
        }
    }

    private static final class TestLifecycleContext implements AutoCloseable {
        private final OrionExecutor executor = new OrionExecutor(4, new OrionThreadFactory());
        private final ApplicationStateHolder stateHolder = new ApplicationStateHolder();
        private final OrionApplicationLifecycle lifecycle;

        private TestLifecycleContext(Set<OrionApplicationStageEventListener> listeners) {
            AtomicReference<OrionApplicationLifecycle> lifecycleRef = new AtomicReference<>();
            OrionProvider provider = new OrionProvider(
                    stateHolder,
                    lifecycleRef::get,
                    OrionEventManager::new,
                    () -> executor);
            lifecycle = new OrionApplicationLifecycle(
                    stateHolder,
                    executor,
                    listeners,
                    provider);
            lifecycleRef.set(lifecycle);
        }

        private OrionApplicationLifecycle lifecycle() {
            return lifecycle;
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }
}
