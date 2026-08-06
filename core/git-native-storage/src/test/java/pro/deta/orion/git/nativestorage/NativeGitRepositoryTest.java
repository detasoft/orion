package pro.deta.orion.git.nativestorage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;
import pro.deta.orion.git.nativestorage.ref.LooseRefStore;
import pro.deta.orion.git.nativestorage.ref.RefUpdateResult;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.nativestorage.upload.NativeFetchResponse;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NativeGitRepositoryTest {
    private static final String NULL_ID = "0".repeat(40);
    private static final String MAIN_ID = "1".repeat(40);

    @Test
    void exposesConfiguredIdentityAndEmptyRefSnapshot() {
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                new LooseObjectStore(),
                "refs/heads/main");

        assertThat(repository.name()).isEqualTo("demo.git");
        assertThat(repository.defaultHead()).isEqualTo("refs/heads/main");
        assertThat(repository.refs()).isEmpty();
    }

    @Test
    void exposesUpdatedRefsThroughFreshSnapshots() {
        LooseRefStore refs = new LooseRefStore();
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                refs,
                new LooseObjectStore(),
                "refs/heads/main");

        refs.update("refs/heads/main", NULL_ID, MAIN_ID);

        assertThat(repository.refs())
                .containsExactlyEntriesOf(
                        java.util.Map.of("refs/heads/main", MAIN_ID));
    }

    @Test
    void publishesQuarantinedObjectsWhenRefUpdatesApply() {
        LooseObjectStore publishedObjects = new LooseObjectStore();
        LooseObjectStore quarantine = new LooseObjectStore();
        GitObjectId blob = quarantine.write(
                ObjectType.BLOB,
                "published".getBytes(StandardCharsets.UTF_8));
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                publishedObjects,
                "refs/heads/main");

        List<RefUpdateResult> results = repository.publishObjectsAndRefs(
                quarantine,
                List.of(new LooseRefStore.Update(
                        "refs/heads/main",
                        NULL_ID,
                        blob.value())));

        assertThat(results).containsExactly(RefUpdateResult.CREATED);
        assertThat(repository.refs())
                .containsEntry("refs/heads/main", blob.value());
        assertThat(repository.readObject(blob)).isPresent();
    }

    @Test
    void buildsPackWithoutObjectsReachableFromHaves() {
        LooseObjectStore objects = new LooseObjectStore();
        GitObjectId wanted = objects.write(
                ObjectType.BLOB,
                "wanted".getBytes(StandardCharsets.UTF_8));
        GitObjectId have = objects.write(
                ObjectType.BLOB,
                "already present".getBytes(StandardCharsets.UTF_8));
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                objects,
                "refs/heads/main");

        CompositeByteBuf pack = produce(repository.fetch(
                new NativeFetchRequest(
                Set.of(wanted, have),
                Set.of(have),
                true,
                true,
                true,
                false)));

        try {
            assertThat(pack.getCharSequence(
                    0,
                    4,
                    StandardCharsets.US_ASCII))
                    .hasToString("PACK");
            assertThat(pack.getInt(8)).isEqualTo(1);
        } finally {
            pack.release();
        }
    }

    @Test
    void includesAnnotatedTagWhoseTargetIsSent() {
        LooseRefStore refs = new LooseRefStore();
        LooseObjectStore objects = new LooseObjectStore();
        GitObjectId blob = objects.write(
                ObjectType.BLOB,
                "tagged".getBytes(StandardCharsets.UTF_8));
        GitObjectId tag = objects.write(
                ObjectType.TAG,
                ("object " + blob + "\n"
                        + "type blob\n"
                        + "tag v1\n"
                        + "tagger Test <test@example.com> 0 +0000\n"
                        + "\nmessage\n")
                        .getBytes(StandardCharsets.UTF_8));
        refs.update("refs/tags/v1", NULL_ID, tag.value());
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                refs,
                objects,
                "refs/heads/main");

        CompositeByteBuf pack = produce(repository.fetch(
                new NativeFetchRequest(
                        Set.of(blob),
                        Set.of(),
                        true,
                        false,
                        false,
                        true)));

        try {
            assertThat(pack.getInt(8)).isEqualTo(2);
        } finally {
            pack.release();
        }
    }

    @Test
    void fetchResponseCarriesShallowBoundaryMetadata() {
        LooseObjectStore objects = new LooseObjectStore();
        GitObjectId baseBlob = objects.write(
                ObjectType.BLOB,
                "base".getBytes(StandardCharsets.UTF_8));
        GitObjectId baseTree = objects.write(
                ObjectType.TREE,
                treeEntry("100644", "file.txt", baseBlob));
        GitObjectId baseCommit = writeCommit(objects, baseTree, null, "base");
        GitObjectId tipBlob = objects.write(
                ObjectType.BLOB,
                "tip".getBytes(StandardCharsets.UTF_8));
        GitObjectId tipTree = objects.write(
                ObjectType.TREE,
                treeEntry("100644", "file.txt", tipBlob));
        GitObjectId tipCommit = writeCommit(objects, tipTree, baseCommit, "tip");
        NativeGitRepository repository = new NativeGitRepository(
                "demo.git",
                new LooseRefStore(),
                objects,
                "refs/heads/main");

        NativeFetchResponse response = repository.fetchResponse(
                new NativeFetchRequest(
                        Set.of(tipCommit),
                        Set.of(),
                        true,
                        false,
                        false,
                        false,
                        false,
                        1));
        CompositeByteBuf pack = produce(response.packProducer());

        try {
            assertThat(response.shallowBoundaries())
                    .containsExactly(tipCommit);
            assertThat(pack.getInt(8)).isEqualTo(3);
        } finally {
            pack.release();
        }
    }

    private static CompositeByteBuf produce(
            NativePackProducer producer) {
        CompositeByteBuf complete = Unpooled.compositeBuffer();
        try (producer) {
            while (true) {
                ByteBuf fragment = Unpooled.buffer(3, 3);
                NativePackProducer.Result result;
                try {
                    result = producer.produce(fragment);
                    complete.addComponent(
                            true,
                            fragment.retain());
                } finally {
                    fragment.release();
                }
                if (result == NativePackProducer.Result.COMPLETED) {
                    return complete;
                }
            }
        } catch (RuntimeException error) {
            complete.release();
            throw error;
        }
    }

    private static GitObjectId writeCommit(
            LooseObjectStore objects,
            GitObjectId tree,
            GitObjectId parent,
            String message) {
        StringBuilder data = new StringBuilder()
                .append("tree ")
                .append(tree)
                .append('\n');
        if (parent != null) {
            data.append("parent ").append(parent).append('\n');
        }
        data.append("author Test <test@example.com> 0 +0000\n")
                .append("committer Test <test@example.com> 0 +0000\n")
                .append('\n')
                .append(message)
                .append('\n');
        return objects.write(
                ObjectType.COMMIT,
                data.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] treeEntry(
            String mode,
            String name,
            GitObjectId objectId) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes((mode + " " + name + "\0")
                .getBytes(StandardCharsets.UTF_8));
        output.writeBytes(HexFormat.of().parseHex(objectId.value()));
        return output.toByteArray();
    }
}
