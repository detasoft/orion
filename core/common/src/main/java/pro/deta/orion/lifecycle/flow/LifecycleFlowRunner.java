package pro.deta.orion.lifecycle.flow;

import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.lifecycle.ApplicationStateHolder;

import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Runs lifecycle flows one at a time. The flow lock prevents concurrent startup/shutdown execution,
 * while the state lock is kept short and only protects state changes plus wait/signal coordination.
 */
@Slf4j
public final class LifecycleFlowRunner {
    private final ReentrantLock flowLock = new ReentrantLock();
    private final ReentrantLock stateLock = new ReentrantLock();
    private final Condition stateChanged = stateLock.newCondition();
    private final ApplicationStateHolder applicationStateHolder;
    private final Function<ApplicationState, Boolean> runtimeTransitionRunner;

    public LifecycleFlowRunner(
            ApplicationStateHolder applicationStateHolder,
            Function<ApplicationState, Boolean> runtimeTransitionRunner) {
        this.applicationStateHolder = Objects.requireNonNull(applicationStateHolder, "applicationStateHolder");
        this.runtimeTransitionRunner = Objects.requireNonNull(runtimeTransitionRunner, "runtimeTransitionRunner");
    }

    public ApplicationState runStartup() {
        return run(LifecycleFlow.STARTUP);
    }

    public ApplicationState runShutdown() {
        return run(LifecycleFlow.SHUTDOWN);
    }

    public ApplicationState run(LifecycleFlow flow) {
        Objects.requireNonNull(flow, "flow");
        flowLock.lock();
        try {
            for (LifecycleStep step : flow.steps()) {
                ApplicationState currentState = applicationStateHolder.getState();
                if (currentState != step.from()) {
                    log.debug("Lifecycle flow '{}' stopped before {} because current state is {}",
                            flow.name(), step, currentState);
                    return currentState;
                }
                followStep(flow, step);
            }
            return applicationStateHolder.getState();
        } finally {
            flowLock.unlock();
        }
    }

    public String describeFlows() {
        return describe(LifecycleFlow.STARTUP) + "\n" + describe(LifecycleFlow.SHUTDOWN);
    }

    public String describe(LifecycleFlow flow) {
        return Objects.requireNonNull(flow, "flow").describe();
    }

    public void waitForState(ApplicationState state) {
        Objects.requireNonNull(state, "state");
        stateLock.lock();
        try {
            while (applicationStateHolder.getState() != state) {
                log.debug("Waiting for desired state {} but current one is {}", state, applicationStateHolder.getState());
                try {
                    stateChanged.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for application state " + state, e);
                }
            }
        } finally {
            stateLock.unlock();
        }
    }

    private void followStep(LifecycleFlow flow, LifecycleStep step) {
        boolean completed = !step.runRuntime() || runtimeTransitionRunner.apply(step.from());
        ApplicationState targetState = completed ? step.success() : step.failure();
        log.debug("Lifecycle flow '{}' moves {} -> {}", flow.name(), step.from(), targetState);
        moveState(step.from(), targetState);
    }

    private void moveState(ApplicationState from, ApplicationState to) {
        stateLock.lock();
        try {
            applicationStateHolder.moveStateFrom(from, to);
            stateChanged.signalAll();
        } finally {
            stateLock.unlock();
        }
    }
}
