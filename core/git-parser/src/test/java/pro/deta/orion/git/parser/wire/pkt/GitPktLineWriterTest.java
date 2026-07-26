package pro.deta.orion.git.parser.wire.pkt;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.wire.GitFixedControlFrameReader;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitPktLineWriterTest {
    private final GitPktLineWriter writer = new GitPktLineWriter(UnpooledByteBufAllocator.DEFAULT);

    @Test
    void writesBinaryDataPacketWithoutMovingPayloadReaderIndex() {
        ByteBuf payload = Unpooled.wrappedBuffer(new byte[]{9, 0, (byte) 0xff, 'A'});
        payload.readerIndex(1);

        ByteBuf packet = writer.writeData(payload);
        try {
            assertThat(bytes(packet)).containsExactly(
                    (byte) '0', (byte) '0', (byte) '0', (byte) '7', 0, (byte) 0xff, (byte) 'A');
            assertThat(payload.readerIndex()).isOne();
            assertThat(payload.readableBytes()).isEqualTo(3);
        } finally {
            packet.release();
            payload.release();
        }
    }

    @Test
    void writesTextPacketWithoutChangingLineEndings() {
        ByteBuf packet = writer.writeText("hello");
        try {
            assertThat(ascii(packet)).isEqualTo("0009hello");
        } finally {
            packet.release();
        }
    }

    @Test
    void writesTextLineOnlyWhenCallerRequestsLineEnding() {
        ByteBuf packet = writer.writeTextLine("hello");
        try {
            assertThat(ascii(packet)).isEqualTo("000ahello\n");
        } finally {
            packet.release();
        }
    }

    @Test
    void writesControlPackets() {
        assertControlPacket(writer.writeFlush(), "0000");
        assertControlPacket(writer.writeDelimiter(), "0001");
        assertControlPacket(writer.writeResponseEnd(), "0002");
    }

    @Test
    void rejectsPayloadAboveGitPktLineLimit() {
        ByteBuf payload = Unpooled.buffer(GitFixedControlFrameReader.MAX_PKT_LINE_LENGTH);
        payload.writerIndex(GitFixedControlFrameReader.MAX_PKT_LINE_LENGTH - 3);

        try {
            assertThatThrownBy(() -> writer.writeData(payload))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Pkt-line payload exceeds Git pkt-line limit");
        } finally {
            payload.release();
        }
    }

    private static void assertControlPacket(ByteBuf packet, String expected) {
        try {
            assertThat(ascii(packet)).isEqualTo(expected);
        } finally {
            packet.release();
        }
    }

    private static String ascii(ByteBuf packet) {
        byte[] bytes = bytes(packet);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static byte[] bytes(ByteBuf packet) {
        byte[] bytes = new byte[packet.readableBytes()];
        packet.getBytes(packet.readerIndex(), bytes);
        return bytes;
    }
}
