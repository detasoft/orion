package pro.deta.orion.git.parser.v2.read;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeltaByteSourceTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readsMixedInstructionsFromReusedDirectBuffers(boolean virtualThread) throws Exception {
        if (virtualThread) {
            try (java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                executor.submit(() -> {
                    readMixedInstructions();
                    return null;
                }).get();
            }
        } else {
            readMixedInstructions();
        }
    }

    private static void readMixedInstructions() throws Exception {
        byte[] bytes = {3, 6, (byte) 0x91, 1, 2, 3, 40, 50, 60, (byte) 0x90, 1};
        boolean[] closed = {false};
        BufferedByteInputV2.Source chunks = new BufferedByteInputV2.Source() {
            private final ByteBuffer buffer = ByteBuffer.allocateDirect(2);
            private int position;

            @Override
            public ByteBuffer read() {
                if (position == bytes.length) {
                    return null;
                }
                int count = Math.min(buffer.capacity(), bytes.length - position);
                buffer.clear().put(bytes, position, count).flip();
                position += count;
                return buffer;
            }

            @Override
            public void release() {
                buffer.clear().putShort((short) -1);
            }

            @Override
            public void close() {
                closed[0] = true;
            }
        };
        try (BufferedByteInputV2 instructions = new BufferedByteInputV2(chunks)) {
            DeltaByteSource delta = new DeltaByteSource(instructions, new byte[]{10, 20, 30});
            try (BufferedByteInputV2 restored = new BufferedByteInputV2(delta)) {
                assertThat(delta.size()).isEqualTo(6);
                assertThat(restored.readUnsignedByte()).isEqualTo(20);
                assertThat(restored.newInputStream().readAllBytes()).containsExactly(30, 40, 50, 60, 10);
                assertThat(restored.buffer()).isNull();
            }
            assertThat(closed[0]).isFalse();
            assertThat(instructions.buffer()).isNull();
        }
        assertThat(closed[0]).isTrue();
    }

    @Test
    void exposesBaseAndLiteralRangesWithoutCopyingThem() throws Exception {
        byte[] base = {1, 2, 3};
        ByteBuffer encoded = ByteBuffer.wrap(new byte[]{3, 4, (byte) 0x90, 3, 1, 4});
        BufferedByteInputV2.Source source = new BufferedByteInputV2.Source() {
            @Override
            public ByteBuffer read() {
                return encoded.hasRemaining() ? encoded : null;
            }

            @Override
            public void release() {}

            @Override
            public void close() {}
        };
        try (BufferedByteInputV2 instructions = new BufferedByteInputV2(source);
             BufferedByteInputV2 restored = new BufferedByteInputV2(new DeltaByteSource(instructions, base))) {
            ByteBuffer copy = restored.buffer();
            assertThat(copy.isReadOnly()).isTrue();
            base[0] = 42;
            assertThat(restored.readBytes(3)).containsExactly(42, 2, 3);
            ByteBuffer literal = restored.buffer();
            assertThat(literal.isReadOnly()).isTrue();
            encoded.put(5, (byte) 43);
            assertThat(restored.readUnsignedByte()).isEqualTo(43);
            assertThat(restored.buffer()).isNull();
        }
    }

    @Test
    void handlesDefaultCopySizeAndRejectsTrailingInstructions() throws Exception {
        byte[] base = new byte[65536];
        base[65535] = 42;
        byte[] delta = {(byte) 0x80, (byte) 0x80, 4, (byte) 0x80, (byte) 0x80, 4, (byte) 0x80, 0};
        try (BufferedByteInputV2 instructions = new BufferedByteInputV2(new ByteArrayInputStream(delta));
             BufferedByteInputV2 restored = new BufferedByteInputV2(new DeltaByteSource(instructions, base))) {
            assertThat(restored.readBytes(base.length)).containsExactly(base);
            assertThatThrownBy(restored::buffer).isInstanceOf(IOException.class)
                    .hasMessageContaining("exceed target size");
        }
    }
}
