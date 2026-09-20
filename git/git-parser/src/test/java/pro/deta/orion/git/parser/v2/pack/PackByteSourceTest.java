package pro.deta.orion.git.parser.v2.pack;

import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.EOFException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackByteSourceTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readsOnlyTheRequestedRangeAndBorrowsStorage(boolean memory) throws Exception {
        byte[] content = new byte[30_000];
        new Random(42).nextBytes(content);
        try (PackDataStorage storage = storage(memory)) {
            storage.write(0, ByteBuffer.wrap(content));
            try (BufferedByteInputV2 input = new BufferedByteInputV2(new PackByteSource(storage, 7, 29_999))) {
                assertThat(input.buffer().isReadOnly()).isTrue();
                assertThat(input.readUnsignedByte()).isEqualTo(Byte.toUnsignedInt(content[7]));
                assertThat(input.readBytes(29_991)).containsExactly(Arrays.copyOfRange(content, 8, 29_999));
                assertThat(input.buffer()).isNull();
            }
            assertThat(storage.isOpen()).isTrue();
            ByteBuffer last = ByteBuffer.allocate(1);
            assertThat(storage.read(29_999, last)).isEqualTo(1);
            assertThat(last.array()).containsExactly(content[29_999]);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reportsTruncationWhenRefillingAndRejectsInvalidRanges(boolean memory) throws Exception {
        try (PackDataStorage storage = storage(memory)) {
            storage.write(0, ByteBuffer.allocate(20_000));
            try (BufferedByteInputV2 input = new BufferedByteInputV2(new PackByteSource(storage, 0, 20_000))) {
                input.buffer().position(input.buffer().limit());
                storage.truncate(0);
                assertThatThrownBy(input::buffer).isInstanceOf(EOFException.class);
            }
            try (BufferedByteInputV2 empty = new BufferedByteInputV2(new PackByteSource(storage, 0, 0))) {
                assertThat(empty.buffer()).isNull();
            }
            assertThatThrownBy(() -> new PackByteSource(storage, -1, 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new PackByteSource(storage, 2, 1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private PackDataStorage storage(boolean memory) throws Exception {
        return memory ? PackDataStorage.memory() : PackDataStorage.open(directory.resolve("pack"),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }
}
