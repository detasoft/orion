package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.pack.NativePackProducer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProtocolV2ShallowInfoResponseTest {

    @Test
    void writesShallowInfoBeforePackfileSection() throws Exception {
        ByteBuf outbound = outputBuffer();
        List<byte[]> sent = new ArrayList<>();
        GitBlockingWireTransport output = collectingOutput(outbound, sent);
        GitObjectId boundary = GitObjectId.of("1".repeat(40));
        byte[] pack = "PACK-data".getBytes(StandardCharsets.US_ASCII);
        GitBlockingWireTransport.ProtocolV2PackfileResponse response =
                output.beginProtocolV2Packfile(
                        producer(pack),
                        Set.of(boundary));

        try {
            response.advance();

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
    void writesUnshallowInfoBeforePackfileSection() throws Exception {
        ByteBuf outbound = outputBuffer();
        List<byte[]> sent = new ArrayList<>();
        GitBlockingWireTransport output = collectingOutput(outbound, sent);
        GitObjectId shallow = GitObjectId.of("1".repeat(40));
        GitObjectId unshallow = GitObjectId.of("2".repeat(40));
        byte[] pack = "PACK-data".getBytes(StandardCharsets.US_ASCII);
        GitBlockingWireTransport.ProtocolV2PackfileResponse response =
                output.beginProtocolV2Packfile(
                        producer(pack),
                        Set.of(shallow),
                        Set.of(unshallow),
                        Map.of(),
                        List.of(),
                        false);

        try {
            response.advance();

            byte[] bytes = join(sent);
            String prefix = new String(
                    bytes,
                    0,
                    17 + 53 + 55 + 4 + 13,
                    StandardCharsets.US_ASCII);
            assertThat(prefix)
                    .isEqualTo(
                            "0011shallow-info\n"
                                    + "0035shallow "
                                    + shallow.value()
                                    + "\n"
                                    + "0037unshallow "
                                    + unshallow.value()
                                    + "\n"
                                    + "0001"
                                    + "000dpackfile\n");
        } finally {
            response.close();
            outbound.release();
        }
    }

    private static GitBlockingWireTransport collectingOutput(
            ByteBuf outbound,
            List<byte[]> sent) {
        return new GitBlockingWireTransport(
                new SubmittedByteBufOutput(outbound, buffer -> {
                    byte[] bytes = new byte[buffer.readableBytes()];
                    buffer.readBytes(bytes);
                    sent.add(bytes);
                    buffer.release();
                }));
    }

    private static NativePackProducer producer(byte[] bytes) {
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
                GitBlockingWireTransport.BUFFER_CAPACITY,
                GitBlockingWireTransport.BUFFER_CAPACITY);
    }
}
