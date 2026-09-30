package pro.deta.orion.git.nativestorage;

import lombok.extern.slf4j.Slf4j;
import pro.deta.orion.git.fileapi.GitFileApi;
import pro.deta.orion.git.nativestorage.receive.GitNativeRepositoryAccessHook;
import pro.deta.orion.git.nativestorage.receive.NativeGitReceivePack;
import pro.deta.orion.git.parser.v2.GitRepositoryContext;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.data.RefUpdateResult;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackChecksum;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.index.GitIndexApi;
import pro.deta.orion.git.parser.v2.index.GitIndexAccess;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.git.parser.v2.object.LooseObject;
import pro.deta.orion.git.parser.v2.pack.PackIngestor;
import pro.deta.orion.git.parser.v2.pack.PackWriter;
import pro.deta.orion.git.parser.v2.read.GitObjectGraph;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.read.ResolvedGitObjectRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.BufferedByteOutput;
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

/**
 * Native Git objects and ref publication. File operations are exposed separately through GitFileApi.
 */
@Slf4j
public class NativeGitRepository implements AutoCloseable {
    private final String name;
    private final GitStorageApi storage;
    private final GitIndexApi index;
    private final String defaultHead;
    private final CopyOnWriteArrayList<Consumer<RefUpdateResult>> refUpdateListeners = new CopyOnWriteArrayList<>();

    public NativeGitRepository(String name, GitStorageApi storage, GitIndexApi index, String defaultHead) {
        this.name = Objects.requireNonNull(name, "name");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.index = Objects.requireNonNull(index, "index");
        this.defaultHead = Objects.requireNonNull(defaultHead, "defaultHead");
    }

    public GitStorageApi storage() {
        return storage;
    }

    public GitIndexApi index() {
        return index;
    }

    public GitHashAlgorithm hashAlgorithm() {
        return index().hashAlgorithm();
    }

    public String name() {
        return name;
    }

    public GitFileApi files() {
        return new GitFileApi(this);
    }

    public String defaultHead() {
        return defaultHead;
    }

    public Map<String, String> refs() {
        try (GitIndexAccess access = index.createAccess()) {
            Map<String, String> refs = new LinkedHashMap<>();
            for (Map.Entry<RefId, ObjectId> ref : access.snapshotRefs().refs().entrySet()) {
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
                publishPack(ingest(input));
            }
            return id;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public Optional<LooseObject> readObject(ObjectId id) {
        try (GitIndexAccess access = index.createAccess()) {
            return GitObjectRead.read(storage(), access, id, new ResolvedGitObjectRead<>(storage(), access,
                    (type, size, base, input) -> new LooseObject(id, type,
                            input.readBytes(Math.toIntExact(size)))));
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public PackMetadata ingest(BufferedByteInputV2 input) throws IOException {
        try (GitIndexAccess access = index.createAccess();
             PackIngestor ingestor = new PackIngestor(input, storage(), access)) {
            return ingestor.ingest();
        }
    }

    public PackMetadata publishPack(PackMetadata pack) throws IOException {
        if (!storage().exists(pack.packId())) {
            throw new IOException("Cannot publish missing pack: " + pack.packId());
        }
        try (GitIndexAccess access = index.createAccess()) {
            return access.publishIndex(pack);
        }
    }

    public void writePack(PackMetadata pack, BufferedByteOutput output) throws IOException {
        try (GitIndexAccess access = index.createAccess();
             PackWriter writer = new PackWriter(output, pack.objectCount())) {
            writer.writeObjects(storage(), access.objects(pack.packId()));
            if (!writer.finish().equals(pack.packChecksum())) {
                throw new IOException("Exported pack checksum differs from published metadata");
            }
        }
    }

    public List<RefUpdateResult> publishPack(byte[] bytes, List<RefUpdate> updates, boolean atomic,
                                            GitNativeRepositoryAccessHook accessHook) throws GitOperationException {
        accessHook.beforeReceive(name());
        accessHook.beforeWrite(name());
        try (BufferedByteInputV2 input = new BufferedByteInputV2(new ByteArrayInputStream(bytes))) {
            PackChecksum id = publishPack(ingest(input)).packChecksum();
            return NativeGitReceivePack.complete(name(), this, updates, atomic, accessHook,
                    valid -> publishReceivedPack(Optional.of(id), valid, atomic));
        } catch (IOException failure) {
            throw new GitOperationException("Cannot publish file update pack", failure);
        }
    }

    public List<RefUpdateResult> publishReceivedPack(Optional<PackChecksum> pack, List<RefUpdate> updates, boolean atomic) {
        return publishRefs(updates, atomic);
    }

    public List<RefUpdateResult> publishRefs(List<RefUpdate> updates, boolean atomic) {
        List<RefUpdateResult> results = GitRepositoryContext.publishRefs(storage(), index, updates, atomic);
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
        try (GitIndexAccess access = index.createAccess()) {
            return new GitObjectGraph(storage(), access).hasCompleteClosure(root);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    @Override
    public void close() {
        try (storage) {
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
