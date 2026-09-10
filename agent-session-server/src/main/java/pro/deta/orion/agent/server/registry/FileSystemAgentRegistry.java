package pro.deta.orion.agent.server.registry;

import pro.deta.orion.agent.protocol.AgentGeneration;
import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.AgentLaunchId;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

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
 */
public final class FileSystemAgentRegistry implements AutoCloseable {
    private final Path root;
    private final AgentRecordCodec codec = new AgentRecordCodec();
    private final AgentRegistryFileOperations operations;
    private final AgentRegistryFileOperations.RootOwnership rootOwnership;
    private final Map<AgentId, AgentRecord> records = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
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

    public AgentRecord register(AgentId agentId, String displayName) throws AgentRegistryException {
        AgentRecord candidate = new AgentRecord(
                agentId, displayName, Optional.empty(), Optional.empty());
        lock.lock();
        try {
            requireOpen();
            AgentRecord existing = records.get(agentId);
            if (existing != null) {
                if (!existing.displayName().equals(displayName)) {
                    throw new AgentRegistryException(
                            CONFLICT, "Agent ID is already registered with different metadata");
                }
                return existing;
            }
            return publish(candidate);
        } finally {
            lock.unlock();
        }
    }

    public Optional<AgentRecord> find(AgentId agentId) throws AgentRegistryException {
        Objects.requireNonNull(agentId, "agentId");
        lock.lock();
        try {
            requireOpen();
            return Optional.ofNullable(records.get(agentId));
        } finally {
            lock.unlock();
        }
    }

    public AgentRecord allocateLaunch(AgentId agentId) throws AgentRegistryException {
        Objects.requireNonNull(agentId, "agentId");
        lock.lock();
        try {
            AgentRecord current = requireRecord(agentId);
            long generation = current.launch().map(launch -> launch.generation().value()).orElse(0L);
            if (generation == Long.MAX_VALUE) {
                throw new AgentRegistryException(INVALID_STATE, "Agent launch generation is exhausted");
            }
            AgentRecord.Launch launch = new AgentRecord.Launch(
                    new AgentGeneration(generation + 1), new AgentLaunchId(UUID.randomUUID()),
                    AgentRecord.LaunchState.RECOVERING, Optional.empty(), Optional.empty());
            return publish(new AgentRecord(
                    agentId, current.displayName(), Optional.of(launch), current.observation()));
        } finally {
            lock.unlock();
        }
    }

    public AgentRecord installLaunchPermit(
            AgentId agentId,
            AgentGeneration expectedGeneration,
            AgentLaunchId expectedLaunchId,
            AgentRecord.Credential permit,
            Instant now) throws AgentRegistryException {
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(expectedGeneration, "expectedGeneration");
        Objects.requireNonNull(expectedLaunchId, "expectedLaunchId");
        Objects.requireNonNull(permit, "permit");
        Objects.requireNonNull(now, "now");
        lock.lock();
        try {
            AgentRecord current = requireRecord(agentId);
            AgentRecord.Launch launch = current.launch().orElseThrow(
                    () -> new AgentRegistryException(INVALID_STATE, "Agent has no current launch"));
            if (!launch.generation().equals(expectedGeneration)
                    || !launch.launchId().equals(expectedLaunchId)) {
                throw new AgentRegistryException(CONFLICT, "Agent launch has been superseded");
            }
            if (launch.state() != AgentRecord.LaunchState.RECOVERING
                    || launch.launchPermit().isPresent() || launch.reconnectToken().isPresent()) {
                throw new AgentRegistryException(INVALID_STATE, "Agent launch is not awaiting a permit");
            }
            if (!permit.expiresAt().isAfter(now)) {
                throw new AgentRegistryException(
                        INVALID_STATE, "Launch permit must expire after the current time");
            }
            AgentRecord.Launch starting = new AgentRecord.Launch(
                    launch.generation(), launch.launchId(), AgentRecord.LaunchState.STARTING,
                    Optional.of(permit), Optional.empty());
            return publish(new AgentRecord(
                    agentId, current.displayName(), Optional.of(starting), current.observation()));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() throws AgentRegistryException {
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
            if (!path.getFileName().toString().equals(AgentRecordCodec.fileName(record.agentId()))) {
                throw new AgentRegistryException(
                        STORED_CORRUPTION, "Agent record identity does not match its file name");
            }
            if (records.put(record.agentId(), record) != null) {
                throw new AgentRegistryException(
                        STORED_CORRUPTION, "Agent registry contains a duplicate agent record");
            }
            operations.establishDurability(path, root);
        }
    }

    private Path recordPath(AgentId agentId) {
        return root.resolve(AgentRecordCodec.fileName(agentId));
    }

    private AgentRecord requireRecord(AgentId agentId) throws AgentRegistryException {
        requireOpen();
        AgentRecord record = records.get(agentId);
        if (record == null) {
            throw new AgentRegistryException(NOT_FOUND, "Agent is not registered");
        }
        return record;
    }

    private AgentRecord publish(AgentRecord candidate) throws AgentRegistryException {
        try {
            operations.publish(root, recordPath(candidate.agentId()), codec.encode(candidate));
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
        records.put(candidate.agentId(), candidate);
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
