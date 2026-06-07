package pro.deta.orion.lifecycle;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.event.type.ApplicationShutdownRequestedEvent;
import pro.deta.orion.lifecycle.flow.LifecycleFlowRunner;
import pro.deta.orion.lifecycle.state.AggregateStateMachine;
import pro.deta.orion.util.OrionProvider;
import pro.deta.orion.util.OrionUtils;

import java.util.concurrent.TimeUnit;

import static pro.deta.orion.ApplicationState.STARTING;
import static pro.deta.orion.ApplicationState.STOPPING;
import static pro.deta.orion.ApplicationState.OFF;
import static pro.deta.orion.ApplicationState.UP;

@Slf4j
@Singleton
public class OrionApplicationLifecycle {
    public final static ApplicationBootstrap BOOTSTRAP = new ApplicationBootstrap();
    private static final long SHUTDOWN_REQUEST_DELAY_MILLIS = 100;

    private final AggregateStateMachine runtimeStateMachine;
    private final OrionProvider orionProvider;
    private final LifecycleFlowRunner flowRunner;

    @Inject
    public OrionApplicationLifecycle(
            ApplicationStateHolder applicationStateHolder,
            @Named("runtime") AggregateStateMachine runtimeStateMachine,
            OrionProvider orionProvider) {
        this.runtimeStateMachine = runtimeStateMachine;
        this.orionProvider = orionProvider;
        flowRunner = new LifecycleFlowRunner(applicationStateHolder, this::runRuntimeTransition);
        registerLifecycleEventHandlers();
    }

    private boolean runRuntimeTransition(ApplicationState state) {
        log.warn("[{}] lifecycle transition initiated...", state);
        boolean isSuccess = runRuntimeStateMachine(state);
        log.warn("[{}] lifecycle transition completed: {}.", state, isSuccess ? "success" : "failure");
        return isSuccess;
    }

    private boolean runRuntimeStateMachine(ApplicationState state) {
        try {
            if (state == STARTING) {
                runtimeStateMachine.start();
            } else if (state == STOPPING) {
                runtimeStateMachine.stop();
            }
            return true;
        } catch (RuntimeException e) {
            log.error("[{}] runtime state machine transition failed.", state, e);
            return false;
        }
    }

    public String describeLifecycle() {
        return flowRunner.describeFlows() + '\n' + runtimeStateMachine.describeStatus();
    }

    public ApplicationState runApplication() {
        if (log.isDebugEnabled()) {
            log.debug("Lifecycle before initialization:\n{}", describeLifecycle());
        }
        return flowRunner.runStartup();
    }

    public ApplicationState shutdownApplication() {
        log.info("System shutdown process initiated.");
        return flowRunner.runShutdown();
    }

    private void doShutdown() {
        shutdownApplication();
    }

    private void registerLifecycleEventHandlers() {
        orionProvider.getEventManager().registerTypeHandler(
                ApplicationShutdownRequestedEvent.class,
                this::handleShutdownRequested);
    }

    private void handleShutdownRequested(ApplicationShutdownRequestedEvent event) {
        orionProvider.getOrionExecutor().schedule(this::doShutdown, SHUTDOWN_REQUEST_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    public String describeFlows() {
        return flowRunner.describeFlows();
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
}
