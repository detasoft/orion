package pro.deta.orion.agent.server.auth;

import pro.deta.orion.agent.protocol.AgentAuthentication;
import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentInstanceId;
import pro.deta.orion.agent.protocol.AgentLaunchId;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentProtocolVersion;
import pro.deta.orion.agent.protocol.ConnectionId;
import pro.deta.orion.agent.protocol.JournalFormatVersion;
import pro.deta.orion.agent.protocol.MachineInfo;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.server.connection.AgentControlHandler;
import pro.deta.orion.agent.server.registry.AgentRecord;
import pro.deta.orion.agent.server.registry.AgentRegistryException;
import pro.deta.orion.agent.server.registry.FileSystemAgentRegistry;
import pro.deta.orion.lifecycle.state.TestOnly;
import pro.deta.orion.provisioning.ProvisioningLaunchPermit;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Authenticates a transport connection against the one durable agent registry.
 * The two-minute permit is single-use. The 30-minute reconnect expiry is renewed from server time only by
 * an already authenticated, launch-guarded context. Initial WELCOME loss requires a new launch; reconnect
 * WELCOME loss can retry the still-valid token.
 */
public final class AgentControlAuthenticator implements AgentControlHandler {
    public static final Duration DEFAULT_PERMIT_LIFETIME = Duration.ofMinutes(2);
    public static final Duration DEFAULT_RECONNECT_TOKEN_LIFETIME = Duration.ofMinutes(30);
    private static final Duration MAX_CREDENTIAL_LIFETIME = Duration.ofDays(1);
    private static final int CREDENTIAL_BYTES = 32;

    private final FileSystemAgentRegistry registry;
    private final Function<AuthenticatedConnectionContext, Session> publisher;
    private final Clock clock;
    private final SecureRandom random;
    private final Duration permitLifetime;
    private final Duration reconnectTokenLifetime;

    public AgentControlAuthenticator(
            FileSystemAgentRegistry registry,
            Function<AuthenticatedConnectionContext, Session> publisher) {
        this(registry, publisher, Clock.systemUTC(), new SecureRandom(),
                DEFAULT_PERMIT_LIFETIME, DEFAULT_RECONNECT_TOKEN_LIFETIME);
    }

    @TestOnly
    static AgentControlAuthenticator withPolicy(
            FileSystemAgentRegistry registry,
            Function<AuthenticatedConnectionContext, Session> publisher,
            Clock clock,
            SecureRandom random,
            Duration permitLifetime,
            Duration reconnectTokenLifetime) {
        return new AgentControlAuthenticator(
                registry, publisher, clock, random, permitLifetime, reconnectTokenLifetime);
    }

