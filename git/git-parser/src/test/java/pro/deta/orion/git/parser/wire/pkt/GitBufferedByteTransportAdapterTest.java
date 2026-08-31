package pro.deta.orion.git.parser.wire.pkt;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.git.parser.wire.control.ControlState.MAX_PKT_LINE_LENGTH;

class GitBufferedByteTransportAdapterTest {
    private final ByteBufAllocator allocator = UnpooledByteBufAllocator.DEFAULT;

    @Test
    void readsPktLineControlAndPayloadFromBufferedInput() throws Exception {
        GitBufferedByteTransportAdapter adapter = inputAdapter("000ahello\n0000");

        ControlState data = adapter.readControlState();
        ByteBuf payload = adapter.readPayload(data);
        try {
            assertThat(data.type()).isEqualTo(ControlState.ControlType.DATA);
            assertThat(payload.toString(StandardCharsets.UTF_8)).isEqualTo("hello\n");

            ControlState flush = adapter.readControlState();
            assertThat(flush.type()).isEqualTo(ControlState.ControlType.FLUSH);
            assertThat(flush.payloadLength()).isZero();
        } finally {
            payload.release();
        }
    }

    @Test
    void rejectsMalformedPktLineHeaderFromBufferedInput() {
        GitBufferedByteTransportAdapter adapter = inputAdapter("zzzz");

        assertThatThrownBy(adapter::readControlState)
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Invalid Git pkt-line header");
    }

    @Test
    void writesPktLinePacketsToBufferedOutput() throws Exception {
        RecordingOutput output = new RecordingOutput();
        GitBufferedByteTransportAdapter adapter = outputAdapter(output);
        ByteBuf payload = Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8);
        try {
            adapter.writeData(payload);
            adapter.writeFlush();
            adapter.flush();

            assertThat(output.ascii()).isEqualTo("0009hello0000");
        } finally {
            payload.release();
        }
    }

    @Test
    void writesSidebandPacketsAndSplitsAtPktLineLimit() throws Exception {
        RecordingOutput output = new RecordingOutput();
        GitBufferedByteTransportAdapter adapter = outputAdapter(output);
        int firstPayloadLength = MAX_PKT_LINE_LENGTH - 5;
        ByteBuf payload = allocator.buffer(firstPayloadLength + 3, firstPayloadLength + 3);
        try {
            payload.writeBytes(repeated((byte) 'a', firstPayloadLength));
            payload.writeBytes(new byte[]{'b', 'c', 'd'});

            adapter.writeSidebandData(payload);

            byte[] bytes = output.bytes();
            assertThat(new String(bytes, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("fff0");
            assertThat(bytes[4]).isEqualTo((byte) 1);
            assertThat(bytes[4 + firstPayloadLength]).isEqualTo((byte) 'a');
            int secondHeaderOffset = MAX_PKT_LINE_LENGTH;
            assertThat(new String(bytes, secondHeaderOffset, 4, StandardCharsets.US_ASCII)).isEqualTo("0008");
            assertThat(bytes[secondHeaderOffset + 4]).isEqualTo((byte) 1);
            assertThat(Arrays.copyOfRange(bytes, secondHeaderOffset + 5, secondHeaderOffset + 8))
                    .containsExactly((byte) 'b', (byte) 'c', (byte) 'd');
        } finally {
            payload.release();
        }
    }

    @Test
    void writesProgressAndErrorSidebandPackets() throws Exception {
        RecordingOutput output = new RecordingOutput();
        GitBufferedByteTransportAdapter adapter = outputAdapter(output);

        adapter.writeSidebandProgress("counting");
        adapter.writeSidebandError("failed");

        assertThat(output.ascii()).isEqualTo("000d\u0002counting000b\u0003failed");
    }

    @Test
    void writesSidebandHeaderSeparatelyFromPayload() throws Exception {
        RecordingOutput output = new RecordingOutput();
        GitBufferedByteTransportAdapter adapter = outputAdapter(output);
        ByteBuf payload = Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8);
        try {
            adapter.writeSidebandData(payload);

            assertThat(output.writeLengths()).containsExactly(5, 5);
            assertThat(output.ascii()).isEqualTo("000a\u0001hello");
        } finally {
            payload.release();
        }
    }

    private GitBufferedByteTransportAdapter inputAdapter(String ascii) {
        return new GitBufferedByteTransportAdapter(
                new ArrayInput(ascii.getBytes(StandardCharsets.US_ASCII)),
                null,
                allocator);
    }

    private GitBufferedByteTransportAdapter outputAdapter(RecordingOutput output) {
        return new GitBufferedByteTransportAdapter(null, output, allocator);
    }

    private static byte[] repeated(byte value, int length) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, value);
        return bytes;
    }

    private static final class ArrayInput implements BufferedByteInput {
        private final byte[] bytes;
        private int offset;

        private ArrayInput(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int available() {
            return bytes.length - offset;
        }

        @Override
        public int readUnsignedByte() throws IOException {
            if (offset == bytes.length) {
                throw new EOFException("test input exhausted");
            }
            return bytes[offset++] & 0xff;
        }

        @Override
        public ByteBuf readCopy(int length) throws IOException {
            if (length < 0) {
                throw new IllegalArgumentException("length must be non-negative");
            }
            if (available() < length) {
                throw new EOFException("test input exhausted");
            }
            ByteBuf copy = Unpooled.buffer(length, length);
            copy.writeBytes(bytes, offset, length);
            offset += length;
            return copy;
        }

        @Override
        public int readInto(ByteBuf target, int maxLength) throws IOException {
            int copied = Math.min(Math.min(maxLength, target.writableBytes()), available());
            target.writeBytes(bytes, offset, copied);
            offset += copied;
            return copied;
        }
    }

    private static final class RecordingOutput implements BufferedByteOutput {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final List<Integer> writeLengths = new ArrayList<>();

        @Override
        public void write(ByteBuf buffer) {
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), bytes);
            writeLengths.add(bytes.length);
            output.write(bytes, 0, bytes.length);
        }

        @Override
        public void flush() {
        }

        private byte[] bytes() {
            return output.toByteArray();
        }

        private String ascii() {
            return output.toString(StandardCharsets.US_ASCII);
        }

        private List<Integer> writeLengths() {
            return writeLengths;
        }
    }
}
