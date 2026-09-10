package pro.deta.orion.agent.server.registry;

import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import static pro.deta.orion.agent.server.registry.AgentRegistryException.Reason.CLOSED;
import static pro.deta.orion.agent.server.registry.AgentRegistryException.Reason.CONFLICT;
import static pro.deta.orion.agent.server.registry.AgentRegistryException.Reason.INDETERMINATE;
import static pro.deta.orion.agent.server.registry.AgentRegistryException.Reason.IO_FAILURE;
import static pro.deta.orion.agent.server.registry.AgentRegistryException.Reason.STORED_CORRUPTION;

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
            try {
                operations.publishNew(root, recordPath(agentId), codec.encode(candidate));
            } catch (AgentRegistryFileOperations.PublicationException e) {
                if (e.indeterminate()) {
                    indeterminate = true;
                    throw new AgentRegistryException(
                            INDETERMINATE,
                            "Agent registration may have been published without directory durability",
                            e);
                }
                throw new AgentRegistryException(IO_FAILURE, "Could not register the agent", e);
            } catch (IOException e) {
                throw new AgentRegistryException(IO_FAILURE, "Could not register the agent", e);
            }
            records.put(agentId, candidate);
            return candidate;
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
