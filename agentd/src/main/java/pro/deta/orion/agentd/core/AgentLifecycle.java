package pro.deta.orion.agentd.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

public final class AgentLifecycle implements AutoCloseable {
    public enum State {
        NEW,
        STARTING,
        RUNNING,
        STOPPING,
        TERMINATED,
        FAILED
    }

    private final List<AgentService> services;
    private final List<AgentService> startedServices = new ArrayList<>();
    private final CountDownLatch terminated = new CountDownLatch(1);
    private State state = State.NEW;

    public AgentLifecycle(List<? extends AgentService> services) {
        Objects.requireNonNull(services, "services");
        this.services = List.copyOf(services);
    }

    public synchronized State state() {
        return state;
    }

    public synchronized void start() {
        if (state != State.NEW) {
            throw new IllegalStateException("AgentD lifecycle cannot start from state " + state);
        }
        state = State.STARTING;
        try {
            for (AgentService service : services) {
                startedServices.add(service);
                service.start();
            }
            state = State.RUNNING;
        } catch (Exception startupFailure) {
            closeStartedServices(startupFailure);
            state = State.FAILED;
            terminated.countDown();
            throw new AgentStartupException("AgentD service startup failed", startupFailure);
        }
    }

    public void awaitTermination() throws InterruptedException {
        terminated.await();
    }

    @Override
    public void close() {
        synchronized (this) {
            if (state == State.TERMINATED || state == State.FAILED || state == State.STOPPING) {
                return;
            }
            state = State.STOPPING;
        }

        RuntimeException shutdownFailure = closeStartedServices(null);
        synchronized (this) {
            state = State.TERMINATED;
            terminated.countDown();
        }
        if (shutdownFailure != null) {
            throw shutdownFailure;
        }
    }

    private RuntimeException closeStartedServices(Throwable cause) {
        RuntimeException failure = null;
        List<AgentService> reverseOrder = new ArrayList<>(startedServices);
        Collections.reverse(reverseOrder);
        for (AgentService service : reverseOrder) {
            try {
                service.close();
            } catch (Exception closeFailure) {
                if (cause != null) {
                    cause.addSuppressed(closeFailure);
                } else if (failure == null) {
                    failure = new AgentShutdownException("AgentD service shutdown failed", closeFailure);
                } else {
                    failure.addSuppressed(closeFailure);
                }
            }
        }
        startedServices.clear();
        return failure;
    }
}
