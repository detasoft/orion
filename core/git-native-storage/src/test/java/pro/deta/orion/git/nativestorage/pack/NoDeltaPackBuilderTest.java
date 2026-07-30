package pro.deta.orion.git.nativestorage.pack;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class NoDeltaPackBuilderTest {
    private final LooseObjectStore objects = new LooseObjectStore();
    private final NoDeltaPackBuilder builder = new NoDeltaPackBuilder();

    @Test
    void buildsOneObjectPackWithHeaderAndTrailer() {
        GitObjectId id = objects.write(ObjectType.BLOB, "hello\n".getBytes(StandardCharsets.UTF_8));
        CompositeByteBuf pack =
                produce(
                        builder.producer(objects, List.of(id)),
                        1);
        try {
            assertThat(pack.getCharSequence(
                    0,
                    4,
                    StandardCharsets.US_ASCII))
                    .hasToString("PACK");
            assertThat(pack.getInt(4)).isEqualTo(2);
            assertThat(pack.getInt(8)).isEqualTo(1);
            assertThat(ByteBufUtil.getBytes(
                    pack,
                    pack.writerIndex() - 20,
                    20,
                    false))
                    .containsExactly(sha1(
                            pack,
                            0,
                            pack.writerIndex() - 20));
            assertThat(new PackIngestor(pack.readableBytes())
                    .ingest(pack.duplicate())
                    .contains(id))
                    .isTrue();
        } finally {
            pack.release();
        }
    }

    @Test
    void sortsObjectsByIdForDeterministicPacks() {
        GitObjectId first = objects.write(ObjectType.BLOB, "one\n".getBytes(StandardCharsets.UTF_8));
        GitObjectId second = objects.write(ObjectType.BLOB, "two\n".getBytes(StandardCharsets.UTF_8));
        CompositeByteBuf forward = produce(
                builder.producer(
                        objects,
                        List.of(first, second)),
                3);
        CompositeByteBuf reverse = produce(
                builder.producer(
                        objects,
                        List.of(second, first)),
                7);

        try {
            assertThat(ByteBufUtil.equals(forward, reverse))
                    .isTrue();
            LooseObjectStore ingested =
                    new PackIngestor(forward.readableBytes())
                            .ingest(forward.duplicate());
            assertThat(ingested.contains(first)).isTrue();
            assertThat(ingested.contains(second)).isTrue();
        } finally {
            forward.release();
            reverse.release();
        }
    }

    private static CompositeByteBuf produce(
            NativePackProducer producer,
            int fragmentSize) {
        CompositeByteBuf complete = Unpooled.compositeBuffer();
        try (producer) {
            while (true) {
                ByteBuf fragment = Unpooled.buffer(
                        fragmentSize,
                        fragmentSize);
                try {
                    NativePackProducer.Result result =
                            producer.produce(fragment);
                    complete.addComponent(
                            true,
                            fragment.retain());
                    if (result
                            == NativePackProducer.Result.COMPLETED) {
                        return complete;
                    }
                } finally {
                    fragment.release();
                }
            }
        } catch (RuntimeException error) {
            complete.release();
            throw error;
        }
    }

    private static byte[] sha1(
            ByteBuf bytes,
            int offset,
            int length) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-1");
            for (ByteBuffer buffer
                    : bytes.nioBuffers(offset, length)) {
                digest.update(buffer);
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }
}
