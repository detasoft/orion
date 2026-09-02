package pro.deta.orion.agentd.core;

import java.util.List;
import java.util.Objects;

public final class Agent implements AutoCloseable {
    private final AgentConfiguration configuration;
    private final AgentLifecycle lifecycle;

    public Agent(AgentConfiguration configuration, List<? extends AgentService> services) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.lifecycle = new AgentLifecycle(services);
    }

    public static Agent create(AgentConfiguration configuration) {
        return new Agent(configuration, List.of());
    }

    public AgentConfiguration configuration() {
        return configuration;
    }

    public AgentLifecycle.State state() {
        return lifecycle.state();
    }

    public void start() {
        lifecycle.start();
    }

    public void awaitTermination() throws InterruptedException {
        lifecycle.awaitTermination();
    }

    @Override
    public void close() {
        lifecycle.close();
    }
}
