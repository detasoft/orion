package pro.deta.orion.agent.server;

import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.server.auth.AgentControlAuthenticator;
import pro.deta.orion.agent.server.auth.AgentdProvisioningControl;
import pro.deta.orion.agent.server.auth.AuthenticatedAgentConnections;
import pro.deta.orion.agent.server.auth.SessionReconciliationPublisher;
import pro.deta.orion.agent.server.connection.AgentControlHandler;
import pro.deta.orion.agent.server.command.SessionCommandService;
import pro.deta.orion.agent.server.journal.FileSystemSessionJournalStorage;
import pro.deta.orion.agent.server.journal.JournalStorageConfig;
import pro.deta.orion.agent.server.replication.SessionReplicationService;
import pro.deta.orion.agent.server.registry.AgentRecord;
import pro.deta.orion.agent.server.registry.AgentRegistryException;
import pro.deta.orion.agent.server.registry.FileSystemAgentRegistry;
import pro.deta.orion.agent.server.registry.FileSystemSessionRegistry;
import pro.deta.orion.lifecycle.state.ServiceLifecycleStateMachineAdapter.ServiceLifecycle;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Owns the durable and transient server-side state behind the Agent control endpoint. */
public final class AgentSessionServer implements AgentControlHandler, ServiceLifecycle {
    private static final Session TERMINAL_SESSION = new Session() {
        @Override
        public void onMessage(AgentMessage message) {
        }

        @Override
        public void onClosed(Throwable failure) {
        }
    };

    private final Path root;
    private final Clock clock;
    private final Duration heartbeatDeadline;
    private FileSystemAgentRegistry agentRegistry;
    private FileSystemSessionRegistry sessionRegistry;
    private AuthenticatedAgentConnections connections;
    private AgentControlAuthenticator authenticator;
    private FileSystemSessionJournalStorage journalStorage;
    private SessionCommandService commandService;

    public AgentSessionServer(Path root) {
        this(root, Clock.systemUTC(), AuthenticatedAgentConnections.DEFAULT_HEARTBEAT_DEADLINE);
    }

    @TestOnly
    public static AgentSessionServer withPolicy(Path root, Clock clock, Duration heartbeatDeadline) {
        return new AgentSessionServer(root, clock, heartbeatDeadline);
    }

    private AgentSessionServer(Path root, Clock clock, Duration heartbeatDeadline) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.heartbeatDeadline = Objects.requireNonNull(heartbeatDeadline, "heartbeatDeadline");
    }

    @Override
    public synchronized void onStart() throws Exception {
        if (authenticator != null) {
            return;
        }
        FileSystemAgentRegistry openedAgents = null;
        FileSystemSessionRegistry openedSessions = null;
        FileSystemSessionJournalStorage openedJournals = null;
        SessionCommandService openedCommands = null;
        AuthenticatedAgentConnections openedConnections = null;
        try {
            openedAgents = new FileSystemAgentRegistry(root.resolve("agents"));
            openedSessions = new FileSystemSessionRegistry(root.resolve("sessions"));
            openedJournals = new FileSystemSessionJournalStorage(
                    root.resolve("journals"), new JournalStorageConfig(AgentProtocolLimits.journalDefaults()));
            AtomicReference<SessionCommandService> commands = new AtomicReference<>();
            SessionReconciliationPublisher reconciliation =
                    new SessionReconciliationPublisher(openedSessions,
                            context -> commands.get().controlSession(context.agentId()));
            openedConnections = AuthenticatedAgentConnections.withPolicy(
                    reconciliation::publish, clock, heartbeatDeadline);
            openedCommands = new SessionCommandService(
                    root.resolve("commands"), openedAgents, openedSessions,
                    openedConnections, openedJournals);
            commands.set(openedCommands);
            AgentControlAuthenticator openedAuthenticator =
                    new AgentControlAuthenticator(openedAgents, openedConnections::activate);
            agentRegistry = openedAgents;
            sessionRegistry = openedSessions;
            connections = openedConnections;
            authenticator = openedAuthenticator;
            journalStorage = openedJournals;
            commandService = openedCommands;
        } catch (Exception failure) {
            close(openedCommands, failure);
            close(openedConnections, failure);
            close(openedJournals, failure);
            closeAfterFailedStart(openedSessions, openedAgents, failure);
            throw failure;
        }
    }

    @Override
    public void onStop() throws Exception {
        FileSystemAgentRegistry agents;
        FileSystemSessionRegistry sessions;
        AuthenticatedAgentConnections activeConnections;
        SessionCommandService commands;
        FileSystemSessionJournalStorage journals;
        synchronized (this) {
            authenticator = null;
            agents = agentRegistry;
            sessions = sessionRegistry;
            activeConnections = connections;
            agentRegistry = null;
            sessionRegistry = null;
            connections = null;
            commands = commandService;
            journals = journalStorage;
            commandService = null;
            journalStorage = null;
        }
        Exception failure = null;
        failure = close(activeConnections, failure);
        failure = close(commands, failure);
        failure = close(journals, failure);
        failure = close(sessions, failure);
        failure = close(agents, failure);
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public synchronized boolean isRunning() {
        return authenticator != null;
    }

    @Override
    public Session open(Connection connection) {
        Objects.requireNonNull(connection, "connection");
        AgentControlAuthenticator current;
        synchronized (this) {
            current = authenticator;
        }
        if (current != null) {
            return current.open(connection);
        }
        connection.close();
        return TERMINAL_SESSION;
    }

    public synchronized AgentRecord registerAgent(AgentId agentId, String displayName)
            throws AgentRegistryException {
        return requireAgentRegistry().register(agentId, displayName);
    }

    public synchronized SessionCommandService commandService() {
        if (commandService == null) {
            throw new IllegalStateException("Agent session server is not running");
        }
        return commandService;
    }

    public synchronized SessionReplicationService replicationService() {
        if (journalStorage == null) {
            throw new IllegalStateException("Agent session server is not running");
        }
        return new SessionReplicationService(journalStorage);
    }

    public synchronized AgentdProvisioningControl provisioningControl(
            AgentId agentId,
            URI serverUri,
            String stateDirectory,
            int maxFrameBytes,
            String agentVersion) {
        return new AgentdProvisioningControl(
                requireAgentRegistry(),
                authenticator,
                connections,
                agentId,
                serverUri,
                stateDirectory,
                maxFrameBytes,
                agentVersion);
    }

    private FileSystemAgentRegistry requireAgentRegistry() {
        if (agentRegistry == null) {
            throw new IllegalStateException("Agent session server is not running");
        }
        return agentRegistry;
    }

    private static void closeAfterFailedStart(
            FileSystemSessionRegistry sessions,
            FileSystemAgentRegistry agents,
            Exception failure) {
        Exception closeFailure = close(sessions, null);
        closeFailure = close(agents, closeFailure);
        if (closeFailure != null) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static Exception close(AutoCloseable closeable, Exception previous) {
        if (closeable == null) {
            return previous;
        }
        try {
            closeable.close();
        } catch (Exception failure) {
            if (previous == null) {
                return failure;
            }
            previous.addSuppressed(failure);
        }
        return previous;
    }
}
