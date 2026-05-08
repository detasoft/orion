package pro.deta.orion.lifecycle;

import org.junit.jupiter.api.Test;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.OrionThreadFactory;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.util.OrionProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class OrionApplicationLifecycleTest {
    @Test
    void startupRunsInitThenStartingAndEndsUp() {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        OrionApplicationStageEventListener init = registrar ->
                registrar.register(ApplicationState.INIT, () -> {
                    events.add("init");
                    return OrionStageCallResult.EMPTY;
                }).priority(1).waitForCompletion();
        OrionApplicationStageEventListener starting = registrar ->
                registrar.register(ApplicationState.STARTING, () -> {
                    events.add("starting");
                    return OrionStageCallResult.EMPTY;
                }).priority(1).waitForCompletion();

        ApplicationState result = runLifecycle(Set.of(init, starting));

        assertThat(result).isEqualTo(ApplicationState.UP);
        assertThat(events).containsExactly("init", "starting");
    }

    @Test
    void failingInitMovesApplicationToFailedAndDoesNotRunStarting() {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        OrionApplicationStageEventListener init = registrar ->
                registrar.register(ApplicationState.INIT, () -> {
                    events.add("init");
                    throw new IllegalStateException("boom");
                }).priority(1).waitForCompletion();
        OrionApplicationStageEventListener starting = registrar ->
                registrar.register(ApplicationState.STARTING, () -> {
                    events.add("starting");
                    return OrionStageCallResult.EMPTY;
                }).priority(1).waitForCompletion();

        ApplicationState result = runLifecycle(Set.of(init, starting));

        assertThat(result).isEqualTo(ApplicationState.FAILED);
        assertThat(events).containsExactly("init");
    }

    @Test
    void shutdownRunsStoppingAndEndsOff() {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        OrionApplicationStageEventListener stopping = registrar ->
                registrar.register(ApplicationState.STOPPING, () -> {
                    events.add("stopping");
                    return OrionStageCallResult.EMPTY;
                }).priority(1).waitForCompletion();

        try (TestLifecycleContext context = new TestLifecycleContext(Set.of(stopping))) {
            context.lifecycle().runApplication();
            context.lifecycle().beginShutdown();
            context.lifecycle().waitForShutdown();

            assertThat(context.stateHolder().getState()).isEqualTo(ApplicationState.OFF);
            assertThat(events).containsExactly("stopping");
        }
    }

    @Test
    void waitsForBlockingInitListenerBeforeStartingNextInitListener() {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        List<Consumer<String>> initSubscribers = new CopyOnWriteArrayList<>();

        OrionApplicationStageEventListener aclService = registrar ->
                registrar.register(ApplicationState.INIT, () -> {
                    delayLongEnoughToExposeMissingWait();
                    initSubscribers.add(value -> events.add("acl-handler-received"));
                    events.add("acl-handler-registered");
                    return OrionStageCallResult.EMPTY;
                }).priority(1).waitForCompletion();

        OrionApplicationStageEventListener gitStorage = registrar ->
                registrar.register(ApplicationState.INIT, () -> {
                    events.add("git-storage-event-published");
                    for (Consumer<String> subscriber : initSubscribers) {
                        subscriber.accept("acl-ready");
                    }
                    return OrionStageCallResult.EMPTY;
                }).priority(2);

        try (TestLifecycleContext context = new TestLifecycleContext(Set.of(aclService, gitStorage))) {
            assertThat(context.lifecycle().runApplication()).isEqualTo(ApplicationState.UP);
        }

        assertThat(events).containsExactly(
                "acl-handler-registered",
                "git-storage-event-published",
                "acl-handler-received");
    }

    private static void delayLongEnoughToExposeMissingWait() throws InterruptedException {
        Thread.sleep(100);
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

        private ApplicationStateHolder stateHolder() {
            return stateHolder;
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
