package pro.deta.orion.lifecycle.flow;

import org.junit.jupiter.api.Test;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.lifecycle.ApplicationStateHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LifecycleFlowRunnerTest {
    @Test
    void startupFlowRunsStagesAndEndsUp() {
        ApplicationStateHolder stateHolder = new ApplicationStateHolder();
        List<ApplicationState> stages = new ArrayList<>();
        LifecycleFlowRunner runner = new LifecycleFlowRunner(
                stateHolder,
                stage -> {
                    stages.add(stage);
                    return true;
                });

        ApplicationState result = runner.runStartup();

        assertThat(result).isEqualTo(ApplicationState.UP);
        assertThat(stages).containsExactly(ApplicationState.INIT, ApplicationState.STARTING);
        assertThat(stateHolder.getState()).isEqualTo(ApplicationState.UP);
    }

    @Test
    void startupFlowDoesNotRunStagesAgainAfterApplicationIsUp() {
        ApplicationStateHolder stateHolder = new ApplicationStateHolder();
        List<ApplicationState> stages = new ArrayList<>();
        LifecycleFlowRunner runner = new LifecycleFlowRunner(
                stateHolder,
                stage -> {
                    stages.add(stage);
                    return true;
                });

        assertThat(runner.runStartup()).isEqualTo(ApplicationState.UP);
        assertThat(runner.runStartup()).isEqualTo(ApplicationState.UP);

        assertThat(stages).containsExactly(ApplicationState.INIT, ApplicationState.STARTING);
    }

    @Test
    void transitionOnlyStepMovesStateWithoutRunningStage() {
        ApplicationStateHolder stateHolder = startedApplication();
        List<ApplicationState> stages = new ArrayList<>();
        LifecycleFlowRunner runner = new LifecycleFlowRunner(
                stateHolder,
                stage -> {
                    stages.add(stage);
                    return true;
                });

        ApplicationState result = runner.runShutdown();

        assertThat(result).isEqualTo(ApplicationState.OFF);
        assertThat(stages).containsExactly(ApplicationState.STOPPING);
    }

    @Test
    void failedStageMovesToFailureAndStopsFlow() {
        ApplicationStateHolder stateHolder = new ApplicationStateHolder();
        List<ApplicationState> stages = new ArrayList<>();
        LifecycleFlowRunner runner = new LifecycleFlowRunner(
                stateHolder,
                stage -> {
                    stages.add(stage);
                    return false;
                });

        ApplicationState result = runner.run(LifecycleFlow.STARTUP);

        assertThat(result).isEqualTo(ApplicationState.FAILED);
        assertThat(stages).containsExactly(ApplicationState.INIT);
    }

    @Test
    void describesKnownFlows() {
        LifecycleFlowRunner runner = new LifecycleFlowRunner(
                new ApplicationStateHolder(),
                stage -> true);

        assertThat(runner.describeFlows()).contains(
                "STARTUP:",
                "INIT -> STARTING",
                "STARTING -> UP",
                "SHUTDOWN:",
                "UP -> STOPPING",
                "STOPPING -> OFF");
    }

    @Test
    void waitForStateReturnsAfterFlowMovesToRequestedState() throws Exception {
        ApplicationStateHolder stateHolder = new ApplicationStateHolder();
        LifecycleFlowRunner runner = new LifecycleFlowRunner(
                stateHolder,
                stage -> true);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> waiter = executor.submit(() -> runner.waitForState(ApplicationState.UP));

            runner.runStartup();

            waiter.get(1, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void doesNotRunTwoFlowsAtTheSameTime() throws Exception {
        LifecycleFlow oneStepFlow = new LifecycleFlow("ONE_STEP", List.of(
                LifecycleStep.from(ApplicationState.INIT)
                        .to(ApplicationState.STARTING)
                        .build()));
        ApplicationStateHolder stateHolder = new ApplicationStateHolder();
        CountDownLatch firstStageStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstStage = new CountDownLatch(1);
        AtomicInteger activeStages = new AtomicInteger();
        AtomicInteger maxActiveStages = new AtomicInteger();
        LifecycleFlowRunner runner = new LifecycleFlowRunner(
                stateHolder,
                stage -> {
                    int active = activeStages.incrementAndGet();
                    maxActiveStages.updateAndGet(current -> Math.max(current, active));
                    firstStageStarted.countDown();
                    await(releaseFirstStage);
                    activeStages.decrementAndGet();
                    return true;
                });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ApplicationState> first = executor.submit(() -> runner.run(oneStepFlow));
            assertThat(firstStageStarted.await(1, TimeUnit.SECONDS)).isTrue();

            Future<ApplicationState> second = executor.submit(() -> runner.run(oneStepFlow));
            Thread.sleep(50);

            assertThat(maxActiveStages.get()).isEqualTo(1);
            releaseFirstStage.countDown();
            assertThat(first.get(1, TimeUnit.SECONDS)).isEqualTo(ApplicationState.STARTING);
            assertThat(second.get(1, TimeUnit.SECONDS)).isEqualTo(ApplicationState.STARTING);
            assertThat(maxActiveStages.get()).isEqualTo(1);
        } finally {
            releaseFirstStage.countDown();
            executor.shutdownNow();
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

    private static ApplicationStateHolder startedApplication() {
        ApplicationStateHolder stateHolder = new ApplicationStateHolder();
        stateHolder.moveStateFrom(ApplicationState.INIT, ApplicationState.STARTING);
        stateHolder.moveStateFrom(ApplicationState.STARTING, ApplicationState.UP);
        return stateHolder;
    }
}
