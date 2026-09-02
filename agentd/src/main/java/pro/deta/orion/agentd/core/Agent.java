package pro.deta.orion.agentd.core;

import java.util.List;
import java.util.Objects;

import org.eclipse.jetty.util.ssl.SslContextFactory;
import pro.deta.orion.agent.protocol.AgentProtocolCodec;
import pro.deta.orion.agent.protocol.MachineInfo;
import pro.deta.orion.agentd.platform.LocalMachineInfo;
import pro.deta.orion.agentd.transport.AgentTransport;
import pro.deta.orion.agentd.transport.JettyHttp2Transport;

public final class Agent implements AutoCloseable {
    private final AgentConfiguration configuration;
    private final AgentLifecycle lifecycle;
    private final AgentLaunchContext launchContext;

    public Agent(AgentConfiguration configuration, List<? extends AgentService> services) {
        this(configuration, services, null);
    }

    private Agent(
            AgentConfiguration configuration,
            List<? extends AgentService> services,
            AgentLaunchContext launchContext
    ) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.lifecycle = new AgentLifecycle(services);
        this.launchContext = launchContext;
    }

    public static Agent create(AgentConfiguration configuration, AgentLaunchContext context) {
        SslContextFactory.Client tls = new SslContextFactory.Client();
        AgentTransport transport = new JettyHttp2Transport(
                configuration.serverUri(), tls, configuration.protocolLimits(), 64, 64);
        return create(configuration, context, transport, new LocalMachineInfo().read());
    }

    static Agent create(
            AgentConfiguration configuration,
            AgentLaunchContext context,
            AgentTransport transport,
            MachineInfo machine
    ) {
        AgentProcessLock processLock = new AgentProcessLock(configuration.processLockFile(), context);
        AgentControlService control = new AgentControlService(
                transport,
                new AgentProtocolCodec(configuration.protocolLimits()),
                new AgentHandshake(),
                context,
                configuration.agentVersion(),
                machine,
                java.util.Map.of());
        return new Agent(configuration, List.of(processLock, control), context);
    }

    public AgentConfiguration configuration() {
        return configuration;
    }

    public AgentLifecycle.State state() {
        return lifecycle.state();
    }

    public void start() {
        try {
            lifecycle.start();
        } catch (RuntimeException failure) {
            if (launchContext != null) {
                launchContext.close();
            }
            throw failure;
        }
    }

    public void awaitTermination() throws InterruptedException {
        lifecycle.awaitTermination();
    }

    @Override
    public void close() {
        try {
            lifecycle.close();
        } finally {
            if (launchContext != null) {
                launchContext.close();
            }
        }
    }
}
