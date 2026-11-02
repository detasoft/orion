package pro.deta.orion.test.integration.git;

import pro.deta.orion.git.fileapi.GitFileAccess;
import pro.deta.orion.git.nativestorage.GitOperationException;
import pro.deta.orion.git.nativestorage.NativeGitRepository;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.RefUpdate;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.RefId;
import pro.deta.orion.git.parser.v2.index.IndexedObject;
import pro.deta.orion.git.parser.v2.index.PackMetadata;
import pro.deta.orion.git.parser.v2.index.memory.InMemoryIndex;
import pro.deta.orion.git.parser.v2.pack.PackWriter;
import pro.deta.orion.git.parser.v2.storage.memory.InMemoryStorage;
import pro.deta.orion.internal.CheckedFunction;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.OutputStreamBufferedByteOutput;
import pro.deta.orion.util.VcChangeAuthor;
import pro.deta.orion.util.VcChangeDescription;
import pro.deta.orion.util.VcRevision;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Creates wire-pack fixtures without publishing objects or refs in the repository under test. */
public final class FileTestSupport {
    private FileTestSupport() {}

    public static void initialize(NativeGitRepository repository, String branch) throws Exception {
        NativeGitRepository source = repository.eventSource();
        String ref = source.ref(branch).value();
        if (source.refs().containsKey(ref)) return;
        GitOperationException.requireSuccess(List.of(source.updateRef(ref, "0".repeat(40), seed(source).value())));
    }

    private static VcRevision.Stored seed(NativeGitRepository repository) {
        ObjectId tree = repository.writeObject(GitObjectType.TREE, new byte[0]);
        String content = "tree " + tree.toHex() + "\n"
                + "author Test <test@example.invalid> 1 +0000\n"
                + "committer Test <test@example.invalid> 1 +0000\n\nseed\n";
        return new VcRevision.Stored(repository.writeObject(
                GitObjectType.COMMIT, content.getBytes(StandardCharsets.UTF_8)).toHex());
    }

    public static Prepared prepared(NativeGitRepository repository, String branch, String message,
            VcChangeAuthor author, CheckedFunction<GitFileAccess, Void> changes) throws Exception {
        return prepared(repository, branch, repository.refs().get(repository.ref(branch).value()),
                message, author, changes);
    }

    public static Prepared prepared(NativeGitRepository repository, String branch, String revision,
            String message, VcChangeAuthor author, CheckedFunction<GitFileAccess, Void> changes) throws Exception {
        try (NativeGitRepository fixture = new NativeGitRepository("prepared", new InMemoryStorage(),
                new InMemoryIndex(new RefId("refs/heads/main")), repository.events())) {
            VcRevision.Stored base;
            if (revision == null) {
                base = seed(fixture);
            } else {
                try (BufferedByteInputV2 input = new BufferedByteInputV2(
                        new ByteArrayInputStream(pack(repository)))) {
                    fixture.ingestAndPublish(input);
                }
                base = new VcRevision.Stored(revision);
            }
            try (GitFileAccess access = fixture.files().open(base)) {
                changes.apply(access);
                ObjectId commit = new ObjectId(access.commit(new VcChangeDescription(author, message)).value());
                RefUpdate update = new RefUpdate(new RefId(repository.ref(branch).value()),
                        Optional.ofNullable(revision).map(ObjectId::new), Optional.of(commit));
                return new Prepared(pack(fixture), List.of(update));
            }
        }
    }

    private static byte[] pack(NativeGitRepository repository) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        repository.index().withAccess(index -> repository.storage().withAccess(storage -> {
            List<IndexedObject> objects = new ArrayList<>();
            Set<ObjectId> included = new HashSet<>();
            for (PackMetadata pack : index.packs()) {
                for (IndexedObject object : index.objects(pack.packId())) {
                    if (included.add(object.objectId())) objects.add(object);
                }
            }
            try (PackWriter writer = new PackWriter(new OutputStreamBufferedByteOutput(output), objects.size())) {
                writer.writeObjects(storage, objects);
                writer.finish();
            }
            return null;
        }));
        return output.toByteArray();
    }

    public record Prepared(byte[] pack, List<RefUpdate> refUpdates) {
        @Override
        public byte[] pack() {
            return pack.clone();
        }
    }
}