    private AgentControlAuthenticator(
            FileSystemAgentRegistry registry,
            Function<AuthenticatedConnectionContext, Session> publisher,
            Clock clock,
            SecureRandom random,
            Duration permitLifetime,
            Duration reconnectTokenLifetime) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.permitLifetime = credentialLifetime(permitLifetime, "permitLifetime");
        this.reconnectTokenLifetime = credentialLifetime(
                reconnectTokenLifetime, "reconnectTokenLifetime");
    }

    public PermitIssueResult issueLaunchPermit(
            AgentId agentId, AgentGeneration generation, AgentLaunchId launchId) {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(launchId, "launchId");
        byte[] credential = randomBytes();
        byte[] encoded = null;
        try {
            Instant now = clock.instant();
            registry.installLaunchPermit(
                    agentId,
                    generation,
                    launchId,
                    new AgentRecord.Credential(digest(credential), now.plus(permitLifetime)),
                    now);
            encoded = Base64.getUrlEncoder().withoutPadding().encode(credential);
            return new PermitIssueResult.Issued(new ProvisioningLaunchPermit(encoded));
        } catch (AgentRegistryException failure) {
            return new PermitIssueResult.Failed(classify(failure));
        } finally {
            Arrays.fill(credential, (byte) 0);
            if (encoded != null) {
                Arrays.fill(encoded, (byte) 0);
            }
        }
    }

    @Override
    public Session open(Connection connection) {
        return new AuthenticationSession(Objects.requireNonNull(connection, "connection"));
    }

    public sealed interface PermitIssueResult {
        record Issued(ProvisioningLaunchPermit permit) implements PermitIssueResult {
            public Issued {
                Objects.requireNonNull(permit, "permit");
            }
        }

        record Failed(Failure reason) implements PermitIssueResult {
            public Failed {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }

    public enum Failure {
        REJECTED,
        PERSISTENCE_FAILED
    }

    private final class AuthenticationSession implements Session {
        private final Connection connection;
        private State state = State.WAITING_FOR_HELLO;
        private Session authenticated;

        private AuthenticationSession(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void onMessage(AgentMessage message) {
            Session delegate;
            AgentMessage.Hello hello = null;
            boolean rejected = false;
            synchronized (this) {
                delegate = state == State.AUTHENTICATED ? authenticated : null;
                if (delegate == null) {
                    if (state == State.WAITING_FOR_HELLO && message instanceof AgentMessage.Hello value) {
                        state = State.AUTHENTICATING;
                        hello = value;
                    } else {
                        rejected = closeLocked();
                    }
                }
            }
            if (delegate != null) {
                delegate.onMessage(message);
            } else if (hello != null) {
                authenticate(hello);
            } else if (rejected) {
                connection.close();
            }
        }

        @Override
        public void onClosed(Throwable failure) {
            Session delegate;
            synchronized (this) {
                closeLocked();
                delegate = authenticated;
                authenticated = null;
            }
            if (delegate != null) {
                delegate.onClosed(failure);
            }
        }

        private void authenticate(AgentMessage.Hello hello) {
            if (!hello.protocolVersion().equals(AgentProtocolVersion.CURRENT)
                    || !hello.journalFormatVersion().equals(JournalFormatVersion.CURRENT)
                    || hello.authentication().isEmpty()) {
                reject();
                return;
            }
            AgentAuthentication authentication = hello.authentication().orElseThrow();
            byte[] credential = authentication.credential().toByteArray();
            byte[] welcomeToken = null;
            try {
                AgentRecord.CredentialDigest credentialDigest = digest(credential);
                if (authentication.kind() == AgentAuthentication.Kind.LAUNCH_PERMIT) {
                    welcomeToken = randomBytes();
                    Instant now = clock.instant();
                    AgentRecord.CredentialDigest tokenDigest = digest(welcomeToken);
                    registry.consumeLaunchPermit(
                            hello.agentId(),
                            authentication.generation(),
                            authentication.launchId(),
                            credentialDigest,
                            new AgentRecord.Credential(tokenDigest, now.plus(reconnectTokenLifetime)),
                            now);
                    sendWelcome(
                            hello,
                            authentication.generation(),
                            authentication.launchId(),
                            tokenDigest,
                            welcomeToken);
                } else {
                    Instant now = clock.instant();
                    registry.verifyReconnectToken(
                            hello.agentId(),
                            authentication.generation(),
                            authentication.launchId(),
                            credentialDigest,
                            now);
                    sendWelcome(
                            hello,
                            authentication.generation(),
                            authentication.launchId(),
                            credentialDigest,
                            credential);
                }
            } catch (AgentRegistryException failure) {
                reject();
            } finally {
                Arrays.fill(credential, (byte) 0);
                if (welcomeToken != null) {
                    Arrays.fill(welcomeToken, (byte) 0);
                }
            }
        }

        private void sendWelcome(
                AgentMessage.Hello hello,
                AgentGeneration generation,
                AgentLaunchId launchId,
                AgentRecord.CredentialDigest tokenDigest,
                byte[] welcomeToken) {
            ConnectionId connectionId = new ConnectionId(UUID.randomUUID().toString());
            AgentMessage.Welcome welcome = new AgentMessage.Welcome(
                    AgentProtocolVersion.CURRENT,
                    JournalFormatVersion.CURRENT,
                    connectionId,
                    Map.of(),
                    Optional.of(ProtocolBytes.copyOf(welcomeToken)));
            synchronized (this) {
                if (state != State.AUTHENTICATING) {
                    return;
                }
                state = State.WELCOME_PENDING;
            }
            try {
                connection.send(welcome).whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        reject();
                        return;
                    }
                    publish(hello, generation, launchId, connectionId, tokenDigest);
                });
            } catch (RuntimeException failure) {
                reject();
            }
        }

        private void publish(
                AgentMessage.Hello hello,
                AgentGeneration generation,
                AgentLaunchId launchId,
                ConnectionId connectionId,
                AgentRecord.CredentialDigest tokenDigest) {
            synchronized (this) {
                if (state != State.WELCOME_PENDING) {
                    return;
                }
                state = State.PUBLISHING;
            }
            Session published;
            try {
                registry.verifyReconnectToken(
                        hello.agentId(), generation, launchId, tokenDigest, clock.instant());
                AuthenticatedConnectionContext context = new AuthenticatedConnectionContext(
                        hello.agentId(),
                        generation,
                        launchId,
                        hello.instanceId(),
                        hello.agentVersion(),
                        hello.machine(),
                        hello.capabilities(),
                        connectionId,
                        connection,
                        () -> renewIfOpen(
                                hello.agentId(),
                                generation,
                                launchId,
                                tokenDigest),
                        (agentVersion, machine, capabilities, observedAt) -> observe(
                                hello.agentId(),
                                generation,
                                launchId,
                                hello.instanceId(),
                                agentVersion,
                                machine,
                                capabilities,
                                observedAt));
                published = Objects.requireNonNull(publisher.apply(context), "authenticated session");
            } catch (Throwable failure) {
                reject();
                return;
            }
            boolean accepted;
            synchronized (this) {
                accepted = state == State.PUBLISHING;
                if (accepted) {
                    authenticated = published;
                    state = State.AUTHENTICATED;
                }
            }
            if (accepted) {
                connection.handshakeComplete();
                if (published instanceof AuthenticatedSession authenticatedSession) {
                    authenticatedSession.onAuthenticated();
                }
            } else {
                published.onClosed(null);
            }
        }

        private void reject() {
            boolean close;
            synchronized (this) {
                close = closeLocked();
            }
            if (close) {
                connection.close();
            }
        }

        private synchronized AuthenticatedConnectionContext.RenewalResult renewIfOpen(
                AgentId agentId,
                AgentGeneration generation,
                AgentLaunchId launchId,
                AgentRecord.CredentialDigest tokenDigest) {
            if (state != State.PUBLISHING && state != State.AUTHENTICATED) {
                return AuthenticatedConnectionContext.RenewalResult.REJECTED;
            }
            return renew(agentId, generation, launchId, tokenDigest);
        }

        private boolean closeLocked() {
            if (state == State.CLOSED) {
                return false;
            }
            state = State.CLOSED;
            return true;
        }
    }

    private AuthenticatedConnectionContext.RenewalResult renew(
            AgentId agentId,
            AgentGeneration generation,
            AgentLaunchId launchId,
            AgentRecord.CredentialDigest tokenDigest) {
        Instant now = clock.instant();
        try {
            registry.renewReconnectToken(
                    agentId,
                    generation,
                    launchId,
                    tokenDigest,
                    now.plus(reconnectTokenLifetime),
                    now);
            return AuthenticatedConnectionContext.RenewalResult.RENEWED;
        } catch (AgentRegistryException failure) {
            return classify(failure) == Failure.PERSISTENCE_FAILED
                    ? AuthenticatedConnectionContext.RenewalResult.PERSISTENCE_FAILED
                    : AuthenticatedConnectionContext.RenewalResult.REJECTED;
        }
    }

    private AuthenticatedConnectionContext.ObservationResult observe(
            AgentId agentId,
            AgentGeneration generation,
            AgentLaunchId launchId,
            AgentInstanceId instanceId,
            String agentVersion,
            MachineInfo machine,
            Map<String, String> capabilities,
            Instant observedAt) {
        try {
            registry.recordObservation(agentId, new AgentRecord.Observation(
                    generation,
                    launchId,
                    instanceId,
                    agentVersion,
                    machine,
                    capabilities,
                    observedAt));
            return AuthenticatedConnectionContext.ObservationResult.RECORDED;
        } catch (IllegalArgumentException failure) {
            return AuthenticatedConnectionContext.ObservationResult.REJECTED;
        } catch (AgentRegistryException failure) {
            return classify(failure) == Failure.PERSISTENCE_FAILED
                    ? AuthenticatedConnectionContext.ObservationResult.PERSISTENCE_FAILED
                    : AuthenticatedConnectionContext.ObservationResult.REJECTED;
        }
    }

    private byte[] randomBytes() {
        byte[] bytes = new byte[CREDENTIAL_BYTES];
        random.nextBytes(bytes);
        return bytes;
    }

    private static AgentRecord.CredentialDigest digest(byte[] credential) {
        try {
            return new AgentRecord.CredentialDigest(
                    MessageDigest.getInstance(AgentRecord.CredentialDigest.ALGORITHM).digest(credential));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static Duration credentialLifetime(Duration lifetime, String name) {
        Objects.requireNonNull(lifetime, name);
        if (lifetime.isZero() || lifetime.isNegative() || lifetime.compareTo(MAX_CREDENTIAL_LIFETIME) > 0) {
            throw new IllegalArgumentException(name + " must be positive and at most one day");
        }
        return lifetime;
    }

    private static Failure classify(AgentRegistryException failure) {
        return switch (failure.reason()) {
            case IO_FAILURE, INDETERMINATE, STORED_CORRUPTION, CLOSED -> Failure.PERSISTENCE_FAILED;
            case NOT_FOUND, INVALID_STATE, CONFLICT -> Failure.REJECTED;
        };
    }

    private enum State {
        WAITING_FOR_HELLO,
        AUTHENTICATING,
        WELCOME_PENDING,
        PUBLISHING,
        AUTHENTICATED,
        CLOSED
    }
}
