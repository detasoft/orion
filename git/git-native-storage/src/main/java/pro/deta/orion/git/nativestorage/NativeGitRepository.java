package pro.deta.orion.git.nativestorage;

import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.git.nativestorage.object.LooseObject;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.nativestorage.receive.NativeGitReceivePack;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.pack.GitPackObjectResolver;
import pro.deta.orion.git.parser.v2.pack.IndexedPack;
import pro.deta.orion.git.parser.v2.pack.PackIngestor;
import pro.deta.orion.git.parser.v2.pack.PackWriter;
import pro.deta.orion.git.parser.v2.read.GitObjectGraph;
import pro.deta.orion.git.parser.v2.read.ResolvedGitObjectRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Slf4j
public class NativeGitRepository implements AutoCloseable {
    private final String name;
    private final GitStorageApi storage;
    private final String defaultHead;
    private final CopyOnWriteArrayList<Consumer<RefUpdateResult>> refUpdateListeners = new CopyOnWriteArrayList<>();

    public NativeGitRepository(String name, GitStorageApi storage, String defaultHead) {
        this.name = Objects.requireNonNull(name, "name");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.defaultHead = Objects.requireNonNull(defaultHead, "defaultHead");
    }

    public GitStorageApi storage() {
        return storage;
    }
    public String name() {
        return name;
    }

    public GitRepositoryFileSnapshot loadFiles(
            String branch,
            List<String> paths) throws GitOperationException {
        return new NativeRepositoryFileLoader(this).loadFiles(branch, paths);
    }

    public void saveFiles(
            String branch,
            Map<String, byte[]> files,
            String message,
            GitCommitAuthor author) throws GitOperationException {
        new NativeRepositoryFileSaver(this).saveFiles(branch, files, message, author);
    }

    public NativeGitFileUpdate prepareFileUpdate(
            String branch,
            Map<String, byte[]> files,
            String message,
            GitCommitAuthor author) throws GitOperationException {
        return new NativeRepositoryFileSaver(this).prepareFiles(branch, files, message, author);
    }

    public NativeGitFileUpdate prepareFileUpdate(
            String branch,
            String expectedRefRevision,
            Map<String, byte[]> files,
            String message,
            GitCommitAuthor author) throws GitOperationException {
        return new NativeRepositoryFileSaver(this).prepareFiles(
                branch, expectedRefRevision, files, message, author, true);
    }

    public NativeGitFileUpdate prepareProxyFileUpdate(
            String branch,
            Map<String, byte[]> files,
            String message,
            GitCommitAuthor author) throws GitOperationException {
        return new NativeRepositoryFileSaver(this).prepareFiles(
                branch,
                files,
                message,
                author,
                false);
    }

    public NativeGitFileUpdate prepareProxyFileUpdate(
            String branch,
            String expectedRefRevision,
            Map<String, byte[]> files,
            String message,
            GitCommitAuthor author) throws GitOperationException {
        return new NativeRepositoryFileSaver(this).prepareFiles(
                branch, expectedRefRevision, files, message, author, false);
    }

    public String defaultHead() {
        return defaultHead;
    }

