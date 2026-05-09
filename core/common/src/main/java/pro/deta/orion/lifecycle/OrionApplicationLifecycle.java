package pro.deta.orion.lifecycle;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.lifecycle.data.OrionStageCallResult;
import pro.deta.orion.lifecycle.flow.LifecycleFlowRunner;
import pro.deta.orion.lifecycle.task.LifecycleTaskDefinition;
import pro.deta.orion.lifecycle.task.LifecycleTaskPlan;
import pro.deta.orion.lifecycle.task.LifecycleTaskPlanner;
import pro.deta.orion.lifecycle.task.LifecycleTaskRegistration;
import pro.deta.orion.lifecycle.task.LifecycleTaskId;
import pro.deta.orion.util.Result;
import pro.deta.orion.util.OrionProvider;
import pro.deta.orion.util.OrionUtils;

import java.util.*;
import java.util.concurrent.*;

import static pro.deta.orion.ApplicationState.*;

@Slf4j
@Singleton
public class OrionApplicationLifecycle  implements ApplicationStateListenerRegistrar {
    public final static ApplicationBootstrap BOOTSTRAP = new ApplicationBootstrap();
    private static final int DEFAULT_TASK_WAIT_FOR_COMPLETION_TIMEOUT_IN_SEC =
            OrionStageCallResult.DEFAULT_WAIT_FOR_COMPLETION_TIMEOUT_IN_SEC;

    private final List<LifecycleTaskRegistration> lifecycleTaskRegistrations = new CopyOnWriteArrayList<>();
    private final OrionProvider orionProvider;
    private final LifecycleFlowRunner flowRunner;

    @Inject
    public OrionApplicationLifecycle(ApplicationStateHolder applicationStateHolder,
                                     OrionExecutor orionExecutor,
                                     Set<OrionApplicationStageEventListener> applicationEventListeners, OrionProvider orionProvider) {
        this.orionProvider = orionProvider;
        flowRunner = new LifecycleFlowRunner(applicationStateHolder, this::onStage);
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
        builder.append(flowRunner.describeFlows()).append('\n');
        for (ApplicationState state : ApplicationState.values()) {
            String taskPlan = describeTaskPlan(state);
            if (!taskPlan.isBlank()) {
                builder.append(taskPlan).append('\n');
            }
        }
        return builder.toString();
    }

    public String describeServiceMap() {
        StringBuilder builder = new StringBuilder();
        builder.append("Lifecycle service map:\n");
        List<LifecycleTaskDefinition> definitions = lifecycleTaskDefinitions();
        for (ApplicationState state : ApplicationState.values()) {
            boolean headerAdded = false;
            for (LifecycleTaskDefinition definition : definitions) {
                if (definition.phase() != state) {
                    continue;
                }
                if (!headerAdded) {
                    builder.append(state).append(":\n");
                    headerAdded = true;
                }
                builder.append("  - ");
                if (!definition.serviceName().isBlank()) {
                    builder.append(definition.serviceName()).append(": ");
                }
                builder.append(definition.id());
                if (!definition.after().isEmpty()) {
                    builder.append(" after ").append(joinTaskIds(definition.after()));
                }
                if (definition.waitForCompletionSecs() > 0) {
                    builder.append(" wait ").append(definition.waitForCompletionSecs()).append("s");
                }
                builder.append('\n');
            }
        }
        return builder.toString();
    }

    public ApplicationState runApplication() {
        if (log.isDebugEnabled()) {
            log.debug("Lifecycle service map before initialization:\n{}", describeServiceMap());
            log.debug("Lifecycle plan:\n{}", describeLifecycle());
        }
        return flowRunner.runStartup();
    }

    private void doShutdown() {
        log.info("System shutdown process initiated.");
        flowRunner.runShutdown();
    }

    public String describeFlows() {
        return flowRunner.describeFlows();
    }

    private static String joinTaskIds(List<LifecycleTaskId> taskIds) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < taskIds.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(taskIds.get(i));
        }
        return builder.toString();
    }

    public void beginShutdown() {
        orionProvider.getOrionExecutor().submit(this::doShutdown);
    }

    public void waitForStarting() {
        flowRunner.waitForState(UP);
        OrionUtils.waitForCondition(() -> orionProvider.getEventManager().getUnprocessedLength() == 0);
    }

    public void waitForShutdown() {
        flowRunner.waitForState(OFF);
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
