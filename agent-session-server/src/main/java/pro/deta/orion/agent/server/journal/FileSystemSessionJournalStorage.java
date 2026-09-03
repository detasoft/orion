package pro.deta.orion.agent.server.journal;

import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.SessionEventRecord;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static pro.deta.orion.agent.server.journal.JournalStorageException.Reason.CLOSED;
import static pro.deta.orion.agent.server.journal.JournalStorageException.Reason.IO_FAILURE;

/**
 * @AiRule A live storage instance exclusively owns its canonical, case-folded journal root. One exact
 * {@code SessionId} spelling owns each case-folded session namespace, including a namespace already present on
 * disk. Cooperating aliases are rejected until the owner closes. Out-of-band filesystem mutation, including a
 * process that ignores this advisory in-process lease and restores metadata, is outside the supported storage
 * contract.
 */
public final class FileSystemSessionJournalStorage implements SessionJournalStorage {
    private static final Object ROOT_OWNER_MONITOR = new Object();
    private static final Map<RootLeaseKey, FileSystemSessionJournalStorage> ROOT_OWNERS =
            new HashMap<>();

    private final Path root;
    private final JournalStorageConfig config;
    private final DurableFileOperations operations;
    private final JournalMaintenance maintenance;
    private final ConcurrentMap<String, SessionBinding> journals = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock(true);
    private boolean closed;
    private RootLeaseKey rootLeaseKey;

    public FileSystemSessionJournalStorage(Path root, JournalStorageConfig config) {
        this(root, config, new DurableFileOperations(), JournalMaintenance.background());
    }

    FileSystemSessionJournalStorage(
            Path root,
            JournalStorageConfig config,
            DurableFileOperations operations) {
        this(root, config, operations, JournalMaintenance.disabled());
    }

    @TestOnly
    static FileSystemSessionJournalStorage withMaintenanceExecutor(
            Path root,
            JournalStorageConfig config,
            DurableFileOperations operations,
            Executor maintenanceExecutor) {
        return new FileSystemSessionJournalStorage(
                root,
                config,
                operations,
                JournalMaintenance.using(maintenanceExecutor, 64));
    }

    @TestOnly
    static FileSystemSessionJournalStorage withMaintenanceExecutor(
            Path root,
            JournalStorageConfig config,
            DurableFileOperations operations,
            Executor maintenanceExecutor,
            int maxPendingSessions) {
        return new FileSystemSessionJournalStorage(
                root,
                config,
                operations,
                JournalMaintenance.using(maintenanceExecutor, maxPendingSessions));
    }

