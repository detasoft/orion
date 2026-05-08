package pro.deta.orion.lifecycle;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.lifecycle.flow.LifecycleFlow;
import pro.deta.orion.lifecycle.flow.LifecycleStep;
import pro.deta.orion.lifecycle.task.LifecycleTaskDefinition;
import pro.deta.orion.lifecycle.task.LifecycleTaskPlan;
import pro.deta.orion.lifecycle.task.LifecycleTaskPlanner;
import pro.deta.orion.lifecycle.task.LifecycleTaskRegistration;
import pro.deta.orion.util.Result;
import pro.deta.orion.util.OrionProvider;
import pro.deta.orion.util.OrionUtils;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static pro.deta.orion.ApplicationState.*;

@Slf4j
@Singleton
public class OrionApplicationLifecycle  implements ApplicationStateListenerRegistrar {
    public final static ApplicationBootstrap BOOTSTRAP = new ApplicationBootstrap();
    private static final int DEFAULT_TASK_WAIT_FOR_COMPLETION_TIMEOUT_IN_SEC =
            OrionStageCallResult.DEFAULT_WAIT_FOR_COMPLETION_TIMEOUT_IN_SEC;

    private final ApplicationStateHolder applicationStateHolder;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition lockCondition = lock.newCondition();

    private final List<LifecycleTaskRegistration> lifecycleTaskRegistrations = new CopyOnWriteArrayList<>();
    private final OrionProvider orionProvider;

    @Inject
    public OrionApplicationLifecycle(ApplicationStateHolder applicationStateHolder,
                                     OrionExecutor orionExecutor,
                                     Set<OrionApplicationStageEventListener> applicationEventListeners, OrionProvider orionProvider) {
        this.applicationStateHolder = applicationStateHolder;
        this.orionProvider = orionProvider;
        for (OrionApplicationStageEventListener applicationStageEventListener : applicationEventListeners) {
            applicationStageEventListener.registerToStage(this);
        }
    }

    @Override
    public LifecycleTaskRegistration register(LifecycleTaskRegistration registration) {
        lifecycleTaskRegistrations.add(registration);
        return registration;
    }

    private boolean onStage(ApplicationState state) {
        log.warn("[{}] stage initiated...", state);
        boolean isSuccess = runExplicitTasks(state);
        log.warn("[{}] stage completed: {}.", state, isSuccess ? "success" : "failure");
        return isSuccess;
    }

    private boolean runExplicitTasks(ApplicationState state) {
        List<LifecycleTaskDefinition> definitions = lifecycleTaskDefinitions();
        if (definitions.stream().noneMatch(definition -> definition.phase() == state)) {
            return true;
        }

        LifecycleTaskPlan plan;
        try {
            plan = LifecycleTaskPlanner.plan(state, definitions);
        } catch (RuntimeException e) {
            log.error("[{}] lifecycle task plan failed.", state, e);
            return false;
        }

        log.trace("[{}] lifecycle task plan:\n{}", state, plan.describe());
        for (LifecycleTaskPlan.ExecutionGroup group : plan.executionGroups()) {
            List<LifecycleTaskResult> results = executeTaskGroup(group);
            for (LifecycleTaskResult result : results) {
                result.waitTask();
            }
            for (LifecycleTaskResult result : results) {
                result.waitFeaturesIfNeeded();
            }
            if (!taskGroupSucceeded(results)) {
                log.trace("[{}] lifecycle task group failed: {}", state, group);
                return false;
            }
        }
        return true;
    }

    private List<LifecycleTaskResult> executeTaskGroup(LifecycleTaskPlan.ExecutionGroup group) {
        List<LifecycleTaskResult> results = new ArrayList<>();
        for (LifecycleTaskDefinition task : group.tasks()) {
            Future<OrionStageCallResult> future = orionProvider.getOrionExecutor().submit(() -> {
                try {
                    return task.call().call();
                } catch (Exception e) {
                    log.error("Exception while calling lifecycle task {}.", task.id(), e);
                    throw new RuntimeException(e);
                }
            });
            results.add(new LifecycleTaskResult(task, future));
        }
        return results;
    }

    private boolean taskGroupSucceeded(List<LifecycleTaskResult> results) {
        for (LifecycleTaskResult result : results) {
            if (!result.isSuccess()) {
                return false;
            }
        }
        return true;
    }

    private List<LifecycleTaskDefinition> lifecycleTaskDefinitions() {
        List<LifecycleTaskDefinition> definitions = new ArrayList<>();
        for (LifecycleTaskRegistration registration : lifecycleTaskRegistrations) {
            definitions.add(registration.definition());
        }
        return definitions;
    }

