package pro.deta.orion.lifecycle;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.OrionThreadFactory;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.lifecycle.task.LifecycleTaskId;
import pro.deta.orion.lifecycle.task.OrionLifecycleTasks;
import pro.deta.orion.util.OrionProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
class OrionApplicationLifecycleTest {
    @Test
    void startupRunsInitThenStartingAndEndsUp() {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        OrionApplicationStageEventListener init = registrar ->
                registrar.task(ApplicationState.INIT, taskId("TEST_INIT"), () -> {
                    events.add("init");
                    return OrionStageCallResult.EMPTY;
                });
        OrionApplicationStageEventListener starting = registrar ->
                registrar.task(ApplicationState.STARTING, taskId("TEST_STARTING"), () -> {
                    events.add("starting");
                    return OrionStageCallResult.EMPTY;
                });

        ApplicationState result = runLifecycle(Set.of(init, starting));

        assertThat(result).isEqualTo(ApplicationState.UP);
        assertThat(events).containsExactly("init", "starting");
    }

    @Test
    void failingInitMovesApplicationToFailedAndDoesNotRunStarting() {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        OrionApplicationStageEventListener init = registrar ->
                registrar.task(ApplicationState.INIT, taskId("TEST_FAILING_INIT"), () -> {
                    events.add("init");
                    throw new IllegalStateException("boom");
                });
        OrionApplicationStageEventListener starting = registrar ->
                registrar.task(ApplicationState.STARTING, taskId("TEST_STARTING"), () -> {
                    events.add("starting");
                    return OrionStageCallResult.EMPTY;
                });

        ApplicationState result = runLifecycle(Set.of(init, starting));

        assertThat(result).isEqualTo(ApplicationState.FAILED);
        assertThat(events).containsExactly("init");
    }

    @Test
    void shutdownRunsStoppingAndEndsOff() {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        OrionApplicationStageEventListener stopping = registrar ->
                registrar.task(ApplicationState.STOPPING, taskId("TEST_STOPPING"), () -> {
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

    @Test
    void describeLifecycleShowsFlowsAndTaskPlans() {
        OrionApplicationStageEventListener startingTasks = registrar -> {
            registrar.task(ApplicationState.STARTING, OrionLifecycleTasks.REPOSITORY_STORAGE, () -> OrionStageCallResult.EMPTY);
            registrar.task(ApplicationState.STARTING, OrionLifecycleTasks.ACL_LOAD, () -> OrionStageCallResult.EMPTY)
                    .after(OrionLifecycleTasks.REPOSITORY_STORAGE);
        };

        try (TestLifecycleContext context = new TestLifecycleContext(Set.of(startingTasks))) {
            assertThat(context.lifecycle().describeLifecycle()).contains(
                    "STARTUP:",
                    "INIT -> STARTING",
                    "STARTING:",
                    "REPOSITORY_STORAGE",
                    "ACL_LOAD after REPOSITORY_STORAGE");
        }
    }

    @Test
    void describeServiceMapShowsRegisteredTasksBeforePlanning() {
        OrionApplicationStageEventListener tasks = new ServiceMapLifecycleService();
        try (TestLifecycleContext context = new TestLifecycleContext(Set.of(tasks))) {
            String serviceMap = context.lifecycle().describeServiceMap();
            log.warn("Lifecycle service map test output:\n{}", serviceMap);

            assertThat(serviceMap).contains(
                    "Lifecycle service map:",
                    "INIT:",
                    "  - ServiceMapLifecycleService: EVENT_MANAGER",
                    "STARTING:",
                    "  - ServiceMapLifecycleService: ACL_LOAD after REPOSITORY_STORAGE wait 3s");
        }
    }

    @Test
    void beginShutdownUsesShutdownFlow() {
        try (TestLifecycleContext context = new TestLifecycleContext(Set.of())) {
            context.lifecycle().runApplication();
            context.lifecycle().beginShutdown();
            context.lifecycle().waitForShutdown();

            assertThat(context.stateHolder().getState()).isEqualTo(ApplicationState.OFF);
        }
    }

    @Test
    void waitsForDependentInitTaskBeforeStartingNextInitTask() {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        List<Consumer<String>> initSubscribers = new CopyOnWriteArrayList<>();
        LifecycleTaskId aclHandler = taskId("TEST_ACL_HANDLER");
        LifecycleTaskId gitStorage = taskId("TEST_GIT_STORAGE");

        OrionApplicationStageEventListener aclService = registrar ->
                registrar.task(ApplicationState.INIT, aclHandler, () -> {
                    delayLongEnoughToExposeMissingWait();
                    initSubscribers.add(value -> events.add("acl-handler-received"));
                    events.add("acl-handler-registered");
                    return OrionStageCallResult.EMPTY;
                });

        OrionApplicationStageEventListener gitStorageEvent = registrar ->
                registrar.task(ApplicationState.INIT, gitStorage, () -> {
                    events.add("git-storage-event-published");
                    for (Consumer<String> subscriber : initSubscribers) {
                        subscriber.accept("acl-ready");
                    }
                    return OrionStageCallResult.EMPTY;
                }).after(aclHandler);

        try (TestLifecycleContext context = new TestLifecycleContext(Set.of(aclService, gitStorageEvent))) {
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

    private static LifecycleTaskId taskId(String value) {
        return new LifecycleTaskId(value);
    }

    private static final class ServiceMapLifecycleService implements OrionApplicationStageEventListener {
        @Override
        public void registerToStage(ApplicationStateListenerRegistrar registrar) {
            task(registrar, ApplicationState.INIT, OrionLifecycleTasks.EVENT_MANAGER, () -> OrionStageCallResult.EMPTY);
            task(registrar, ApplicationState.STARTING, OrionLifecycleTasks.ACL_LOAD, () -> OrionStageCallResult.EMPTY)
                    .after(OrionLifecycleTasks.REPOSITORY_STORAGE)
                    .waitForCompletionSecs(3);
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
