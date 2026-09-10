package pro.deta.orion.agent.server.registry;

import pro.deta.orion.agent.protocol.AgentId;
import pro.deta.orion.agent.protocol.SessionDescriptor;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Durable owner of session identity, reported metadata, and authoritative process outcomes. */
public final class FileSystemSessionRegistry implements AutoCloseable {
    private final SessionRecordCodec codec = new SessionRecordCodec();
    private final SessionRegistryFileOperations operations;
    private final Path root;
    private final Map<SessionId, SessionRecord> records = new HashMap<>();
    private final SessionRegistryFileOperations.RootOwnership ownership;
    private boolean closed;
    private boolean indeterminate;

    public FileSystemSessionRegistry(Path root) throws SessionRegistryException {
        this(root, new SessionRegistryFileOperations());
    }

    @TestOnly
    static FileSystemSessionRegistry withOperations(
            Path root,
            SessionRegistryFileOperations operations) throws SessionRegistryException {
        return new FileSystemSessionRegistry(root, operations);
    }

    private FileSystemSessionRegistry(Path requestedRoot, SessionRegistryFileOperations operations)
            throws SessionRegistryException {
        Objects.requireNonNull(requestedRoot, "root");
        this.operations = Objects.requireNonNull(operations, "operations");
        Path prepared = null;
        SessionRegistryFileOperations.RootOwnership acquired = null;
        try {
            prepared = operations.prepareRoot(requestedRoot.toAbsolutePath().normalize());
            acquired = operations.acquireRoot(prepared);
            operations.recoverTransactions(prepared);
            load(prepared);
        } catch (SessionRegistryFileOperations.RootInUseException failure) {
            throw new SessionRegistryException(
                    SessionRegistryException.Reason.CONFLICT,
                    "Session registry root is already owned",
                    failure);
        } catch (SessionRegistryFileOperations.StoredRecordException failure) {
            closeAfterFailedOpen(acquired, failure);
            throw new SessionRegistryException(
                    SessionRegistryException.Reason.STORED_CORRUPTION,
                    "Could not open session registry: " + failure.getMessage(),
                    failure);
        } catch (IOException failure) {
            closeAfterFailedOpen(acquired, failure);
            throw new SessionRegistryException(
                    SessionRegistryException.Reason.IO_FAILURE,
                    "Could not open session registry",
                    failure);
        }
        root = prepared;
        ownership = acquired;
    }

    private void load(Path preparedRoot) throws IOException {
        for (Path path : operations.recordFiles(preparedRoot)) {
            SessionRecord record = codec.decode(operations.readRecord(path));
            SessionId sessionId = record.reported().sessionId();
            if (!path.getFileName().toString().equals(SessionRecordCodec.fileName(sessionId))) {
                throw new SessionRegistryFileOperations.StoredRecordException(
                        "Session record file name does not match its session ID",
                        null);
            }
            if (records.put(sessionId, record) != null) {
                throw new SessionRegistryFileOperations.StoredRecordException(
                        "Session registry contains duplicate session identity",
                        null);
            }
        }
    }

    public synchronized void reconcile(AgentId agentId, List<SessionDescriptor> sessions)
            throws SessionRegistryException {
        requireOpen();
        Objects.requireNonNull(agentId, "agentId");
        List<SessionDescriptor> snapshot = List.copyOf(sessions);
        Map<SessionId, SessionDescriptor> reported = new LinkedHashMap<>();
        for (SessionDescriptor descriptor : snapshot) {
            Objects.requireNonNull(descriptor, "session");
            if (reported.put(descriptor.sessionId(), descriptor) != null) {
                throw conflict("Session report contains a duplicate session ID");
            }
            SessionRecord current = records.get(descriptor.sessionId());
            if (current != null && !current.agentId().equals(agentId)) {
                throw conflict("Session belongs to another agent");
            }
        }

        Map<SessionId, SessionRecord> replacements = new LinkedHashMap<>();
        for (SessionDescriptor descriptor : reported.values()) {
            SessionRecord current = records.get(descriptor.sessionId());
            SessionRecord replacement = new SessionRecord(
                    agentId,
                    descriptor,
                    current == null ? Optional.empty() : current.outcome());
            if (!replacement.equals(current)) {
                replacements.put(descriptor.sessionId(), replacement);
            }
        }
        publish(replacements);
    }

