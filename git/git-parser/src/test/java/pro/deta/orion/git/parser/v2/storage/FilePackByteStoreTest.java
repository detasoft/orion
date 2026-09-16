package pro.deta.orion.git.parser.v2.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilePackByteStoreTest {
    @TempDir
    Path directory;

    @Test
    void readsAcceptedWritesImmediatelyWithoutMovingTheAppendPosition() throws Exception {
        Path path = directory.resolve("incoming.pack");
        try (var store = new FilePackByteStore(path)) {
            writeAll(store, ByteBuffer.wrap(new byte[]{1, 2, 3}));
            ByteBuffer destination = ByteBuffer.allocate(2);
            assertThat(store.read(1, destination)).isEqualTo(2);
            assertThat(destination.array()).containsExactly((byte) 2, (byte) 3);
            writeAll(store, ByteBuffer.wrap(new byte[]{4, 5}));
            assertThat(Files.readAllBytes(path)).containsExactly(1, 2, 3, 4, 5);
            assertThat(store.isOpen()).isTrue();
            store.force();
        }
        assertThat(Files.readAllBytes(path)).containsExactly(1, 2, 3, 4, 5);
    }

    @Test
    void preservesLimitsAndAdvancesPositionsForHeapDirectAndSlicedBuffers() throws Exception {
        for (ByteBuffer buffer : new ByteBuffer[]{ByteBuffer.allocate(8), ByteBuffer.allocateDirect(8),
                ByteBuffer.allocate(12).position(4).slice()}) {
            Path path = directory.resolve("pack-" + System.identityHashCode(buffer));
            try (var store = new FilePackByteStore(path)) {
                buffer.put(new byte[]{0, 1, 2, 3, 4, 5});
                buffer.position(1).limit(4);
                writeAll(store, buffer);
                assertThat(buffer.position()).isEqualTo(4);
                assertThat(buffer.limit()).isEqualTo(4);
                buffer.clear().position(2).limit(5);
                assertThat(store.read(0, buffer)).isEqualTo(3);
                assertThat(buffer.position()).isEqualTo(5);
                assertThat(buffer.limit()).isEqualTo(5);
                buffer.position(2);
                assertThat(buffer.get()).isEqualTo((byte) 1);
                assertThat(buffer.get()).isEqualTo((byte) 2);
                assertThat(buffer.get()).isEqualTo((byte) 3);
            }
        }
    }

    @Test
    void handlesEmptyBuffersEndOfFileAndLongOffsets() throws Exception {
        try (var store = new FilePackByteStore(directory.resolve("incoming.pack"))) {
            ByteBuffer destination = ByteBuffer.allocate(4);
            assertThat(store.read(0, destination)).isEqualTo(-1);
            assertThat(store.read(Long.MAX_VALUE, destination)).isEqualTo(-1);
            assertThat(destination.position()).isZero();
            assertThat(store.read(Long.MAX_VALUE, ByteBuffer.allocate(0))).isZero();
            assertThat(store.write(ByteBuffer.allocate(0))).isZero();
            writeAll(store, ByteBuffer.wrap(new byte[]{1, 2}));
            assertThat(store.read(1, destination)).isEqualTo(1);
            assertThat(store.read(2, destination)).isEqualTo(-1);
        }
    }

    @Test
    void validatesOffsetsAndBuffersWithoutChangingStoredBytes() throws Exception {
        Path path = directory.resolve("incoming.pack");
        try (var store = new FilePackByteStore(path)) {
            writeAll(store, ByteBuffer.wrap(new byte[]{1}));
            assertThatThrownBy(() -> store.read(-1, ByteBuffer.allocate(1)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.read(0, null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> store.write(null)).isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> store.read(0, ByteBuffer.allocate(1).asReadOnlyBuffer()))
                    .isInstanceOf(ReadOnlyBufferException.class);
            assertThatThrownBy(() -> store.read(0, ByteBuffer.allocate(0).asReadOnlyBuffer()))
                    .isInstanceOf(ReadOnlyBufferException.class);
            assertThat(Files.readAllBytes(path)).containsExactly(1);
        }
    }

    @Test
    void closesIdempotentlyAndRejectsEvenEmptyReadsAndWritesAfterClose() throws Exception {
        Path path = directory.resolve("incoming.pack");
        var store = new FilePackByteStore(path);
        store.close();
        store.close();
        assertThat(store.isOpen()).isFalse();
        assertThatThrownBy(() -> store.read(0, ByteBuffer.allocate(0)))
                .isInstanceOf(ClosedChannelException.class);
        assertThatThrownBy(() -> store.write(ByteBuffer.allocate(0)))
                .isInstanceOf(ClosedChannelException.class);
        assertThatThrownBy(store::force).isInstanceOf(ClosedChannelException.class);
        assertThat(Files.exists(path)).isTrue();
    }

    @Test
    void neverReplacesAnExistingAttemptFile() throws Exception {
        Path path = directory.resolve("incoming.pack");
        Files.write(path, new byte[]{1, 2, 3});
        assertThatThrownBy(() -> new FilePackByteStore(path)).isInstanceOf(FileAlreadyExistsException.class);
        assertThat(Files.readAllBytes(path)).containsExactly(1, 2, 3);
    }

    private static void writeAll(FilePackByteStore store, ByteBuffer source) throws Exception {
        while (source.hasRemaining()) {
            assertThat(store.write(source)).isPositive();
        }
    }
}