    public String describeTaskPlan(ApplicationState state) {
        List<LifecycleTaskDefinition> definitions = lifecycleTaskDefinitions();
        if (definitions.stream().noneMatch(definition -> definition.phase() == state)) {
            return "";
        }
        return LifecycleTaskPlanner.plan(state, definitions).describe();
    }

    public String describeLifecycle() {
        StringBuilder builder = new StringBuilder();
        builder.append(describeFlows()).append('\n');
        for (ApplicationState state : ApplicationState.values()) {
            String taskPlan = describeTaskPlan(state);
            if (!taskPlan.isBlank()) {
                builder.append(taskPlan).append('\n');
            }
        }
        return builder.toString();
    }

    public ApplicationState runApplication() {
        log.debug("Lifecycle plan:\n{}", describeLifecycle());
        return runFlow(LifecycleFlow.STARTUP);
    }

    private ApplicationState runFlow(LifecycleFlow flow) {
        for (LifecycleStep step : flow.steps()) {
            ApplicationState currentState = applicationStateHolder.getState();
            if (currentState != step.from()) {
                log.debug("Lifecycle flow '{}' stopped before {} because current state is {}", flow.name(), step, currentState);
                return currentState;
            }
            followStep(flow, step);
        }
        return applicationStateHolder.getState();
    }

    private void followStep(LifecycleFlow flow, LifecycleStep step) {
        boolean completed = !step.runListeners() || onStage(step.from());
        ApplicationState targetState = completed ? step.success() : step.failure();
        log.debug("Lifecycle flow '{}' moves {} -> {}", flow.name(), step.from(), targetState);
        moveState(step.from(), targetState);
    }

    private void moveState(ApplicationState from, ApplicationState to) {
        doInLock(() -> {
            applicationStateHolder.moveStateFrom(from, to);
            lockCondition.signalAll();
        });
    }

    private void doShutdown() {
        log.info("System shutdown process initiated.");
        runFlow(LifecycleFlow.SHUTDOWN);
    }

    public String describeFlows() {
        return LifecycleFlow.STARTUP.describe() + "\n" + LifecycleFlow.SHUTDOWN.describe();
    }

    public void beginShutdown() {
        doInLock(() -> {
            orionProvider.getOrionExecutor().submit(this::doShutdown);
        });
    }

    private void doInLock(Runnable r) {
        try {
            lock.lock();
            r.run();
        } finally {
            lock.unlock();
        }
    }

    public void waitForStarting() {
        doInLock(() -> {
            waitAppForState(UP);
            OrionUtils.waitForCondition(() -> orionProvider.getEventManager().getUnprocessedLength() == 0);
        });
    }

    private void waitAppForState(ApplicationState state) {
        while (applicationStateHolder.getState() != state) {
            log.debug("Waiting for desired state {} but current one is {}", state, applicationStateHolder.getState());
            try {
                lockCondition.awaitNanos(1000_000);
            } catch (InterruptedException e) {
                log.debug("Interrupted while waiting for begin shutdown", e);
            }
        }
        lockCondition.signalAll();
    }

    public void waitForShutdown() {
        doInLock(() -> {
            waitAppForState(OFF);
        });
    }

    private static final class LifecycleTaskResult {
        private final LifecycleTaskDefinition task;
        private final Future<OrionStageCallResult> future;
        private Result<OrionStageCallResult> result;

        private LifecycleTaskResult(LifecycleTaskDefinition task, Future<OrionStageCallResult> future) {
            this.task = task;
            this.future = future;
        }

        private void waitTask() {
            result = OrionUtils.waitForCompletion(future, waitForCompletionSecs());
            result.valueOrWarning("[{}] running lifecycle task {} block: {}",
                    task.phase(), task.id(), waitForCompletionSecs());
        }

        private void waitFeaturesIfNeeded() {
            result.onSuccess(value -> {
                if (value != null && value.neededToWait()) {
                    value.getFuturesToWait().forEach(waitFuture ->
                            OrionUtils.waitForCompletion(waitFuture.getFuture(), value.getWaitForCompletionSecs()));
                }
            });
        }

        private boolean isSuccess() {
            return result != null && !result.isFailure();
        }

        private int waitForCompletionSecs() {
            if (task.waitForCompletionSecs() > 0) {
                return task.waitForCompletionSecs();
            }
            return DEFAULT_TASK_WAIT_FOR_COMPLETION_TIMEOUT_IN_SEC;
        }
    }

}