    public synchronized void recordOutcome(
            AgentId agentId,
            SessionId sessionId,
            SessionRecord.Outcome outcome) throws SessionRegistryException {
        requireOpen();
        Objects.requireNonNull(agentId, "agentId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(outcome, "outcome");
        SessionRecord current = records.get(sessionId);
        if (current == null) {
            throw new SessionRegistryException(
                    SessionRegistryException.Reason.NOT_FOUND,
                    "Session is not registered");
        }
        if (!current.agentId().equals(agentId)) {
            throw conflict("Session belongs to another agent");
        }
        SessionRecord replacement = new SessionRecord(
                agentId,
                current.reported(),
                Optional.of(outcome));
        if (!replacement.equals(current)) {
            publish(Map.of(sessionId, replacement));
        }
    }

    public synchronized Optional<SessionRecord> find(SessionId sessionId)
            throws SessionRegistryException {
        requireOpen();
        return Optional.ofNullable(records.get(Objects.requireNonNull(sessionId, "sessionId")));
    }

    public synchronized List<SessionRecord> ownedBy(AgentId agentId)
            throws SessionRegistryException {
        requireOpen();
        Objects.requireNonNull(agentId, "agentId");
        List<SessionRecord> owned = new ArrayList<>();
        for (SessionRecord record : records.values()) {
            if (record.agentId().equals(agentId)) {
                owned.add(record);
            }
        }
        owned.sort(Comparator.comparing(record -> record.reported().sessionId().value()));
        return List.copyOf(owned);
    }

    public synchronized boolean owns(AgentId agentId, SessionId sessionId)
            throws SessionRegistryException {
        requireOpen();
        Objects.requireNonNull(agentId, "agentId");
        SessionRecord record = records.get(Objects.requireNonNull(sessionId, "sessionId"));
        return record != null && record.agentId().equals(agentId);
    }

    private void publish(Map<SessionId, SessionRecord> replacements)
            throws SessionRegistryException {
        if (replacements.isEmpty()) {
            return;
        }
        Map<Path, byte[]> encoded = new LinkedHashMap<>();
        try {
            for (Map.Entry<SessionId, SessionRecord> entry : replacements.entrySet()) {
                Path target = root.resolve(SessionRecordCodec.fileName(entry.getKey()));
                encoded.put(target, codec.encode(entry.getValue()));
            }
            operations.publishBatch(root, encoded);
            records.putAll(replacements);
        } catch (SessionRegistryFileOperations.PublicationException failure) {
            if (failure.indeterminate()) {
                indeterminate = true;
            }
            throw new SessionRegistryException(
                    failure.indeterminate()
                            ? SessionRegistryException.Reason.INDETERMINATE
                            : SessionRegistryException.Reason.IO_FAILURE,
                    "Could not publish session records",
                    failure);
        } catch (IOException failure) {
            throw new SessionRegistryException(
                    SessionRegistryException.Reason.IO_FAILURE,
                    "Could not encode session records",
                    failure);
        }
    }

    private SessionRegistryException conflict(String message) {
        return new SessionRegistryException(SessionRegistryException.Reason.CONFLICT, message);
    }

    private void requireOpen() throws SessionRegistryException {
        if (closed) {
            throw new SessionRegistryException(
                    SessionRegistryException.Reason.CLOSED,
                    "Session registry is closed");
        }
        if (indeterminate) {
            throw new SessionRegistryException(
                    SessionRegistryException.Reason.INDETERMINATE,
                    "Session registry publication outcome is indeterminate; reopen it to recover");
        }
    }

    @Override
    public synchronized void close() throws SessionRegistryException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            ownership.close();
        } catch (IOException failure) {
            throw new SessionRegistryException(
                    SessionRegistryException.Reason.IO_FAILURE,
                    "Could not release session registry ownership",
                    failure);
        }
    }

    private static void closeAfterFailedOpen(
            SessionRegistryFileOperations.RootOwnership ownership,
            Exception failure) {
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
