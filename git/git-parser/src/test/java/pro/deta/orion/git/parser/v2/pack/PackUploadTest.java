package pro.deta.orion.git.parser.v2.pack;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.read.ContentGitObjectRead;
import pro.deta.orion.git.parser.v2.read.HashedGitObjectRead;
import pro.deta.orion.git.parser.v2.read.PresenceGitObjectRead;
import pro.deta.orion.git.parser.v2.read.RawGitObjectRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.InputStreamBufferedByteInput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Random;
import java.util.zip.DeflaterOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PackUploadTest {
    @TempDir
    Path directory;

    @Test
    void iteratesAndIndexesObjectsBeforeReturningThemAndVerifiesThePackChecksum() throws Exception {
        byte[] first = blob(new byte[]{1, 2, 3});
        byte[] second = blob(new byte[0]);
        byte[] pack = pack(first, second);
        try (var attempt = attempt(join(pack, new byte[]{42}))) {
            var upload = attempt.upload;
            assertThat(upload.storage()).isSameAs(attempt.storage);
            assertThat(upload.index()).isSameAs(attempt.index);
            assertThatThrownBy(upload::packId).isInstanceOf(IllegalStateException.class);
            assertThat(upload.hasNext()).isTrue();
            int available = attempt.source.available();
            assertThat(upload.hasNext()).isTrue();
            assertThat(attempt.source.available()).isEqualTo(available);
            var firstResult = upload.next();
            assertThat(firstResult.entry().offset()).isEqualTo(12);
            assertThat(firstResult.value()).isPresent();
            assertThat(attempt.index.find(firstResult.value().orElseThrow())).contains(firstResult.entry());
            var secondResult = upload.next();
            assertThat(secondResult.entry().offset()).isEqualTo(12 + first.length);
            assertThat(secondResult.value().orElseThrow().toHex())
                    .isEqualTo("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391");
            assertThat(attempt.index.hasUnresolved()).isFalse();
            assertThatThrownBy(upload::packId).isInstanceOf(IllegalStateException.class);
            assertThat(upload.hasNext()).isFalse();
            assertThat(upload.packId()).isEqualTo(new PackId(Arrays.copyOfRange(pack, pack.length - 20,
                    pack.length)));
            assertThat(upload.hasNext()).isFalse();
            assertThatThrownBy(upload::next).isInstanceOf(NoSuchElementException.class);
            assertThat(attempt.store.bytes()).containsExactly(pack);
            assertThat(attempt.source.readUnsignedByte()).isEqualTo(42);
            assertThat(attempt.store.isOpen()).isTrue();
        }
    }

    @Test
    void verifiesAnEmptyPackAndCanStartIterationThroughNext() throws Exception {
        byte[] empty = pack();
        try (var attempt = attempt(join(empty, new byte[]{42}))) {
            assertThatThrownBy(attempt.upload::next).isInstanceOf(NoSuchElementException.class);
            assertThat(attempt.upload.hasNext()).isFalse();
            assertThat(attempt.upload.packId()).isEqualTo(new PackId(Arrays.copyOfRange(empty, 12, 32)));
            assertThat(attempt.store.bytes()).containsExactly(empty);
            assertThat(attempt.source.readUnsignedByte()).isEqualTo(42);
        }
        try (var attempt = attempt(pack(blob(new byte[]{1})))) {
            assertThat(attempt.upload.next().value()).isPresent();
            assertThat(attempt.upload.hasNext()).isFalse();
        }
    }

    @Test
    void keepsBothDeltaTypesUnresolvedAndRereadsTheirOriginalInstructions() throws Exception {
        byte[] base = blob(new byte[]{1, 2, 3});
        byte[] instructions = {3, 3, (byte) 0x90, 3};
        ObjectId ref = new ObjectId("1".repeat(40));
        byte[] ofsDelta = join(new byte[]{0x64, (byte) base.length}, compressed(instructions));
        byte[] refDelta = join(new byte[]{0x74}, ref.toBytes(), compressed(instructions));
        try (var attempt = attempt(pack(base, ofsDelta, refDelta))) {
            var upload = attempt.upload;
            upload.next();
            var ofs = upload.next();
            var reference = upload.next();
            assertThat(ofs.value()).isEmpty();
            assertThat(reference.value()).isEmpty();
            assertThat(ofs.entry().baseOffset()).hasValue(12);
            assertThat(reference.entry().baseId()).contains(ref);
            assertThat(attempt.index.find(ofs.entry().offset())).contains(ofs.entry());
            assertThat(attempt.index.hasUnresolved()).isTrue();
            assertThat(upload.hasNext()).isFalse();
            for (var result : List.of(ofs, reference)) {
                assertThat(upload.readObject(result.entry().offset(), new ContentGitObjectRead<byte[]>(
                        (type, size, baseId, source) -> {
                            assertThat(type).isEqualTo(result.entry().type());
                            assertThat(baseId).isEqualTo(result.entry().baseId());
                            assertThat(size).isEqualTo(instructions.length);
                            return readAll(source);
                        }))).containsExactly(instructions);
            }
            assertThatThrownBy(() -> upload.commit(upload.packId()))
                    .isInstanceOf(IOException.class).hasMessageContaining("unresolved");
        }
    }

    @Test
    void positionalReadsDoNotAdvanceTransportOrAppendBytesOrChangeTheChecksum() throws Exception {
        byte[] content = new byte[100_000];
        new Random(73).nextBytes(content);
        byte[] pack = pack(blob(content), blob(new byte[]{9}));
        try (var attempt = attempt(join(pack, new byte[]{42}))) {
            var upload = attempt.upload;
            var first = upload.next();
            int available = attempt.source.available();
            byte[] retained = attempt.store.bytes();
            assertThat(upload.readObject(12, new HashedGitObjectRead())).isEqualTo(first.value().orElseThrow());
            assertThat(upload.readObject(12, new RawGitObjectRead<byte[]>((type, size, baseId, raw) -> readAll(raw))))
                    .containsExactly(compressed(content));
            var contentReader = new ContentGitObjectRead<byte[]>((type, size, baseId, data) -> readAll(data));
            assertThat(upload.readObject(12, contentReader)).containsExactly(content);
            assertThat(attempt.source.available()).isEqualTo(available);
            assertThat(attempt.store.bytes()).containsExactly(retained);
            assertThat(attempt.store.forceCalls).isZero();
            upload.next();
            assertThat(upload.hasNext()).isFalse();
            assertThat(upload.readObject(12, new PresenceGitObjectRead())).isTrue();
            assertThat(attempt.source.readUnsignedByte()).isEqualTo(42);
            assertThat(attempt.store.bytes()).containsExactly(pack);
        }
    }

    @Test
    void rejectsNegativeAndNotYetEncounteredEntryOffsets() throws Exception {
        try (var attempt = attempt(pack(blob(new byte[]{1})))) {
            for (long offset : new long[]{-1, 0, 12, Long.MAX_VALUE}) {
                assertThatThrownBy(() -> attempt.upload.readObject(offset, new PresenceGitObjectRead()))
                        .isInstanceOf(IllegalArgumentException.class);
            }
            attempt.upload.next();
            assertThatThrownBy(() -> attempt.upload.readObject(13, new PresenceGitObjectRead()))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsBadHeadersAndTreatsTheCountAsUnsigned() throws Exception {
        byte[] wrongMagic = pack();
        wrongMagic[0] = 'X';
        byte[] wrongVersion = pack();
        wrongVersion[7] = 3;
        for (byte[] invalid : new byte[][]{wrongMagic, wrongVersion, new byte[11]}) {
            try (var attempt = attempt(invalid)) {
                assertThatThrownBy(attempt.upload::hasNext).isInstanceOf(IOException.class);
                assertStopped(attempt);
            }
        }
        byte[] header = ByteBuffer.allocate(12).putInt(0x5041434b).putInt(2).putInt(-1).array();
        try (var attempt = attempt(header)) {
            assertThat(attempt.upload.hasNext()).isTrue();
            assertThatThrownBy(attempt.upload::next).isInstanceOf(IOException.class);
            assertStopped(attempt);
        }
    }

    @Test
    void rejectsTruncatedObjectsAndTrailersInsteadOfReportingExhaustion() throws Exception {
        byte[] bytes = pack(blob(new byte[]{1, 2, 3}));
        for (int length : new int[]{12, 13, bytes.length - 21, bytes.length - 20, bytes.length - 1}) {
            try (var attempt = attempt(Arrays.copyOf(bytes, length))) {
                assertThatThrownBy(() -> {
                    while (attempt.upload.hasNext()) {
                        attempt.upload.next();
                    }
                }).isInstanceOf(IOException.class);
                assertStopped(attempt);
            }
        }
    }

    @Test
    void rejectsChecksumMismatchAndCannotResumeOnTheFollowingPack() throws Exception {
        byte[] bytes = pack(blob(new byte[]{1, 2, 3}));
        bytes[bytes.length - 1] ^= 1;
        try (var attempt = attempt(join(bytes, pack()))) {
            attempt.upload.next();
            assertThatThrownBy(attempt.upload::hasNext)
                    .isInstanceOf(IOException.class).hasMessageContaining("checksum");
            assertStopped(attempt);
        }
    }

    @Test
    void stopsTheAttemptAfterEntryOrObjectIndexRegistrationFails() throws Exception {
        for (boolean completing : new boolean[]{false, true}) {
            try (var attempt = attempt(pack(blob(new byte[]{1})))) {
                attempt.index.failEntry = !completing;
                attempt.index.failObject = completing;
                assertThatThrownBy(attempt.upload::next).isInstanceOf(IOException.class)
                        .hasMessage("index failure");
                attempt.index.failEntry = false;
                attempt.index.failObject = false;
                assertStopped(attempt);
            }
        }
    }

    @Test
    void stopsTheAttemptAfterRetainingHeaderEntryOrTrailerFails() throws Exception {
        byte[] bytes = pack(blob(new byte[]{1, 2, 3}));
        for (long failAt : new long[]{0, 14, bytes.length - 10}) {
            try (var attempt = attempt(bytes)) {
                attempt.store.failAt = failAt;
                assertThatThrownBy(() -> {
                    while (attempt.upload.hasNext()) {
                        attempt.upload.next();
                    }
                }).isInstanceOf(IOException.class).hasMessage("store failure");
                attempt.store.failAt = Long.MAX_VALUE;
                assertStopped(attempt);
            }
        }
    }

    @Test
    void closesAnUnreturnedReadResultWhenRetainedDataIsCorrupt() throws Exception {
        try (var attempt = attempt(pack(blob(new byte[]{1, 2, 3})))) {
            var entry = attempt.upload.next().entry();
            attempt.store.channel.write(ByteBuffer.wrap(new byte[]{0}), entry.dataOffset());
            boolean[] closed = {false};
            assertThatThrownBy(() -> attempt.upload.readObject(12, (type, size, baseId, raw) ->
                    (AutoCloseable) () -> closed[0] = true)).isInstanceOf(IOException.class);
            assertThat(closed[0]).isTrue();
            assertThat(attempt.store.isOpen()).isTrue();
            assertStopped(attempt);
        }
    }

    @Test
    void blocksCompletionWhenStoredReadsFailAfterChecksumVerification() throws Exception {
        try (var attempt = attempt(pack(blob(new byte[]{1, 2, 3})))) {
            attempt.upload.next();
            assertThat(attempt.upload.hasNext()).isFalse();
            assertThat(attempt.upload.packId()).isNotNull();
            attempt.store.failReads = true;
            assertThatThrownBy(() -> attempt.upload.readObject(12, new PresenceGitObjectRead()))
                    .isInstanceOf(IOException.class).hasMessage("store read failure");
            assertStopped(attempt);
        }
    }

    @Test
    void stopsOnIndexLookupFailureWithoutConsumingMoreTransportInput() throws Exception {
        try (var attempt = attempt(pack(blob(new byte[]{1, 2, 3})))) {
            attempt.upload.next();
            attempt.index.failFind = true;
            assertThatThrownBy(() -> attempt.upload.readObject(12, new PresenceGitObjectRead()))
                    .isInstanceOf(IOException.class).hasMessage("index lookup failure");
            attempt.index.failFind = false;
            assertStopped(attempt);
        }
    }

    private static void assertStopped(Attempt attempt) {
        int available = attempt.source.available();
        assertThatThrownBy(attempt.upload::hasNext).isInstanceOf(IOException.class);
        assertThatThrownBy(attempt.upload::next).isInstanceOf(IOException.class);
        assertThatThrownBy(attempt.upload::packId).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> attempt.upload.commit(new PackId(new byte[20])))
                .isInstanceOf(IOException.class).hasMessageContaining("cannot continue");
        assertThat(attempt.source.available()).isEqualTo(available);
    }

    private Attempt attempt(byte[] bytes) throws IOException {
        return new Attempt(bytes, Files.createTempFile(directory, "pack-", ".pack"));
    }

    private static byte[] blob(byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int size = content.length;
        int part = 0x30 | (size & 15);
        size >>>= 4;
        output.write(part | (size == 0 ? 0 : 128));
        while (size != 0) {
            part = size & 127;
            size >>>= 7;
            output.write(part | (size == 0 ? 0 : 128));
        }
        output.writeBytes(compressed(content));
        return output.toByteArray();
    }

    private static byte[] compressed(byte[] content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (var compressor = new DeflaterOutputStream(output)) {
            compressor.write(content);
        }
        return output.toByteArray();
    }

    private static byte[] pack(byte[]... entries) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes(ByteBuffer.allocate(12).putInt(0x5041434b).putInt(2).putInt(entries.length).array());
        for (byte[] entry : entries) {
            output.writeBytes(entry);
        }
        output.writeBytes(MessageDigest.getInstance("SHA-1").digest(output.toByteArray()));
        return output.toByteArray();
    }

    private static byte[] join(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static byte[] join(byte[] first, byte[] second, byte[] third) {
        return join(join(first, second), third);
    }

    private static byte[] readAll(BufferedByteInput source) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteBuf buffer = Unpooled.buffer(4096, 4096);
        try {
            int count;
            while ((count = source.readInto(buffer, buffer.writableBytes())) != 0) {
                output.write(buffer.array(), buffer.arrayOffset(), count);
                buffer.clear();
            }
            return output.toByteArray();
        } finally {
            buffer.release();
        }
    }

    private static final class Attempt implements AutoCloseable {
        private final InputStreamBufferedByteInput source;
        private final FileStore store;
        private final RecordingIndex index = new RecordingIndex();
        private final GitStorageApi storage = new GitStorageApi();
        private final PackUpload upload;

        private Attempt(byte[] bytes, Path path) throws IOException {
            source = new InputStreamBufferedByteInput(new ByteArrayInputStream(bytes));
            store = new FileStore(path);
            upload = new PackUpload(storage, source, store, index);
        }

        @Override
        public void close() throws IOException {
            try (source) {
                store.close();
            }
        }
    }

    private static final class FileStore implements PackByteStore {
        private final Path path;
        private final FileChannel channel;
        private long failAt = Long.MAX_VALUE;
        private int forceCalls;
        private boolean failReads;

        private FileStore(Path path) throws IOException {
            this.path = path;
            channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        }

        private byte[] bytes() throws IOException {
            return Files.readAllBytes(path);
        }

        @Override
        public int write(ByteBuffer source) throws IOException {
            if (channel.position() >= failAt) {
                throw new IOException("store failure");
            }
            int limit = source.limit();
            source.limit(source.position() + Math.min(7, source.remaining()));
            try {
                return channel.write(source);
            } finally {
                source.limit(limit);
            }
        }

        @Override
        public int read(long offset, ByteBuffer destination) throws IOException {
            if (failReads) {
                throw new IOException("store read failure");
            }
            int limit = destination.limit();
            destination.limit(destination.position() + Math.min(11, destination.remaining()));
            try {
                return channel.read(destination, offset);
            } finally {
                destination.limit(limit);
            }
        }

        @Override
        public void force() throws IOException {
            forceCalls++;
            channel.force(true);
        }

        @Override
        public boolean isOpen() {
            return channel.isOpen();
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    private static final class RecordingIndex implements PackIndex {
        private final Map<Long, PackObjectParser.Entry> entries = new HashMap<>();
        private final Map<ObjectId, PackObjectParser.Entry> objects = new HashMap<>();
        private boolean failEntry;
        private boolean failObject;
        private boolean failFind;

        @Override
        public void addEntry(PackObjectParser.Entry entry) throws IOException {
            if (failEntry) {
                throw new IOException("index failure");
            }
            entries.put(entry.offset(), entry);
        }

        @Override
        public void addObject(PackObjectParser.Entry entry, ObjectId id, ObjectType type, long size)
                throws IOException {
            if (failObject) {
                throw new IOException("index failure");
            }
            assertThat(entries.get(entry.offset())).isEqualTo(entry);
            assertThat(type).isEqualTo(entry.type());
            assertThat(size).isEqualTo(entry.inflatedSize());
            objects.put(id, entry);
        }

        @Override
        public Optional<PackObjectParser.Entry> find(ObjectId id) {
            return Optional.ofNullable(objects.get(id));
        }

        @Override
        public Optional<PackObjectParser.Entry> find(long offset) throws IOException {
            if (failFind) {
                throw new IOException("index lookup failure");
            }
            return Optional.ofNullable(entries.get(offset));
        }

        @Override
        public Optional<PackObjectParser.Entry> waitingFor(ObjectId id, long offset) {
            throw new UnsupportedOperationException("Resolution is outside this test fixture");
        }

        @Override
        public boolean hasUnresolved() {
            return entries.size() != objects.size();
        }

        @Override
        public Optional<ObjectId> nextExternalBase() {
            throw new UnsupportedOperationException("Pack completion is outside this test fixture");
        }
    }
}
