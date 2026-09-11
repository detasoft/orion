package pro.deta.orion.agentd.core;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

import org.eclipse.jetty.util.ssl.SslContextFactory;
import pro.deta.orion.agent.protocol.AgentProtocolCodec;
import pro.deta.orion.agent.protocol.MachineInfo;
import pro.deta.orion.agentd.platform.LocalMachineInfo;
import pro.deta.orion.agentd.session.ControlHostProbe;
import pro.deta.orion.agentd.session.FileSystemJournalProbe;
import pro.deta.orion.agentd.session.JsonSessionManifestReader;
import pro.deta.orion.agentd.session.SessionControlClient;
import pro.deta.orion.agentd.session.SessionDiscovery;
import pro.deta.orion.agentd.session.SessionDiscoveryMonitor;
import pro.deta.orion.agentd.session.SessionRegistry;
import pro.deta.orion.agentd.transport.AgentTransport;
import pro.deta.orion.agentd.transport.JettyHttp2Transport;

public final class Agent implements AutoCloseable {
    private static final Duration SESSION_CONTROL_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration SESSION_DISCOVERY_INTERVAL = Duration.ofSeconds(1);

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
        SessionRegistry registry = new SessionRegistry();
        AgentService discovery = new DiscoveryService(configuration.sessionsDirectory(), registry);
        AgentControlService control = new AgentControlService(
                transport,
                new AgentProtocolCodec(configuration.protocolLimits()),
                new AgentHandshake(),
                context,
                configuration.agentVersion(),
                machine,
                java.util.Map.of(),
                registry);
        return new Agent(configuration, List.of(processLock, discovery, control), context);
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

    private static final class DiscoveryService implements AgentService {
        private final Path sessionsDirectory;
        private final SessionRegistry registry;
        private SessionDiscoveryMonitor monitor;

        private DiscoveryService(Path sessionsDirectory, SessionRegistry registry) {
            this.sessionsDirectory = sessionsDirectory;
            this.registry = registry;
        }

        @Override
        public void start() throws IOException {
            SessionDiscovery discovery = new SessionDiscovery(
                    sessionsDirectory,
                    new JsonSessionManifestReader(),
                    new ControlHostProbe(new SessionControlClient(SESSION_CONTROL_TIMEOUT)),
                    new FileSystemJournalProbe(),
                    registry);
            monitor = new SessionDiscoveryMonitor(
                    sessionsDirectory, discovery, SESSION_DISCOVERY_INTERVAL);
            monitor.start();
        }

        @Override
        public void close() throws IOException {
            if (monitor != null) {
                monitor.close();
            }
        }
    }
}
