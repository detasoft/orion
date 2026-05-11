package pro.deta.orion;

import org.junit.jupiter.api.Test;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.OrionThreadFactory;
import pro.deta.orion.lifecycle.ApplicationStateHolder;
import pro.deta.orion.lifecycle.OrionApplicationLifecycle;
import pro.deta.orion.lifecycle.OrionApplicationStageEventListener;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.lifecycle.task.LifecycleTaskId;
import pro.deta.orion.util.OrionProvider;

import java.util.Set;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AppTest {
    @Test
    void runWaitsUntilLifecycleShutdown() throws Exception {
        try (TestLifecycleContext context = new TestLifecycleContext(Set.of())) {
            FutureTask<Integer> run = new FutureTask<>(() -> App.run(context.lifecycle(), false));
            Thread appThread = new Thread(run, "app-run-test");
            appThread.start();

            context.lifecycle().waitForStarting();
            assertFalse(run.isDone());

            context.lifecycle().beginShutdown();

            assertEquals(0, run.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void runReturnsErrorWhenStartupFails() {
        OrionApplicationStageEventListener failingInit = registrar ->
                registrar.task(ApplicationState.INIT, new LifecycleTaskId("FAILING_INIT"), () -> {
                    throw new IllegalStateException("boom");
                });

        try (TestLifecycleContext context = new TestLifecycleContext(Set.of(failingInit))) {
            assertEquals(1, App.run(context.lifecycle(), false));
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
