package pro.deta.orion.agent.server.registry;

import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentLabel;
import pro.deta.orion.agent.protocol.AgentLaunchId;
import pro.deta.orion.agent.protocol.AgentInstanceId;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static pro.deta.orion.agent.server.registry.AgentRegistryException.Reason.CLOSED;
import static pro.deta.orion.agent.server.registry.AgentRegistryException.Reason.CONFLICT;
import static pro.deta.orion.agent.server.registry.AgentRegistryException.Reason.INDETERMINATE;
import static pro.deta.orion.agent.server.registry.AgentRegistryException.Reason.INVALID_STATE;
import static pro.deta.orion.agent.server.registry.AgentRegistryException.Reason.IO_FAILURE;
import static pro.deta.orion.agent.server.registry.AgentRegistryException.Reason.NOT_FOUND;
import static pro.deta.orion.agent.server.registry.AgentRegistryException.Reason.STORED_CORRUPTION;

/**
 * Owns durable agent snapshots; recorded launch state is not connection authority.
 * The recovery caller installs a permit only after making the launch safe and supplies its digest,
 * expiry, and current time. Credential issuance and lifetime policy remain with that caller.
 * Authentication supplies digests only; successful credential mutations return after durable publication.
 * Token renewal retains the same digest and never shortens its expiry. Verification is a snapshot,
 * not authority over a later connection or generation change. The latest launch is pending authorization;
 * registration holds the current instance and reconnect credential. Empty expected-instance allocation is
 * initial issuance; replacement requires the exact current instance. Latest-generation matching makes an
 * older pending permit unusable without persisting a second predecessor identity.
 * Shared authority leases precede service, callback, and registry locks. Registration replacement and
 * shutdown take the exclusive lease before the registry lock; callbacks never run under the exclusive lease.
 */
public final class FileSystemAgentRegistry implements AutoCloseable {
    private final Path root;
    private final AgentRecordCodec codec = new AgentRecordCodec();
    private final AgentRegistryFileOperations operations;
    private final AgentRegistryFileOperations.RootOwnership rootOwnership;
    private final Map<AgentLabel, AgentRecord> records = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final ReentrantReadWriteLock authority = new ReentrantReadWriteLock();
    private boolean closed;
    private boolean indeterminate;

    public FileSystemAgentRegistry(Path root) throws AgentRegistryException {
        this(root, new AgentRegistryFileOperations());
    }

    @TestOnly
    static FileSystemAgentRegistry withOperations(
            Path root,
            AgentRegistryFileOperations operations) throws AgentRegistryException {
        return new FileSystemAgentRegistry(root, operations);
    }

    private FileSystemAgentRegistry(Path root, AgentRegistryFileOperations operations)
            throws AgentRegistryException {
        Objects.requireNonNull(root, "root");
        this.operations = Objects.requireNonNull(operations, "operations");
        AgentRegistryFileOperations.RootOwnership acquired = null;
        try {
            this.root = operations.prepareRoot(root.toAbsolutePath().normalize());
            acquired = operations.acquireRoot(this.root);
            this.rootOwnership = acquired;
            recover();
        } catch (AgentRegistryFileOperations.RootInUseException e) {
            throw new AgentRegistryException(CONFLICT, "Agent registry root is already owned", e);
        } catch (AgentRegistryException e) {
            releaseAfterOpenFailure(acquired, e);
            throw e;
        } catch (IOException e) {
            AgentRegistryException failure = new AgentRegistryException(
                    IO_FAILURE, "Could not open the agent registry", e);
            releaseAfterOpenFailure(acquired, failure);
            throw failure;
        }
    }

    public AgentRecord register(AgentLabel agentLabel, String displayName) throws AgentRegistryException {
        AgentRecord candidate = new AgentRecord(
                agentLabel, displayName, Optional.empty(), Optional.empty(), Optional.empty());
        lock.lock();
        try {
            requireOpen();
            AgentRecord existing = records.get(agentLabel);
            if (existing != null) {
                if (!existing.displayName().equals(displayName)) {
                    throw new AgentRegistryException(
                            CONFLICT, "Agent label is already registered with different metadata");
                }
                return existing;
            }
            return publish(candidate);
        } finally {
            lock.unlock();
        }
    }

    public Optional<AgentRecord> find(AgentLabel agentLabel) throws AgentRegistryException {
        Objects.requireNonNull(agentLabel, "agentLabel");
        lock.lock();
        try {
            requireOpen();
            return Optional.ofNullable(records.get(agentLabel));
        } finally {
            lock.unlock();
        }
    }

