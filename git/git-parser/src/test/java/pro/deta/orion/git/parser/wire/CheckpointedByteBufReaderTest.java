package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CheckpointedByteBufReaderTest {
    @Test
    void restoresReaderIndexWhenClosedWithoutCommit() {
        ByteBuf input = ascii("abcdef");
        try {
            input.readerIndex(2);

            try (CheckpointedByteBufReader reader = CheckpointedByteBufReader.open(input)) {
                assertThat(reader.readUnsignedByte()).isEqualTo('c');
                reader.skipBytes(2);
                assertThat(reader.readerIndex()).isEqualTo(5);
            }

            assertThat(input.readerIndex()).isEqualTo(2);
            assertThat(readableString(input)).isEqualTo("cdef");
        } finally {
            input.release();
        }
    }

    @Test
    void keepsConsumedReaderIndexWhenCommittedBeforeClose() {
        ByteBuf input = ascii("abcdef");
        try {
            try (CheckpointedByteBufReader reader = CheckpointedByteBufReader.open(input)) {
                assertThat(reader.readUnsignedByte()).isEqualTo('a');
                assertThat(reader.readUnsignedByte()).isEqualTo('b');

                reader.commit();
            }

            assertThat(input.readerIndex()).isEqualTo(2);
            assertThat(readableString(input)).isEqualTo("cdef");
        } finally {
            input.release();
        }
    }

    @Test
    void exposesReadableStateAndPrimitiveReads() {
        ByteBuf input = Unpooled.buffer(5);
        input.writeByte(0xff);
        input.writeInt(0x0001_0203);
        try {
            try (CheckpointedByteBufReader reader = CheckpointedByteBufReader.open(input)) {
                assertThat(reader.isReadable()).isTrue();
                assertThat(reader.readableBytes()).isEqualTo(5);
                assertThat(reader.readUnsignedByte()).isEqualTo(255);
                assertThat(reader.readInt()).isEqualTo(0x0001_0203);
                assertThat(reader.isReadable()).isFalse();

                reader.commit();
            }

            assertThat(input.readerIndex()).isEqualTo(5);
        } finally {
            input.release();
        }
    }

    @Test
    void retainedSliceAdvancesInputAndLeavesSliceOwnedByCaller() {
        ByteBuf input = ascii("abcdef");
        ByteBuf slice = null;
        try {
            try (CheckpointedByteBufReader reader = CheckpointedByteBufReader.open(input)) {
                slice = reader.readRetainedSlice(3);

                assertThat(input.readerIndex()).isEqualTo(3);
                assertThat(readableString(slice)).isEqualTo("abc");
                assertThat(input.refCnt()).isEqualTo(2);

                reader.commit();
            }

            assertThat(input.readerIndex()).isEqualTo(3);
            assertThat(readableString(input)).isEqualTo("def");
        } finally {
            if (slice != null) {
                slice.release();
            }
            input.release();
        }
    }

    @Test
    void commitIsIdempotent() {
        ByteBuf input = ascii("abcdef");
        try {
            try (CheckpointedByteBufReader reader = CheckpointedByteBufReader.open(input)) {
                reader.skipBytes(4);

                reader.commit();
                reader.commit();
            }

            assertThat(input.readerIndex()).isEqualTo(4);
            assertThat(readableString(input)).isEqualTo("ef");
        } finally {
            input.release();
        }
    }

    private static ByteBuf ascii(String value) {
        ByteBuf buffer = Unpooled.buffer(value.length());
        for (int i = 0; i < value.length(); i++) {
            buffer.writeByte(value.charAt(i));
        }
        return buffer;
    }

    private static String readableString(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }
}