    private FileSystemSessionJournalStorage(
            Path root,
            JournalStorageConfig config,
            DurableFileOperations operations,
            JournalMaintenance maintenance) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.config = Objects.requireNonNull(config, "config");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
    }

    @Override
    public Optional<EventId> firstEventId(SessionId sessionId) throws JournalStorageException {
        return execute(sessionId, SessionJournal::firstEventId);
    }

    @Override
    public Optional<EventId> lastEventId(SessionId sessionId) throws JournalStorageException {
        return execute(sessionId, SessionJournal::lastEventId);
    }

    @Override
    public JournalAppendResult append(SessionId sessionId, List<SessionEventRecord> records)
            throws JournalStorageException {
        return execute(sessionId, journal -> journal.append(records));
    }

    @Override
    public JournalReadResult readAfter(SessionId sessionId, Optional<EventId> after)
            throws JournalStorageException {
        return execute(sessionId, journal -> journal.readAfter(Objects.requireNonNull(after, "after")));
    }

    @Override
    public void close() {
        Lock gate = lifecycle.writeLock();
        gate.lock();
        try {
            closed = true;
            maintenance.close();
            journals.clear();
            releaseRootLease();
        } finally {
            gate.unlock();
        }
    }

    private <T> T execute(SessionId sessionId, JournalOperation<T> operation)
            throws JournalStorageException {
        Lock gate = lifecycle.readLock();
        gate.lock();
        try {
            Objects.requireNonNull(sessionId, "sessionId");
            if (closed) {
                throw new JournalStorageException(CLOSED, "Journal storage is closed");
            }
            acquireRootLease();
            SessionJournal journal = resolveJournal(sessionId);
            return operation.run(journal);
        } finally {
            gate.unlock();
        }
    }

    private SessionJournal resolveJournal(SessionId sessionId) throws JournalStorageException {
        String key = caseFold(sessionId.value());
        SessionBinding existing = journals.get(key);
        if (existing != null) {
            return requireExactSession(sessionId, existing);
        }
        synchronized (journals) {
            existing = journals.get(key);
            if (existing != null) {
                return requireExactSession(sessionId, existing);
            }
            rejectExistingSessionAlias(sessionId, key);
            SessionJournal journal = new SessionJournal(
                    root.resolve(sessionId.value()), config, operations, maintenance);
            journals.put(key, new SessionBinding(sessionId, journal));
            return journal;
        }
    }

    private static SessionJournal requireExactSession(
            SessionId requested,
            SessionBinding existing) throws JournalStorageException {
        if (!existing.sessionId().equals(requested)) {
            throw sessionAliasFailure();
        }
        return existing.journal();
    }

    private void rejectExistingSessionAlias(SessionId sessionId, String key)
            throws JournalStorageException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(root)) {
            for (Path entry : entries) {
                String spelling = entry.getFileName().toString();
                if (caseFold(spelling).equals(key) && !spelling.equals(sessionId.value())) {
                    throw sessionAliasFailure();
                }
            }
        } catch (NoSuchFileException e) {
            return;
        } catch (DirectoryIteratorException e) {
            throw new JournalStorageException(
                    IO_FAILURE, "Could not validate the session journal namespace", e.getCause());
        } catch (IOException e) {
            throw new JournalStorageException(
                    IO_FAILURE, "Could not validate the session journal namespace", e);
        }
    }

    private void acquireRootLease() throws JournalStorageException {
        synchronized (ROOT_OWNER_MONITOR) {
            if (rootLeaseKey != null) {
                return;
            }
            RootLeaseKey key;
            try {
                key = resolveRootLeaseKey(root);
            } catch (IOException e) {
                throw new JournalStorageException(
                        IO_FAILURE, "Could not resolve the session journal root", e);
            }
            FileSystemSessionJournalStorage owner = ROOT_OWNERS.get(key);
            if (owner != null && owner != this) {
                throw new JournalStorageException(
                        IO_FAILURE, "The session journal root is already owned by another live storage");
            }
            ROOT_OWNERS.put(key, this);
            rootLeaseKey = key;
        }
    }

    private void releaseRootLease() {
        synchronized (ROOT_OWNER_MONITOR) {
            if (rootLeaseKey == null) {
                return;
            }
            ROOT_OWNERS.remove(rootLeaseKey, this);
            rootLeaseKey = null;
        }
    }

    private static RootLeaseKey resolveRootLeaseKey(Path root) throws IOException {
        List<String> unresolved = new ArrayList<>();
        Path ancestor = root;
        Path canonical;
        while (true) {
            try {
                canonical = ancestor.toRealPath();
                break;
            } catch (NoSuchFileException e) {
                Path name = ancestor.getFileName();
                Path parent = ancestor.getParent();
                if (name == null || parent == null) {
                    throw new IOException("Could not find an existing journal root ancestor", e);
                }
                unresolved.add(name.toString());
                ancestor = parent;
            }
        }
        Collections.reverse(unresolved);
        for (String name : unresolved) {
            canonical = canonical.resolve(name);
        }
        return new RootLeaseKey(
                canonical.getFileSystem(),
                caseFold(canonical.toAbsolutePath().normalize().toString()));
    }

    private static String caseFold(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static JournalStorageException sessionAliasFailure() {
        return new JournalStorageException(
                IO_FAILURE, "Session ID conflicts with a case-insensitive journal namespace");
    }

    @TestOnly
    Optional<Throwable> maintenanceFailureForTest() {
        return maintenance.failure();
    }

    @TestOnly
    void awaitMaintenanceForTest() throws InterruptedException {
        maintenance.awaitQuiescence();
    }

    @FunctionalInterface
    private interface JournalOperation<T> {
        T run(SessionJournal journal) throws JournalStorageException;
    }

    private record SessionBinding(SessionId sessionId, SessionJournal journal) {
        private SessionBinding {
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(journal, "journal");
        }
    }

    private record RootLeaseKey(FileSystem fileSystem, String foldedPath) {
        private RootLeaseKey {
            Objects.requireNonNull(fileSystem, "fileSystem");
            Objects.requireNonNull(foldedPath, "foldedPath");
        }
    }
}
