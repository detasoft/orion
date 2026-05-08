package pro.deta.orion.lifecycle;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.ApplicationState;
import pro.deta.orion.internal.OrionExecutor;
import pro.deta.orion.lifecycle.flow.LifecycleFlow;
import pro.deta.orion.lifecycle.flow.LifecycleStep;
import pro.deta.orion.lifecycle.listener.RegisteredListener;
import pro.deta.orion.lifecycle.listener.RegisteredListenerResult;
import pro.deta.orion.lifecycle.task.LifecycleTaskRegistration;
import pro.deta.orion.util.LogInitializer;
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

    private final ApplicationStateHolder applicationStateHolder;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition lockCondition = lock.newCondition();

    private final List<RegisteredListener> applicationStageEventListeners = new CopyOnWriteArrayList<>();
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
    public RegisteredListener register(RegisteredListener registeredListener) {
        applicationStageEventListeners.add(registeredListener);
        return registeredListener;
    }

    @Override
    public LifecycleTaskRegistration register(LifecycleTaskRegistration registration) {
        lifecycleTaskRegistrations.add(registration);
        return registration;
    }

    private boolean onStage(ApplicationState state) {
        log.warn("[{}] stage initiated...", state);
        List<RegisteredListenerResult> resultList = getSortedOrionStageListeners(state);
        log.trace("[{}] stage listeners found:\n{}", state, format(resultList));
        for (int i = 0; i < resultList.size(); i++) {
            RegisteredListenerResult result = resultList.get(i);
            // we need to wait for all previous listeners to complete before submitting
            if (result.neededToWait()) {
                listenersCompleted(resultList, i);
            }
            result.execute(orionProvider.getOrionExecutor());

            if (result.neededToWait()) {
                result.waitListener();
            }
        }

        for (RegisteredListenerResult result : resultList) {
            result.waitListener();
        }
        log.trace("[{}] main listeners completed...", state);
        for (RegisteredListenerResult result : resultList) {
            result.waitFeaturesIfNeeded();
        }

        boolean isSuccess = isSuccess(resultList);
        log.trace("[{}] stage completed\n{}", state, formatStatus(resultList));
        log.warn("[{}] stage completed: {}.", state, isSuccess ? "success" : "failure");
        return isSuccess;
    }

    private boolean isSuccess(List<RegisteredListenerResult> resultList) {
        for (RegisteredListenerResult result : resultList) {
            if (!result.isSuccess())
                return false;
        }
        return true;
    }

    public StringBuilder formatStatus(List<RegisteredListenerResult> resultList) {
        StringBuilder stringBuilder = new StringBuilder();
        for (RegisteredListenerResult result : resultList) {
            result.formatStatus(stringBuilder);
        }
        return stringBuilder;
    }


    private void listenersCompleted(List<RegisteredListenerResult> resultList, int i) {
        for (int j = 0; j < i; j++) {
            RegisteredListenerResult res = resultList.get(j);
            res.waitListener();
        }
    }

    private String format(List<RegisteredListenerResult> listeners) {
        StringBuilder builder = new StringBuilder();
        for(RegisteredListenerResult l : listeners) {
            l.format(builder);
        }
        return builder.toString();
    }

    private List<RegisteredListenerResult> getSortedOrionStageListeners(ApplicationState state) {
        return applicationStageEventListeners.stream()
                .filter( it -> it.getState() == state)
                .sorted(RegisteredListener.COMPARATOR)
                .map(RegisteredListenerResult::new)
                .toList();
    }

    public ApplicationState runApplication() {
        return runFlow(LifecycleFlow.STARTUP);
    }

    private ApplicationState runFlow(LifecycleFlow flow) {
        for (LifecycleStep step : flow.steps()) {
            if (step.runListeners() && !onStage(step.from())) {
                applicationStateHolder.moveStateFrom(step.from(), step.failure());
                return applicationStateHolder.getState();
            }
            applicationStateHolder.moveStateFrom(step.from(), step.success());
        }
        return applicationStateHolder.getState();
    }

    private void doShutdown() {
        log.info("System shutdown process initiated.");
        runFlow(LifecycleFlow.SHUTDOWN);
    }

    public String describeFlows() {
        return LifecycleFlow.STARTUP.describe() + "\n" + LifecycleFlow.SHUTDOWN.describe();
    }

//    private void waitForBeginShutdown() {
//        doInLock(() -> {
//            while (applicationStateHolder.isActive()) {
//                try {
//                    lockCondition.awaitNanos(1000_000);
//                } catch (InterruptedException e) {
//                    log.debug("Interrupted while waiting for begin shutdown", e);
//                }
//            }
//            lockCondition.signalAll();
//
//        });
//    }

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

}
