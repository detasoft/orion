package pro.deta.orion.lifecycle;

import org.junit.jupiter.api.Test;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.internal.OrionThreadFactory;
import pro.deta.orion.lifecycle.state.AggregateLifecycleStateMachineAdapter;
import pro.deta.orion.lifecycle.state.AggregateStateMachine;
import pro.deta.orion.lifecycle.state.InvalidStateTransitionException;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter;
import pro.deta.orion.lifecycle.state.StateMachineDefinition;
import pro.deta.orion.util.OrionProvider;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.ERR;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.FIN;
import static pro.deta.orion.lifecycle.state.StandardStateDefinition.RUNNING;

class OrionApplicationLifecycleTest {
    @Test
    void startupStartsRuntimeAndMovesMachineToRunning() {
        RecordingServiceLifecycle service = new RecordingServiceLifecycle();
        try (TestLifecycleContext context = new TestLifecycleContext(service)) {
            StateMachineDefinition.State result = context.lifecycle().runApplication();

            assertThat(result).isEqualTo(RUNNING);
            assertThat(context.runtime().currentState()).isEqualTo(RUNNING);
            assertThat(service.startCalls()).isEqualTo(1);
        }
    }

    @Test
    void failedRuntimeStartMovesMachineToErr() {
        RecordingServiceLifecycle service = new RecordingServiceLifecycle();
        service.failStartWith(new IllegalStateException("boom"));
        try (TestLifecycleContext context = new TestLifecycleContext(service)) {
            StateMachineDefinition.State result = context.lifecycle().runApplication();

            assertThat(result).isEqualTo(ERR);
            assertThat(context.runtime().currentState()).isEqualTo(ERR);
            assertThat(service.startCalls()).isEqualTo(1);
            assertThat(service.stopCalls()).isZero();
        }
    }

    @Test
    void shutdownStopsRuntimeAndMovesMachineToFin() {
        RecordingServiceLifecycle service = new RecordingServiceLifecycle();
        try (TestLifecycleContext context = new TestLifecycleContext(service)) {
            assertThat(context.lifecycle().runApplication()).isEqualTo(RUNNING);

            StateMachineDefinition.State result = context.lifecycle().shutdownApplication();

            assertThat(result).isEqualTo(FIN);
            assertThat(context.runtime().currentState()).isEqualTo(FIN);
            assertThat(service.stopCalls()).isEqualTo(1);
        }
    }

    @Test
    void waitForStartingReturnsAfterStartupMovesMachineToRunning() throws Exception {
        RecordingServiceLifecycle service = new RecordingServiceLifecycle();
        try (TestLifecycleContext context = new TestLifecycleContext(service)) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> waiter = executor.submit(() -> context.lifecycle().waitForStarting());

                context.lifecycle().runApplication();

                waiter.get(1, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void waitForShutdownReturnsAfterShutdownMovesMachineToFin() throws Exception {
        RecordingServiceLifecycle service = new RecordingServiceLifecycle();
        try (TestLifecycleContext context = new TestLifecycleContext(service)) {
            assertThat(context.lifecycle().runApplication()).isEqualTo(RUNNING);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> waiter = executor.submit(() -> context.lifecycle().waitForShutdown());

                Thread.sleep(50);
                assertThat(waiter).isNotDone();
                context.lifecycle().shutdownApplication();

                waiter.get(1, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void concurrentStartupRunsRuntimeOnlyOnce() throws Exception {
        RecordingServiceLifecycle service = new RecordingServiceLifecycle();
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        service.blockStartWith(startEntered, releaseStart);
        try (TestLifecycleContext context = new TestLifecycleContext(service)) {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<StateMachineDefinition.State> first = executor.submit(() -> context.lifecycle().runApplication());
                assertThat(startEntered.await(1, TimeUnit.SECONDS)).isTrue();

                Future<StateMachineDefinition.State> second = executor.submit(() -> context.lifecycle().runApplication());
                Thread.sleep(50);

                assertThat(service.startCalls()).isEqualTo(1);
                releaseStart.countDown();
                assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo(RUNNING);
                assertInvalidStateTransition(second);
                assertThat(service.startCalls()).isEqualTo(1);
            } finally {
                releaseStart.countDown();
                executor.shutdownNow();
            }
        }
    }

    @Test
    void describeLifecycleShowsRuntimeStatus() {
        RecordingServiceLifecycle service = new RecordingServiceLifecycle();
        try (TestLifecycleContext context = new TestLifecycleContext(service)) {
            assertThat(context.lifecycle().describeLifecycle()).contains(
                    "runtime: NEW",
                    "service: NEW");
        }
    }

    private static void assertInvalidStateTransition(Future<StateMachineDefinition.State> future) throws Exception {
        try {
            future.get(1, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            assertThat(e).hasCauseInstanceOf(InvalidStateTransitionException.class);
            return;
        }
        throw new AssertionError("Expected invalid state transition");
    }

    private static final class TestLifecycleContext implements AutoCloseable {
        private final OrionExecutor executor = new OrionExecutor(4, new OrionThreadFactory());
        private final OrionEventManager eventManager = new OrionEventManager();
        private final AggregateStateMachine runtime;
        private final OrionApplicationLifecycle lifecycle;

        private TestLifecycleContext(RecordingServiceLifecycle service) {
            ServiceLifecycleStateMachineAdapter serviceMachine =
                    new ServiceLifecycleStateMachineAdapter("service", service);
            runtime = AggregateLifecycleStateMachineAdapter.define("runtime")
                    .child("service", serviceMachine.stateMachine())
                    .buildAggregateStateMachine();
            AtomicReference<OrionApplicationLifecycle> lifecycleRef = new AtomicReference<>();
            OrionProvider provider = new OrionProvider(
                    lifecycleRef::get,
                    () -> eventManager,
                    () -> executor);
            lifecycle = new OrionApplicationLifecycle(
                    runtime,
                    provider);
            lifecycleRef.set(lifecycle);
        }

        private AggregateStateMachine runtime() {
            return runtime;
        }

        private OrionApplicationLifecycle lifecycle() {
            return lifecycle;
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    private static final class RecordingServiceLifecycle implements ServiceLifecycleStateMachineAdapter.ServiceLifecycle {
        private RuntimeException startFailure;
        private CountDownLatch startEntered;
        private CountDownLatch releaseStart;
        private boolean running;
        private int startCalls;
        private int stopCalls;

        @Override
        public void onStart() {
            startCalls++;
            if (startEntered != null) {
                startEntered.countDown();
                await(releaseStart);
            }
            if (startFailure != null) {
                throw startFailure;
            }
            running = true;
        }

        @Override
        public void onStop() {
            stopCalls++;
            running = false;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        private void failStartWith(RuntimeException failure) {
            startFailure = failure;
        }

        private void blockStartWith(CountDownLatch startEntered, CountDownLatch releaseStart) {
            this.startEntered = startEntered;
            this.releaseStart = releaseStart;
        }

        private int startCalls() {
            return startCalls;
        }

        private int stopCalls() {
            return stopCalls;
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for test latch");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for test latch", e);
        }
    }
}
