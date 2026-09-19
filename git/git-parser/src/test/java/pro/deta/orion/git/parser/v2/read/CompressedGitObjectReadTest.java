package pro.deta.orion.git.parser.v2.read;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.junit.jupiter.api.Test;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Random;
import java.util.Optional;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompressedGitObjectReadTest {
    @Test
    void drainsAfterAnEarlyReturnWithoutClosingTheBorrowedSourceOrReturnedResource() throws Exception {
        byte[] content = new byte[100_000];
        content[0] = 42;
        byte[] zlib = compressed(content);
        boolean[] closed = {false};
        AutoCloseable resource = () -> closed[0] = true;
        var reader = new ContentGitObjectRead<>((type, size, baseId, input) -> {
            assertThat(type).isEqualTo(GitObjectType.BLOB);
            assertThat(size).isEqualTo(content.length);
            assertThat(input.readUnsignedByte()).isEqualTo(42);
            return resource;
        });
        try (var source = new BorrowedSource(zlib)) {
            assertThat(reader.read(GitObjectType.BLOB, content.length, Optional.empty(), source)).isSameAs(resource);
            assertThat(source.closed).isFalse();
            assertThat(source.available()).isZero();
            assertThat(closed[0]).isFalse();
        }
    }

    @Test
    void rejectsWrongLengthsTruncationAndCorruptionAfterTheConsumerReturns() throws Exception {
        byte[] zlib = compressed(new byte[]{1, 2, 3});
        byte[] corrupt = zlib.clone();
        corrupt[corrupt.length - 1] ^= 1;
        for (byte[] bytes : new byte[][]{zlib, Arrays.copyOf(zlib, zlib.length - 1), corrupt}) {
            boolean[] closed = {false};
            var reader = new ContentGitObjectRead<>((type, size, baseId, input) -> (AutoCloseable) () -> {
                closed[0] = true;
                throw new IOException("cleanup failure");
            });
            try (var source = new BorrowedSource(bytes)) {
                assertThatThrownBy(() -> reader.read(GitObjectType.BLOB, 4, Optional.empty(), source))
                        .isInstanceOf(IOException.class)
                        .satisfies(error -> assertThat(error.getSuppressed()).hasSize(1));
                assertThat(closed[0]).isTrue();
                assertThat(source.closed).isFalse();
            }
        }
    }

    @Test
    void rejectsExcessInflatedDataAndBytesAfterTheBoundedZlibStream() throws Exception {
        byte[] zlib = compressed(new byte[]{1, 2, 3});
        var reader = new ContentGitObjectRead<>((type, size, baseId, input) -> Boolean.TRUE);
        try (var source = new BorrowedSource(zlib)) {
            assertThatThrownBy(() -> reader.read(GitObjectType.BLOB, 2, Optional.empty(), source)).isInstanceOf(IOException.class);
        }
        try (var source = new BorrowedSource(Arrays.copyOf(zlib, zlib.length + 1))) {
            assertThatThrownBy(() -> reader.read(GitObjectType.BLOB, 3, Optional.empty(), source)).isInstanceOf(IOException.class);
        }
    }

    @Test
    void neverHashesDeltaInstructionsAsObjects() throws Exception {
        for (GitObjectType type : new GitObjectType[]{GitObjectType.OFS_DELTA, GitObjectType.REF_DELTA}) {
            try (var source = new BorrowedSource(compressed(new byte[]{1, 2, 3}))) {
                assertThatThrownBy(() -> new HashedGitObjectRead().read(type, 3, Optional.empty(), source))
                        .isInstanceOf(IOException.class);
            }
        }
    }

    @Test
    void propagatesConsumerFailureAndReusesTheProcessorAfterFailure() throws Exception {
        IOException expected = new IOException("consumer failure");
        int[] calls = {0};
        var reader = new ContentGitObjectRead<>((type, size, baseId, input) -> {
            if (calls[0]++ == 0) {
                throw expected;
            }
            return input.readUnsignedByte();
        });
        try (var first = new BorrowedSource(compressed(new byte[]{42}));
             var second = new BorrowedSource(compressed(new byte[]{43}))) {
            assertThatThrownBy(() -> reader.read(GitObjectType.BLOB, 1, Optional.empty(), first)).isSameAs(expected);
            assertThat(reader.read(GitObjectType.BLOB, 1, Optional.empty(), second)).isEqualTo(43);
            assertThat(first.closed).isFalse();
            assertThat(second.closed).isFalse();
        }
    }

    @Test
    void hashesLargeContentFromFragmentedCompressedInput() throws Exception {
        byte[] content = new byte[200_000];
        new Random(73).nextBytes(content);
        MessageDigest hash = MessageDigest.getInstance("SHA-1");
        hash.update(("blob " + content.length + "\0").getBytes(StandardCharsets.US_ASCII));
        try (var source = new BorrowedSource(compressed(content))) {
            source.chunkSize = 3;
            assertThat(new HashedGitObjectRead().read(GitObjectType.BLOB, content.length, Optional.empty(), source))
                    .isEqualTo(new ObjectId(hash.digest(content)));
        }
    }

    @Test
    void rejectsPresetDictionaries() throws Exception {
        Deflater deflater = new Deflater();
        try {
            deflater.setDictionary(new byte[]{1, 2, 3});
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (var compressor = new DeflaterOutputStream(output, deflater)) {
                compressor.write(new byte[]{1, 2, 3});
            }
            try (var source = new BorrowedSource(output.toByteArray())) {
                var reader = new ContentGitObjectRead<>((type, size, baseId, input) -> Boolean.TRUE);
                assertThatThrownBy(() -> reader.read(GitObjectType.BLOB, 3, Optional.empty(), source))
                        .isInstanceOf(IOException.class).hasMessageContaining("dictionary");
            }
        } finally {
            deflater.end();
        }
    }

    @Test
    void supportsExactReadsAndEmptyReadsAtExhaustion() throws Exception {
        byte[] content = {1, 2, 3};
        try (var source = new BorrowedSource(compressed(content))) {
            var reader = new ContentGitObjectRead<>((type, size, baseId, input) -> {
                ByteBuf bytes = input.readCopy(3, ByteBufAllocator.DEFAULT);
                try {
                    assertThat(bytes.readUnsignedByte()).isEqualTo((short) 1);
                    assertThat(bytes.readUnsignedByte()).isEqualTo((short) 2);
                    assertThat(bytes.readUnsignedByte()).isEqualTo((short) 3);
                    bytes.clear();
                    assertThat(input.readBytes(0)).isEmpty();
                    assertThat(input.readInto(bytes, 0)).isZero();
                    assertThat(input.readInto(bytes, 3)).isZero();
                    assertThat(input.readInto(bytes, 3)).isZero();
                } finally {
                    bytes.release();
                }
                return Boolean.TRUE;
            });
            assertThat(reader.read(GitObjectType.BLOB, 3, Optional.empty(), source)).isTrue();
        }
    }

    private static byte[] compressed(byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var compressor = new DeflaterOutputStream(output)) {
            compressor.write(content);
        }
        return output.toByteArray();
    }

    private static final class BorrowedSource implements BufferedByteInput, AutoCloseable {
        private final InputStreamBufferedByteInput input;
        private boolean closed;
        private int chunkSize = Integer.MAX_VALUE;

        private BorrowedSource(byte[] bytes) {
            input = new InputStreamBufferedByteInput(new ByteArrayInputStream(bytes));
        }

        @Override
        public int available() {
            return input.available();
        }

        @Override
        public int readUnsignedByte() throws IOException {
            return input.readUnsignedByte();
        }

        @Override
        public ByteBuf readCopy(int length, ByteBufAllocator allocator) throws IOException {
            return input.readCopy(length, allocator);
        }

        @Override
        public int readInto(ByteBuf target, int maxLength) throws IOException {
            return input.readInto(target, Math.min(chunkSize, maxLength));
        }

        @Override
        public void close() throws IOException {
            closed = true;
            input.close();
        }
    }
}