    public AgentRecord allocateLaunch(AgentLabel agentLabel, Optional<AgentInstanceId> expectedInstance)
            throws AgentRegistryException {
        Objects.requireNonNull(agentLabel, "agentLabel");
        Objects.requireNonNull(expectedInstance, "expectedInstance");
        lock.lock();
        try {
            AgentRecord current = requireRecord(agentLabel);
            if (!current.registration().map(AgentRecord.Registration::instanceId).equals(expectedInstance)) {
                throw new AgentRegistryException(CONFLICT, "Launch authorization does not match current registration");
            }
            long generation = current.launch().map(launch -> launch.generation().value()).orElse(0L);
            if (generation == Long.MAX_VALUE) {
                throw new AgentRegistryException(INVALID_STATE, "Agent launch generation is exhausted");
            }
            AgentRecord.Launch launch = new AgentRecord.Launch(
                    new AgentGeneration(generation + 1), new AgentLaunchId(UUID.randomUUID()),
                    AgentRecord.LaunchState.RECOVERING, Optional.empty());
            return publish(new AgentRecord(
                    agentLabel, current.displayName(), Optional.of(launch), current.observation(), current.registration()));
        } finally {
            lock.unlock();
        }
    }

    public AgentRecord installLaunchPermit(
            AgentLabel agentLabel,
            AgentGeneration expectedGeneration,
            AgentLaunchId expectedLaunchId,
            AgentRecord.Credential permit,
            Instant now) throws AgentRegistryException {
        Objects.requireNonNull(agentLabel, "agentLabel");
        Objects.requireNonNull(expectedGeneration, "expectedGeneration");
        Objects.requireNonNull(expectedLaunchId, "expectedLaunchId");
        Objects.requireNonNull(permit, "permit");
        Objects.requireNonNull(now, "now");
        lock.lock();
        try {
            AgentRecord current = requireRecord(agentLabel);
            AgentRecord.Launch launch = requireLaunch(current, expectedGeneration, expectedLaunchId);
            if (launch.state() != AgentRecord.LaunchState.RECOVERING
                    || launch.launchPermit().isPresent()) {
                throw new AgentRegistryException(INVALID_STATE, "Agent launch is not awaiting a permit");
            }
            if (!permit.expiresAt().isAfter(now)) {
                throw new AgentRegistryException(
                        INVALID_STATE, "Launch permit must expire after the current time");
            }
            AgentRecord.Launch starting = new AgentRecord.Launch(
                    launch.generation(), launch.launchId(), AgentRecord.LaunchState.STARTING,
                    Optional.of(permit));
            return publish(new AgentRecord(
                    agentLabel, current.displayName(), Optional.of(starting), current.observation(), current.registration()));
        } finally {
            lock.unlock();
        }
    }

    public AgentRecord consumeLaunchPermit(
            AgentLabel agentLabel,
            AgentGeneration expectedGeneration,
            AgentLaunchId expectedLaunchId,
            AgentInstanceId instanceId,
            AgentRecord.CredentialDigest expectedPermit,
            AgentRecord.Credential reconnectToken,
            Instant now) throws AgentRegistryException {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(expectedPermit, "expectedPermit");
        Objects.requireNonNull(reconnectToken, "reconnectToken");
        Objects.requireNonNull(now, "now");
        authority.writeLock().lock();
        lock.lock();
        try {
            AgentRecord current = requireRecord(agentLabel);
            AgentRecord.Launch launch = requireLaunch(current, expectedGeneration, expectedLaunchId);
            if (launch.state() != AgentRecord.LaunchState.STARTING) {
                throw new AgentRegistryException(INVALID_STATE, "Agent launch is not awaiting authentication");
            }
            requireCredential(launch.launchPermit(), expectedPermit, now);
            if (!reconnectToken.expiresAt().isAfter(now)) {
                throw new AgentRegistryException(INVALID_STATE, "Reconnect token must expire after the current time");
            }
            if (current.registration().map(value -> value.instanceId().equals(instanceId)).orElse(false)) {
                throw new AgentRegistryException(CONFLICT, "Replacement requires a fresh agent instance");
            }
            AgentRecord.Launch consumed = new AgentRecord.Launch(
                    launch.generation(), launch.launchId(), launch.state(), Optional.empty());
            AgentRecord.Registration registration = new AgentRecord.Registration(
                    launch.generation(), launch.launchId(), instanceId, reconnectToken);
            return publish(new AgentRecord(current.agentLabel(), current.displayName(),
                    Optional.of(consumed), current.observation(), Optional.of(registration)));
        } finally {
            lock.unlock();
            authority.writeLock().unlock();
        }
    }

