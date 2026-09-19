package pro.deta.orion.git.parser.v2.storage;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVStoreException;
import org.h2.mvstore.type.StringDataType;
import pro.deta.orion.git.parser.v2.data.Head;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.data.RefsSnapshot;
import pro.deta.orion.git.parser.v2.id.CommitId;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.read.ExistsGitObjectRead;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static pro.deta.orion.git.parser.v2.data.RefUpdateResult.Status.*;

final class GitRefsStorage {
    private static final RefId HEAD = new RefId("HEAD");
    private static final String SYMBOLIC = "ref: ";
    private final Path path;
    private final GitLock lock;
    private final GitObjectStorage objects;

    GitRefsStorage(Path repository, GitObjectStorage objects) throws IOException {
        Path root = repository.toRealPath();
        path = root.resolve("refs.mv");
        lock = new GitLock(root);
        this.objects = Objects.requireNonNull(objects, "objects");
        try (GitLock.Lease lease = lockRefs(List.of(HEAD))) {
            boolean create = Files.notExists(path);
            MVStore store = open(false);
            try {
                if (create) {
                    store.setStoreVersion(1);
                    map(store).put(HEAD.value(), SYMBOLIC + "refs/heads/main");
                    store.commit();
                    store.sync();
                    try (FileChannel directory = FileChannel.open(root, StandardOpenOption.READ)) {
                        directory.force(true);
                    }
                } else {
                    readHead(existingMap(store));
                }
            } finally {
                store.closeImmediately();
            }
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

    RefsSnapshot snapshot() throws IOException {
        try (GitLock.Lease lease = lockRefs(List.of(HEAD))) {
            Files.size(path);
            MVStore store = open(true);
            try {
                MVMap<String, String> values = existingMap(store);
                Head head = readHead(values);
                Map<RefId, ObjectId> refs = new LinkedHashMap<>();
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    if (!entry.getKey().equals(HEAD.value())) {
                        RefId ref = new RefId(entry.getKey());
                        requireRef(ref);
                        refs.put(ref, new ObjectId(entry.getValue()));
                    }
                }
                return new RefsSnapshot(refs, head);
            } finally {
                store.closeImmediately();
            }
        } catch (MVStoreException | IllegalArgumentException error) {
            throw storageFailure(error);
        }
    }

    void updateHead(Head head) throws IOException {
        Objects.requireNonNull(head, "head");
        String value = switch (head) {
            case Head.Symbolic symbolic -> {
                requireRef(symbolic.target());
                yield SYMBOLIC + symbolic.target().value();
            }
            case Head.Detached detached -> detached.target().toHex();
        };
        try (GitLock.Lease lease = lockRefs(List.of(HEAD))) {
            Files.size(path);
            MVStore store = open(false);
            try {
                MVMap<String, String> refs = existingMap(store);
                readHead(refs);
                if (head instanceof Head.Detached detached
                        && !exists(new ObjectId(detached.target().toBytes()))) {
                    throw new IOException("Detached HEAD object does not exist: " + detached.target());
                }
                refs.put(HEAD.value(), value);
                store.commit();
                store.sync();
            } finally {
                store.closeImmediately();
            }
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

    List<RefUpdateResult> updateAll(List<RefUpdate> updates, boolean atomic) throws IOException {
        if (updates.isEmpty()) {
            return List.of();
        }
        Set<RefId> names = new HashSet<>();
        for (RefUpdate update : updates) {
            requireRef(update.ref());
            if (!names.add(update.ref())) {
                throw new IllegalArgumentException("Duplicate ref update: " + update.ref());
            }
        }
        try (GitLock.Lease lease = lockRefs(names)) {
            Files.size(path);
            MVStore store = open(false);
            try {
                MVMap<String, String> refs = existingMap(store);
                readHead(refs);
                List<RefUpdateResult> results = new ArrayList<>(updates.size());
                boolean failed = false;
                for (RefUpdate update : updates) {
                    String current = refs.get(update.ref().value());
                    RefUpdateResult.Status status;
                    if (!Objects.equals(current, update.expectedOld().map(ObjectId::toHex).orElse(null))) {
                        status = EXPECTED_OLD_MISMATCH;
                    } else if (update.newId().isPresent() && !exists(update.newId().get())) {
                        status = OBJECT_NOT_FOUND;
                    } else {
                        status = APPLIED;
                    }
                    failed |= status != APPLIED;
                    results.add(new RefUpdateResult(update, status, Optional.empty()));
                }
                if (atomic && failed) {
                    for (int index = 0; index < results.size(); index++) {
                        RefUpdateResult result = results.get(index);
                        if (result.status() == APPLIED) {
                            results.set(index, new RefUpdateResult(result.update(), ATOMIC_ABORTED, Optional.empty()));
                        }
                    }
                    return List.copyOf(results);
                }
                for (RefUpdateResult result : results) {
                    if (result.status() == APPLIED) {
                        RefUpdate update = result.update();
                        if (update.newId().isPresent()) {
                            refs.put(update.ref().value(), update.newId().get().toHex());
                        } else {
                            refs.remove(update.ref().value());
                        }
                    }
                }
                store.commit();
                store.sync();
                return List.copyOf(results);
            } finally {
                store.closeImmediately();
            }
        } catch (MVStoreException error) {
            throw storageFailure(error);
        }
    }

    private boolean exists(ObjectId id) throws IOException {
        return objects.read(id, new ExistsGitObjectRead()).isPresent();
    }

    private MVStore open(boolean readOnly) {
        MVStore.Builder builder = new MVStore.Builder().fileName(path.toString()).cacheSize(1)
                .autoCommitDisabled().autoCommitBufferSize(0);
        if (readOnly) {
            builder.readOnly();
        }
        return builder.open();
    }

    private static MVMap<String, String> map(MVStore store) {
        return store.openMap("refs", new MVMap.Builder<String, String>()
                .keyType(StringDataType.INSTANCE).valueType(StringDataType.INSTANCE));
    }

    private static MVMap<String, String> existingMap(MVStore store) throws IOException {
        if (store.getStoreVersion() != 1 || !store.getMapNames().equals(Set.of("refs"))) {
            throw new IOException("Unsupported refs storage format");
        }
        return map(store);
    }

    private static Head readHead(MVMap<String, String> refs) throws IOException {
        String value = refs.get(HEAD.value());
        if (value == null) {
            throw new IOException("Missing HEAD in refs storage");
        }
        try {
            if (value.startsWith(SYMBOLIC)) {
                RefId target = new RefId(value.substring(SYMBOLIC.length()));
                requireRef(target);
                return new Head.Symbolic(target);
            }
            return new Head.Detached(new CommitId(value));
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid HEAD in refs storage", error);
        }
    }

    private static void requireRef(RefId ref) {
        if (!ref.value().startsWith("refs/") || ref.value().length() == "refs/".length()) {
            throw new IllegalArgumentException("Expected a full ref name: " + ref);
        }
    }

    private GitLock.Lease lockRefs(Collection<RefId> refs) throws IOException {
        try {
            return lock.lockRefs(refs);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while accessing refs", error);
        }
    }

    private IOException storageFailure(RuntimeException error) {
        return new IOException("Failed to access refs storage " + path, error);
    }
}
