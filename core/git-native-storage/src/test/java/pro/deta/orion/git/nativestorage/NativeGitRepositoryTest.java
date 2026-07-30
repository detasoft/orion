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
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;

import java.nio.charset.StandardCharsets;
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
                true)));

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
}
