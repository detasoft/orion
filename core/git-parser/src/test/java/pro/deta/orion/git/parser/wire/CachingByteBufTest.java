package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class CachingByteBufTest {
    @Test
    void bufferedModeCachesBytesInSingleOwnedBuffer() {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        ByteBuf first = ascii("0");
        CachingByteBuf output = new CachingByteBuf(allocator, first, 4, CachingByteBuf.Mode.BUFFERED);
        ByteBuf byteBuf = output;
        first.release();
        try {
            assertThat(readableString(byteBuf)).isEqualTo("0");
            assertThat(first.refCnt()).isZero();
            assertThat(output.refCnt()).isOne();
            assertThat(output.isComplete()).isFalse();

            ByteBuf second = ascii("0");
            int secondConsumed = output.append(second);
            second.release();

            assertThat(secondConsumed).isOne();
            assertThat(readableString(output)).isEqualTo("00");
            assertThat(output.isComplete()).isFalse();
            assertThat(allocator.allocations()).isOne();

            ByteBuf third = ascii("0aPACK");
            try {
                int thirdConsumed = output.append(third);

                assertThat(thirdConsumed).isEqualTo(2);
                assertThat(readableString(output)).isEqualTo("000a");
                assertThat(readableString(third)).isEqualTo("PACK");
                assertThat(output.isComplete()).isTrue();
                assertThat(allocator.allocations()).isOne();
            } finally {
                third.release();
            }
        } finally {
            output.release();
        }
    }

    @Test
    void compositeModeCachesRetainedSlicesWithoutCopyingIntoOwnedBuffer() {
        CountingByteBufAllocator allocator = new CountingByteBufAllocator();
        ByteBuf first = ascii("0");
        CachingByteBuf output = new CachingByteBuf(allocator, first, 4, CachingByteBuf.Mode.COMPOSITE);
        ByteBuf byteBuf = output;
        assertThat(first.refCnt()).isEqualTo(2);
        first.release();
        try {
            assertThat(first.refCnt()).isOne();
            assertThat(readableString(byteBuf)).isEqualTo("0");
            assertThat(output.isComplete()).isFalse();
            assertThat(allocator.allocations()).isZero();

            ByteBuf second = ascii("00aPACK");
            int consumed = output.append(second);
            assertThat(second.refCnt()).isEqualTo(2);
            assertThat(readableString(second)).isEqualTo("PACK");
            second.release();

            assertThat(consumed).isEqualTo(3);
            assertThat(second.refCnt()).isOne();
            assertThat(readableString(output)).isEqualTo("000a");
            assertThat(output.isComplete()).isTrue();
            assertThat(allocator.allocations()).isZero();

            output.release();
            assertThat(first.refCnt()).isZero();
            assertThat(second.refCnt()).isZero();
        } finally {
            if (output.refCnt() > 0) {
                output.release();
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

    private static String readableString(ByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.getBytes(buffer.readerIndex(), bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }
}
