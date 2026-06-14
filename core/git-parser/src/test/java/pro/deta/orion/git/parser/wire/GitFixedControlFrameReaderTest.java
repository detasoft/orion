package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitFixedControlFrameReaderTest {
    @Test
    void readsCompletePktLineFrameWithoutAllocatingStructuralBuffer() {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        try (GitFixedControlFrameReader reader = new GitFixedControlFrameReader(allocator)) {
            ByteBuf input = ascii("000aabcdef");
            try {
                assertThat(reader.accept(input))
                        .isEqualTo(GitFixedControlFrameReader.ControlReadState.CONTROL_COMPLETE);

                assertThat(input.readerIndex()).isEqualTo(10);
                assertThat(input.readableBytes()).isZero();
                assertThat(input.refCnt()).isEqualTo(2);
                assertThat(allocator.allocations()).isZero();
                assertThat(reader.bufferedBytes()).isZero();
                assertThat(reader.isRetainedFrom(input)).isTrue();
                assertThat(readableBytes(reader.bytes())).containsExactly(asciiBytes("000aabcdef"));
            } finally {
                input.release();
            }
        }
    }

    @Test
    void copiesFragmentedPayloadAfterCompleteHeader() {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        try (GitFixedControlFrameReader reader = new GitFixedControlFrameReader(allocator)) {
            ByteBuf first = ascii("000aab");
            try {
                assertThat(reader.accept(first))
                        .isEqualTo(GitFixedControlFrameReader.ControlReadState.NEEDS_MORE_DATA);

                assertThat(first.readerIndex()).isEqualTo(6);
                assertThat(first.readableBytes()).isZero();
                assertThat(allocator.allocations()).isOne();
                assertThat(allocator.lastInitialCapacity()).isEqualTo(10);
                assertThat(allocator.lastMaxCapacity()).isEqualTo(GitFixedControlFrameReader.MAX_PKT_LINE_LENGTH);
                assertThat(reader.bufferedBytes()).isEqualTo(6);
                assertThatThrownBy(reader::bytes)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("Control frame is not complete");
            } finally {
                first.release();
            }

            ByteBuf second = ascii("cdefxy");
            try {
                assertThat(reader.accept(second))
                        .isEqualTo(GitFixedControlFrameReader.ControlReadState.CONTROL_COMPLETE);

                assertThat(second.readerIndex()).isEqualTo(4);
                assertThat(second.readableBytes()).isEqualTo(2);
                assertThat(allocator.allocations()).isOne();
                assertThat(reader.isRetainedFrom(second)).isFalse();
                assertThat(readableBytes(reader.bytes())).containsExactly(asciiBytes("000aabcdef"));
            } finally {
                second.release();
            }
        }
    }

    @Test
    void rejectsHeaderSplitAcrossInputsForNow() {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        try (GitFixedControlFrameReader reader = new GitFixedControlFrameReader(allocator)) {
            ByteBuf input = ascii("00");
            try {
                assertThatThrownBy(() -> reader.accept(input))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Pkt-line header must be available in one input buffer");
                assertThat(input.readerIndex()).isZero();
                assertThat(allocator.allocations()).isZero();
            } finally {
                input.release();
            }
        }
    }

    @Test
    void rejectsAcceptAfterCompleteWithoutConsumingInput() {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        try (GitFixedControlFrameReader reader = new GitFixedControlFrameReader(allocator)) {
            ByteBuf first = ascii("000aabcdef");
            try {
                assertThat(reader.accept(first))
                        .isEqualTo(GitFixedControlFrameReader.ControlReadState.CONTROL_COMPLETE);
            } finally {
                first.release();
            }

            ByteBuf second = ascii("x");
            try {
                int readableBefore = second.readableBytes();
                assertThatThrownBy(() -> reader.accept(second))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("Control frame is already complete");
                assertThat(second.readableBytes()).isEqualTo(readableBefore);
            } finally {
                second.release();
            }
        }
    }

    @Test
    void readsSpecialPktLinePacketsWithoutAllocatingStructuralBuffer() {
        assertSpecialPacket("0000");
        assertSpecialPacket("0001");
        assertSpecialPacket("0002");
    }

    @Test
    void rejectsInvalidPktLineHeader() {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        try (GitFixedControlFrameReader reader = new GitFixedControlFrameReader(allocator)) {
            ByteBuf input = ascii("zzzz");
            try {
                assertThatThrownBy(() -> reader.accept(input))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Pkt-line length contains non-hex byte");
            } finally {
                input.release();
            }
        }
    }

    @Test
    void rejectsReservedPktLineLength() {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        try (GitFixedControlFrameReader reader = new GitFixedControlFrameReader(allocator)) {
            ByteBuf input = ascii("0003");
            try {
                assertThatThrownBy(() -> reader.accept(input))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Pkt-line length 0003 is reserved");
            } finally {
                input.release();
            }
        }
    }

    @Test
    void rejectsPktLinePacketAboveGitLimit() {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        try (GitFixedControlFrameReader reader = new GitFixedControlFrameReader(allocator)) {
            ByteBuf input = ascii("fff1");
            try {
                assertThatThrownBy(() -> reader.accept(input))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessage("Pkt-line length exceeds Git pkt-line limit");
            } finally {
                input.release();
            }
        }
    }

    private static void assertSpecialPacket(String header) {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        try (GitFixedControlFrameReader reader = new GitFixedControlFrameReader(allocator)) {
            ByteBuf input = ascii(header + "tail");
            try {
                assertThat(reader.accept(input))
                        .isEqualTo(GitFixedControlFrameReader.ControlReadState.CONTROL_COMPLETE);

                assertThat(input.readerIndex()).isEqualTo(4);
                assertThat(input.readableBytes()).isEqualTo(4);
                assertThat(allocator.allocations()).isZero();
                assertThat(readableBytes(reader.bytes())).containsExactly(asciiBytes(header));
            } finally {
                input.release();
            }
        }
    }

    private static ByteBuf ascii(String value) {
        ByteBuf buffer = Unpooled.buffer(value.length());
        for (int i = 0; i < value.length(); i++) {
            buffer.writeByte(value.charAt(i));
        }
        return buffer;
    }

    private static byte[] readableBytes(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return bytes;
    }

    private static byte[] asciiBytes(String value) {
        byte[] bytes = new byte[value.length()];
        for (int i = 0; i < value.length(); i++) {
            bytes[i] = (byte) value.charAt(i);
        }
        return bytes;
    }
}
