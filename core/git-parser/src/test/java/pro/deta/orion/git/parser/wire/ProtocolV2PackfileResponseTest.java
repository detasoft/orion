package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolV2PackfileResponseTest {

    @Test
    void writesPackfileSectionSideBandDataAndFlush() {
        ByteBuf outbound = outputBuffer();
        List<byte[]> sent = new ArrayList<>();
        GitNativeClientOutput output = collectingOutput(outbound, sent);
        byte[] pack = "PACK-data".getBytes(StandardCharsets.US_ASCII);
        GitNativeClientOutput.ProtocolV2PackfileResponse response =
                output.beginProtocolV2Packfile(producer(pack));

        try {
            complete(response);

            byte[] bytes = join(sent);
            assertThat(new String(
                    bytes,
                    0,
                    13,
                    StandardCharsets.US_ASCII))
                    .isEqualTo("000dpackfile\n");
            assertThat(new String(
                    bytes,
                    13,
                    4,
                    StandardCharsets.US_ASCII))
                    .isEqualTo("%04x".formatted(pack.length + 5));
            assertThat(bytes[17]).isEqualTo((byte) 1);
            assertThat(Arrays.copyOfRange(
                    bytes,
                    18,
                    18 + pack.length))
                    .containsExactly(pack);
            assertThat(bytes)
                    .endsWith((byte) '0', (byte) '0', (byte) '0', (byte) '0');
        } finally {
            response.close();
            outbound.release();
        }
    }

    @Test
    void writesShallowInfoBeforePackfileSection() {
        ByteBuf outbound = outputBuffer();
        List<byte[]> sent = new ArrayList<>();
        GitNativeClientOutput output = collectingOutput(outbound, sent);
        GitObjectId boundary = GitObjectId.of("1".repeat(40));
        byte[] pack = "PACK-data".getBytes(StandardCharsets.US_ASCII);
        GitNativeClientOutput.ProtocolV2PackfileResponse response =
                output.beginProtocolV2Packfile(
                        producer(pack),
                        Set.of(boundary));

        try {
            complete(response);

            byte[] bytes = join(sent);
            String prefix = new String(
                    bytes,
                    0,
                    17 + 53 + 4 + 13,
                    StandardCharsets.US_ASCII);
            assertThat(prefix)
                    .isEqualTo(
                            "0011shallow-info\n"
                                    + "0035shallow "
                                    + boundary.value()
                                    + "\n"
                                    + "0001"
                                    + "000dpackfile\n");
        } finally {
            response.close();
            outbound.release();
        }
    }

    @Test
    void streamsLargePackAndClosesProducer() {
        ByteBuf outbound = outputBuffer();
        List<byte[]> sent = new ArrayList<>();
        GitNativeClientOutput output = collectingOutput(outbound, sent);
        byte[] pack = new byte[100_000];
        java.util.Arrays.fill(pack, (byte) 7);
        AtomicBoolean closed = new AtomicBoolean();
        GitNativeClientOutput.ProtocolV2PackfileResponse response =
                output.beginProtocolV2Packfile(
                        producer(pack, closed));

        try {
            complete(response);

            assertThat(sent).hasSizeGreaterThan(1);
            assertThat(closed).isTrue();
        } finally {
            response.close();
            outbound.release();
        }
    }

    @Test
    void reportsDeliveryFailureAndClosesProducer() {
        ByteBuf outbound = outputBuffer();
        AtomicBoolean closed = new AtomicBoolean();
        GitNativeClientOutput output = new GitNativeClientOutput(
                outbound,
                ignored -> {
                    throw new IllegalStateException("delivery failed");
                });
        GitNativeClientOutput.ProtocolV2PackfileResponse response =
                output.beginProtocolV2Packfile(
                        producer(
                                "PACK".getBytes(StandardCharsets.US_ASCII),
                                closed));

        try {
            GitNativeClientOutput.SendResult.Streaming streaming =
                    (GitNativeClientOutput.SendResult.Streaming)
                            response.advance();
            streaming.task().run();

            assertThat(response.advance())
                    .isInstanceOfSatisfying(
                            GitNativeClientOutput.SendResult.Failed.class,
                            failed -> assertThat(failed.cause())
                                    .hasMessage("delivery failed"));
            assertThat(closed).isTrue();
        } finally {
            response.close();
            outbound.release();
        }
    }

    private static void complete(
            GitNativeClientOutput.ProtocolV2PackfileResponse response) {
        while (true) {
            GitNativeClientOutput.SendResult result = response.advance();
            if (result instanceof
                    GitNativeClientOutput.SendResult.Streaming streaming) {
                streaming.task().run();
                continue;
            }
            assertThat(result)
                    .isInstanceOf(
                            GitNativeClientOutput.SendResult.Completed.class);
            return;
        }
    }

    private static GitNativeClientOutput collectingOutput(
            ByteBuf outbound,
            List<byte[]> sent) {
        return new GitNativeClientOutput(
                outbound,
                buffer -> {
                    byte[] bytes = new byte[buffer.readableBytes()];
                    buffer.readBytes(bytes);
                    sent.add(bytes);
                    buffer.release();
                });
    }

    private static NativePackProducer producer(byte[] bytes) {
        return producer(bytes, new AtomicBoolean());
    }

    private static NativePackProducer producer(
            byte[] bytes,
            AtomicBoolean closed) {
        return new NativePackProducer() {
            private int offset;

            @Override
            public Result produce(ByteBuf destination) {
                int length = Math.min(
                        destination.writableBytes(),
                        bytes.length - offset);
                destination.writeBytes(bytes, offset, length);
                offset += length;
                return offset == bytes.length
                        ? Result.COMPLETED
                        : Result.MORE;
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
    }

    private static byte[] join(List<byte[]> chunks) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            output.writeBytes(chunk);
        }
        return output.toByteArray();
    }

    private static ByteBuf outputBuffer() {
        return Unpooled.buffer(
                GitNativeClientOutput.BUFFER_CAPACITY,
                GitNativeClientOutput.BUFFER_CAPACITY);
    }
}