    public AgentRecord verifyReconnectToken(
            AgentLabel agentLabel,
            AgentGeneration expectedGeneration,
            AgentLaunchId expectedLaunchId,
            AgentInstanceId instanceId,
            AgentRecord.CredentialDigest expectedToken,
            Instant now) throws AgentRegistryException {
        Objects.requireNonNull(expectedToken, "expectedToken");
        Objects.requireNonNull(now, "now");
        lock.lock();
        try {
            AgentRecord current = requireRecord(agentLabel);
            AgentRecord.Registration registration = requireRegistration(
                    current, expectedGeneration, expectedLaunchId, instanceId);
            requireCredential(Optional.of(registration.reconnectToken()), expectedToken, now);
            return current;
        } finally {
            lock.unlock();
        }
    }

    public AgentRecord renewReconnectToken(
            AgentLabel agentLabel,
            AgentGeneration expectedGeneration,
            AgentLaunchId expectedLaunchId,
            AgentInstanceId instanceId,
            AgentRecord.CredentialDigest expectedToken,
            Instant expiresAt,
            Instant now) throws AgentRegistryException {
        Objects.requireNonNull(expectedToken, "expectedToken");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(now, "now");
        lock.lock();
        try {
            AgentRecord current = requireRecord(agentLabel);
            AgentRecord.Registration registration = requireRegistration(
                    current, expectedGeneration, expectedLaunchId, instanceId);
            AgentRecord.Credential token = requireCredential(
                    Optional.of(registration.reconnectToken()), expectedToken, now);
            if (!expiresAt.isAfter(now)) {
                throw new AgentRegistryException(INVALID_STATE, "Reconnect token must expire after the current time");
            }
            if (!expiresAt.isAfter(token.expiresAt())) {
                return current;
            }
            AgentRecord.Registration renewed = new AgentRecord.Registration(
                    registration.generation(), registration.launchId(), registration.instanceId(),
                    new AgentRecord.Credential(token.digest(), expiresAt));
            return publish(new AgentRecord(current.agentLabel(), current.displayName(), current.launch(),
                    current.observation(), Optional.of(renewed)));
        } finally {
            lock.unlock();
        }
    }