    public Map<String, String> refs() {
        try {
            Map<String, String> refs = new LinkedHashMap<>();
            for (Map.Entry<RefId, ObjectId> ref : storage().snapshotRefs().refs().entrySet()) {
                refs.put(ref.getKey().value(), ref.getValue().toHex());
            }
            return Map.copyOf(refs);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public RefUpdateResult updateRef(String refName, String expectedOldId, String newId) {
        return publishRefs(List.of(RefUpdate.fromWire(refName, expectedOldId, newId)), true).getFirst();
    }

    public RefUpdateSubscription onRefUpdate(Consumer<RefUpdateResult> listener) {
        Consumer<RefUpdateResult> registered = Objects.requireNonNull(listener, "listener");
        refUpdateListeners.add(registered);
        return () -> refUpdateListeners.remove(registered);
    }

    public ObjectId writeObject(GitObjectType type, byte[] data) {
        GitObjectType objectType = type;
        MessageDigest hash = GitHashAlgorithm.SHA1.newDigest();
        hash.update((objectType.name().toLowerCase(Locale.ROOT) + " " + data.length + "\0")
                .getBytes(StandardCharsets.US_ASCII));
        ObjectId id = new ObjectId(hash.digest(data));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BufferedByteInputV2 content = new BufferedByteInputV2(new ByteArrayInputStream(data));
             PackWriter writer = new PackWriter(new OutputStreamBufferedByteOutput(bytes), 1)) {
            writer.writeObject(objectType, data.length, content);
            writer.finish();
            try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(bytes.toByteArray()))) {
                storage().persist(ingest(input));
            }
            return id;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public Optional<LooseObject> readObject(ObjectId id) {
        try {
            return storage().readObject(id, new ResolvedGitObjectRead<>(storage(),
                    (type, size, base, input) -> new LooseObject(id, type,
                            input.readBytes(Math.toIntExact(size)))));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public IndexedPack ingest(BufferedByteInputV2 input) throws IOException {
        try (PackIngestor ingestor = new PackIngestor(input, storage().newPack())) {
            IndexedPack pack = ingestor.ingest();
            try {
                new GitPackObjectResolver(pack, storage()).complete();
                return pack;
            } catch (IOException | RuntimeException | Error failure) {
                try {
                    pack.discard();
                } catch (Throwable cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }
    }

    public List<RefUpdateResult> publishPack(byte[] bytes, List<RefUpdate> updates, boolean atomic,
                                            GitNativeRepositoryAccessHook accessHook) throws GitOperationException {
        accessHook.beforeReceive(name());
        accessHook.beforeWrite(name());
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(bytes))) {
            PackId id = storage().persist(ingest(input));
            return NativeGitReceivePack.complete(name(), this, updates, atomic, accessHook,
                    valid -> publishReceivedPack(Optional.of(id), valid, atomic));
        } catch (IOException failure) {
            throw new GitOperationException("Cannot publish file update pack", failure);
        }
    }

    public List<RefUpdateResult> publishReceivedPack(Optional<PackId> pack, List<RefUpdate> updates, boolean atomic) {
        return publishRefs(updates, atomic);
    }

    public List<RefUpdateResult> publishRefs(List<RefUpdate> updates, boolean atomic) {
        List<RefUpdateResult> results = storage().updateRefs(updates, atomic);
        for (RefUpdateResult result : results) {
            if (result.status() != RefUpdateResult.Status.APPLIED
                    || result.update().expectedOld().equals(result.update().newId())) {
                continue;
            }
            for (Consumer<RefUpdateResult> listener : refUpdateListeners) {
                try {
                    listener.accept(result);
                } catch (RuntimeException failure) {
                    log.error("Repository ref listener failed for {} {}", name(), result.update().ref(), failure);
                }
            }
        }
        return results;
    }

    public List<RefUpdateResult> previewRefUpdates(List<RefUpdate> updates, boolean atomic) {
        Map<String, String> refs = refs();
        List<RefUpdateResult> results = new ArrayList<>(updates.size());
        boolean failed = false;
        for (RefUpdate update : updates) {
            RefUpdateResult.Status status = Objects.equals(refs.get(update.ref().value()),
                    update.expectedOld().map(ObjectId::toHex).orElse(null))
                    ? RefUpdateResult.Status.APPLIED : RefUpdateResult.Status.EXPECTED_OLD_MISMATCH;
            failed |= status != RefUpdateResult.Status.APPLIED;
            results.add(new RefUpdateResult(update, status, Optional.empty()));
        }
        if (atomic && failed) {
            for (int index = 0; index < results.size(); index++) {
                if (results.get(index).status() == RefUpdateResult.Status.APPLIED) {
                    results.set(index, new RefUpdateResult(updates.get(index),
                            RefUpdateResult.Status.ATOMIC_ABORTED, Optional.empty()));
                }
            }
        }
        return List.copyOf(results);
    }

    public boolean hasCompleteObjectClosure(ObjectId root) {
        try {
            return new GitObjectGraph(storage()).hasCompleteClosure(root);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Override
    public void close() {
        try {
            storage.close();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @FunctionalInterface
    public interface RefUpdateSubscription extends AutoCloseable {
        @Override
        void close();
    }
}