    public AgentRecord recordObservation(
            AgentLabel agentLabel,
            AgentRecord.Observation observation) throws AgentRegistryException {
        Objects.requireNonNull(agentLabel, "agentLabel");
        Objects.requireNonNull(observation, "observation");
        lock.lock();
        try {
            AgentRecord current = requireRecord(agentLabel);
            requireRegistration(current, observation.generation(), observation.launchId(), observation.instanceId());
            return publish(new AgentRecord(
                    current.agentLabel(), current.displayName(), current.launch(), Optional.of(observation), current.registration()));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() throws AgentRegistryException {
        authority.writeLock().lock();
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            records.clear();
            try {
                rootOwnership.close();
            } catch (IOException e) {
                throw new AgentRegistryException(
                        IO_FAILURE, "Could not release the agent registry root", e);
            }
        } finally {
            lock.unlock();
            authority.writeLock().unlock();
        }
    }

    private void recover() throws IOException, AgentRegistryException {
        for (Path path : operations.recordFiles(root)) {
            AgentRecord record;
            try {
                record = codec.decode(operations.readRecord(path));
            } catch (AgentRegistryFileOperations.StoredRecordException e) {
                throw new AgentRegistryException(
                        STORED_CORRUPTION,
                        "Could not recover agent record " + path.getFileName(),
                        e);
            } catch (AgentRecordCodec.FormatException e) {
                throw new AgentRegistryException(
                        STORED_CORRUPTION,
                        "Could not recover agent record " + path.getFileName(),
                        e);
            }
            if (!path.getFileName().toString().equals(AgentRecordCodec.fileName(record.agentLabel()))) {
                throw new AgentRegistryException(
                        STORED_CORRUPTION, "Agent record identity does not match its file name");
            }
            if (records.put(record.agentLabel(), record) != null) {
                throw new AgentRegistryException(
                        STORED_CORRUPTION, "Agent registry contains a duplicate agent record");
            }
            operations.establishDurability(path, root);
        }
    }

    private Path recordPath(AgentLabel agentLabel) {
        return root.resolve(AgentRecordCodec.fileName(agentLabel));
    }

    private AgentRecord requireRecord(AgentLabel agentLabel) throws AgentRegistryException {
        Objects.requireNonNull(agentLabel, "agentLabel");
        requireOpen();
        AgentRecord record = records.get(agentLabel);
        if (record == null) {
            throw new AgentRegistryException(NOT_FOUND, "Agent is not registered");
        }
        return record;
    }

    private static AgentRecord.Launch requireLaunch(
            AgentRecord current, AgentGeneration expectedGeneration, AgentLaunchId expectedLaunchId)
            throws AgentRegistryException {
        Objects.requireNonNull(expectedGeneration, "expectedGeneration");
        Objects.requireNonNull(expectedLaunchId, "expectedLaunchId");
        AgentRecord.Launch launch = current.launch().orElseThrow(
                () -> new AgentRegistryException(INVALID_STATE, "Agent has no current launch"));
        if (!launch.generation().equals(expectedGeneration) || !launch.launchId().equals(expectedLaunchId)) {
            throw new AgentRegistryException(CONFLICT, "Agent launch has been superseded");
        }
        return launch;
    }

    private static AgentRecord.Credential requireCredential(
            Optional<AgentRecord.Credential> credential, AgentRecord.CredentialDigest expectedDigest, Instant now)
            throws AgentRegistryException {
        AgentRecord.Credential current = credential.orElseThrow(
                () -> new AgentRegistryException(INVALID_STATE, "Agent launch has no current credential"));
        if (!MessageDigest.isEqual(current.digest().bytes(), expectedDigest.bytes())) {
            throw new AgentRegistryException(CONFLICT, "Agent credential does not match");
        }
        if (!current.expiresAt().isAfter(now)) {
            throw new AgentRegistryException(INVALID_STATE, "Agent credential has expired");
        }
        return current;
    }

    private static AgentRecord.Registration requireRegistration(
            AgentRecord current, AgentGeneration generation, AgentLaunchId launchId, AgentInstanceId instanceId)
            throws AgentRegistryException {
        AgentRecord.Registration registration = current.registration().orElseThrow(
                () -> new AgentRegistryException(INVALID_STATE, "Agent has no registered instance"));
        if (!registration.generation().equals(generation) || !registration.launchId().equals(launchId)
                || !registration.instanceId().equals(instanceId)) {
            throw new AgentRegistryException(CONFLICT, "Agent instance has been superseded");
        }
        return registration;
    }

    public RegistrationLease acquireRegistration(
            AgentLabel label, AgentGeneration generation, AgentLaunchId launchId, AgentInstanceId instanceId)
            throws AgentRegistryException {
        RegistrationLease lease = acquireAuthority();
        try {
            lock.lock();
            try {
                requireRegistration(requireRecord(label), generation, launchId, instanceId);
            } finally {
                lock.unlock();
            }
            return lease;
        } catch (AgentRegistryException | RuntimeException failure) {
            lease.close();
            throw failure;
        }
    }

    public RegistrationLease acquireAuthority() {
        authority.readLock().lock();
        return authority.readLock()::unlock;
    }

    @FunctionalInterface
    public interface RegistrationLease extends AutoCloseable {
        @Override
        void close();
    }

    private AgentRecord publish(AgentRecord candidate) throws AgentRegistryException {
        try {
            operations.publish(root, recordPath(candidate.agentLabel()), codec.encode(candidate));
        } catch (AgentRegistryFileOperations.PublicationException e) {
            if (e.indeterminate()) {
                indeterminate = true;
                throw new AgentRegistryException(
                        INDETERMINATE,
                        "Agent record may have been published without directory durability",
                        e);
            }
            throw new AgentRegistryException(IO_FAILURE, "Could not persist the agent record", e);
        } catch (IOException e) {
            throw new AgentRegistryException(IO_FAILURE, "Could not persist the agent record", e);
        }
        records.put(candidate.agentLabel(), candidate);
        return candidate;
    }

    private void requireOpen() throws AgentRegistryException {
        if (closed) {
            throw new AgentRegistryException(CLOSED, "Agent registry is closed");
        }
        if (indeterminate) {
            throw new AgentRegistryException(
                    INDETERMINATE, "Agent registry has an indeterminate durable state");
        }
    }

    private static void releaseAfterOpenFailure(
            AgentRegistryFileOperations.RootOwnership ownership,
            AgentRegistryException failure) {
        if (ownership == null) {
            return;
        }
        try {
            ownership.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }
}
