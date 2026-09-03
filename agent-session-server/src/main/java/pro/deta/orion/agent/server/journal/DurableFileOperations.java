package pro.deta.orion.agent.server.journal;

import pro.deta.orion.agent.protocol.SessionEventRecord;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Uses identity reads around each no-follow channel open because portable NIO cannot obtain a file key from an
 * opened {@link FileChannel}. A mismatch closes the channel before use. An ABA replacement within the
 * provider's path-resolution boundary cannot be distinguished portably. Content verification streams through a
 * fixed-size buffer. Recovery performs exact full-content verification; cached appends retain immutable
 * fingerprints and use identity, size, and last-modified generation checks around one active-prefix scan.
 */
class DurableFileOperations {
    final DirectoryTree createDirectories(Path directory) throws IOException {
        List<Path> missing = new ArrayList<>();
        Path current = directory;
        while (current != null && isMissing(current)) {
            missing.add(current);
            current = current.getParent();
        }
        if (current == null) {
            throw new IOException("Could not find an existing parent directory");
        }
        requireDirectory(current);

        Collections.reverse(missing);
        List<DurableDirectory> created = new ArrayList<>(missing.size());
        for (Path path : missing) {
            boolean createdNow;
            try {
                Files.createDirectory(path);
                createdNow = true;
            } catch (FileAlreadyExistsException e) {
                requireDirectory(path);
                createdNow = false;
            }
            if (createdNow) {
                DurableDirectory captured = directory(path);
                created.add(captured);
                afterDirectoryIdentityCaptured(captured);
            }
        }

        List<DurableDirectory> chain = new ArrayList<>();
        for (Path path = directory; path != null; path = path.getParent()) {
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()) {
                break;
            }
            chain.add(directory(path, attributes));
        }
        if (chain.isEmpty()) {
            throw new IOException("The journal directory durability chain is empty");
        }
        DirectoryTree result = new DirectoryTree(chain, created);
        afterDirectoriesCreated(result);
        verifyDirectories(result);
        return result;
    }

    @TestOnly
    void afterDirectoryIdentityCaptured(DurableDirectory directory) throws IOException {
    }

    @TestOnly
    void afterDirectoriesCreated(DirectoryTree directories) throws IOException {
    }

    @TestOnly
    void contentBytesRead(Path path, int count) {
    }

    @TestOnly
    void retryLookupRecordRetained(SessionEventRecord record) {
    }

    @TestOnly
    void appendLockContended(Path sessionDirectory) {
    }

    @TestOnly
    void beforeContentRead(Path path) throws IOException {
    }

    @TestOnly
    void afterReadFailurePublished() {
    }

    @TestOnly
    void beforeCompressionTempForce(Path path) throws IOException {
    }

    @TestOnly
    void afterAppendFileCreated(Path path) throws IOException {
    }

    @TestOnly
    void beforeIdentityCapture(Path path) throws IOException {
    }

    @TestOnly
    void afterCompressionTempVerified(Path path) throws IOException {
    }

    @TestOnly
    void beforeCompressionPublication(Path temporary, Path target) throws IOException {
    }

    @TestOnly
    void afterCompressionPublished(Path path) throws IOException {
    }

    @TestOnly
    void beforeCompressionCatalogPublication() throws IOException {
    }

    final InputStream observeReads(Path path, InputStream input) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(input, "input");
        return new FilterInputStream(input) {
            @Override
            public int read() throws IOException {
                beforeContentRead(path);
                int value = super.read();
                if (value >= 0) {
                    contentBytesRead(path, 1);
                }
                return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                beforeContentRead(path);
                int read = super.read(bytes, offset, length);
                if (read > 0) {
                    contentBytesRead(path, read);
                }
                return read;
            }
        };
    }

    final AppendFile openAppend(
            Path path,
            boolean createNew,
            DurableDirectory parent) throws IOException {
        return openAppend(path, createNew, parent, ignored -> { });
    }

    final AppendFile openAppend(
            Path path,
            boolean createNew,
            DurableDirectory parent,
            IdentityObserver identityObserver) throws IOException {
        return openAppend(path, createNew, parent, () -> { }, identityObserver);
    }

    final AppendFile openAppend(
            Path path,
            boolean createNew,
            DurableDirectory parent,
            CreationObserver creationObserver,
            IdentityObserver identityObserver) throws IOException {
        Objects.requireNonNull(creationObserver, "creationObserver");
        Objects.requireNonNull(identityObserver, "identityObserver");
        FileChannel channel = null;
        try {
            verifyDirectory(parent);
            if (createNew) {
                Files.createFile(path);
                creationObserver.created();
                afterAppendFileCreated(path);
            }
            verifyDirectory(parent);
            SegmentCatalog.FileIdentity expected = regularFileIdentity(path);
            identityObserver.accept(expected);
            beforeAppendOpen(path);
            channel = openAppendChannel(path);
            afterAppendOpen(path, channel);
            SegmentCatalog.FileIdentity actual = regularFileIdentity(path);
            verifyDirectory(parent);
            if (!expected.equals(actual)) {
                throw new IOException("The append segment changed while its channel was opening");
            }
            return new AppendFile(channel, expected);
        } catch (IOException | RuntimeException e) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
            }
            throw e;
        }
    }

    @TestOnly
    void beforeAppendOpen(Path path) throws IOException {
    }

    @TestOnly
    FileChannel openAppendChannel(Path path) throws IOException {
        return FileChannel.open(path, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    @TestOnly
    void afterAppendOpen(Path path, FileChannel channel) throws IOException {
    }

    final byte[] verifyAppendedContent(
            Path path,
            SegmentCatalog.FileIdentity identity,
            long prefixLength,
            Optional<byte[]> expectedPrefix,
            FileGeneration expectedGeneration,
            List<byte[]> suffix) throws IOException {
        MessageDigest prefixDigest = sha256();
        MessageDigest digest = sha256();
        long expectedLength = prefixLength;
        for (byte[] bytes : suffix) {
            try {
                expectedLength = Math.addExact(expectedLength, bytes.length);
            } catch (ArithmeticException e) {
                throw new IOException("The appended journal segment length overflowed", e);
            }
        }
        if (expectedGeneration.size() != expectedLength) {
            throw new IOException("The appended journal segment has an unexpected final length");
        }

        verifyGeneration(path, identity, expectedGeneration);
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            verifyGeneration(path, identity, expectedGeneration);
            if (channel.size() != expectedLength) {
                throw new IOException("The appended journal segment changed length before verification");
            }
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            updateDigestsFromChannel(
                    path, channel, buffer, prefixLength, prefixDigest, digest);
            if (expectedPrefix.isPresent()
                    && !Arrays.equals(expectedPrefix.get(), prefixDigest.digest())) {
                throw new IOException("The active journal segment prefix changed during append");
            }
            for (byte[] expected : suffix) {
                verifyBytesFromChannel(path, channel, buffer, expected, digest);
            }
            if (channel.position() != expectedLength || channel.size() != expectedLength) {
                throw new IOException("The appended journal segment changed length during verification");
            }
            verifyGeneration(path, identity, expectedGeneration);
        }
        return digest.digest();
    }

    final RecoveryContent captureRecoveryContent(SegmentCatalog.Segment segment) throws IOException {
        return captureRecoveryContentInternal(segment);
    }

    final PhysicalFingerprint capturePhysicalFingerprint(
            Path path,
            SegmentCatalog.FileIdentity identity) throws IOException {
        FileGeneration expectedGeneration = generation(path, identity);
        MessageDigest digest = sha256();
        updateDigestFromFile(
                path,
                identity,
                expectedGeneration.size(),
                expectedGeneration,
                digest);
        byte[] physicalDigest = digest.digest();
        return new PhysicalFingerprint(
                expectedGeneration.size(), physicalDigest, expectedGeneration);
    }

    final void verifyMetadata(
            SegmentCatalog catalog,
            List<SegmentContent> expectedContents) throws IOException {
        if (catalog.segments().size() != expectedContents.size()) {
            throw new IOException("The journal segment metadata set changed before publication");
        }
        for (int index = 0; index < expectedContents.size(); index++) {
            SegmentCatalog.Segment segment = catalog.segments().get(index);
            SegmentContent expected = expectedContents.get(index);
            if (!segment.physicalPath().equals(expected.path())
                    || !segment.identity().equals(expected.identity())
                    || segment.completeByteLength() != expected.length()) {
                throw new IOException("A journal segment changed before publication");
            }
            if (segment.representation() == SegmentCatalog.Representation.UNCOMPRESSED
                    && expected.length() != expected.physical().length()) {
                throw new IOException("An uncompressed journal segment has an unexpected physical length");
            }
            verifyGeneration(expected);
        }
    }

    final void verifyRecoveryContents(
            SegmentCatalog catalog,
            List<RecoveryContent> expectedContents) throws IOException {
        if (catalog.segments().size() != expectedContents.size()) {
            throw new IOException("The recovered journal segment content set changed");
        }
        for (int index = 0; index < expectedContents.size(); index++) {
            SegmentCatalog.Segment segment = catalog.segments().get(index);
            RecoveryContent expected = expectedContents.get(index);
            SegmentContent content = expected.content();
            if (!segment.physicalPath().equals(content.path())
                    || !segment.identity().equals(content.identity())
                    || segment.completeByteLength() != content.length()
                    || segment.representation() != expected.representation()) {
                throw new IOException("A recovered journal segment changed before publication");
            }
            if (segment.representation() == SegmentCatalog.Representation.UNCOMPRESSED
                    && content.length() > content.physical().length()) {
                throw new IOException("A recovered journal segment is shorter than its complete prefix");
            }
            verifyDigest(content);
        }
    }

    final void verifyDigest(SegmentContent expected) throws IOException {
        MessageDigest digest = sha256();
        updateDigestFromFile(
                expected.path(),
                expected.identity(),
                expected.physical().length(),
                expected.physical().generation(),
                digest);
        if (!Arrays.equals(expected.physical().digest(), digest.digest())) {
            throw new IOException("A journal segment changed contents before publication");
        }
    }

    final void verifyMetadata(SegmentContent expected) throws IOException {
        verifyGeneration(expected);
    }

    final FileGeneration captureGeneration(
            Path path,
            SegmentCatalog.FileIdentity identity,
            long expectedSize) throws IOException {
        FileGeneration generation = generation(path, identity);
        if (generation.size() != expectedSize) {
            throw new IOException("A journal segment has an unexpected physical length");
        }
        return generation;
    }

    final SegmentCatalog.FileIdentity captureIdentity(Path path) throws IOException {
        return captureIdentityIfPresent(path).orElseThrow(
                () -> new NoSuchFileException(path.toString()));
    }

    final Optional<SegmentCatalog.FileIdentity> captureIdentityIfPresent(Path path)
            throws IOException {
        beforeIdentityCapture(path);
        try {
            return Optional.of(regularFileIdentity(path));
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
    }

    @TestOnly
    int write(FileChannel channel, ByteBuffer source) throws IOException {
        return channel.write(source);
    }

    @TestOnly
    void forceFile(FileChannel channel) throws IOException {
        channel.force(true);
    }

    final void forceFile(SegmentContent content) throws IOException {
        verifyGeneration(content);
        try (FileChannel channel = FileChannel.open(
                content.path(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            verifyGeneration(content);
            if (channel.size() != content.physical().length()) {
                throw new IOException("A recovered journal segment has an unexpected length");
            }
            forceFile(channel);
            if (channel.size() != content.physical().length()) {
                throw new IOException("A recovered journal segment changed length while being forced");
            }
            verifyGeneration(content);
        }
    }

    final void forceDirectory(DurableDirectory directory) throws IOException {
        verifyDirectory(directory);
        beforeDirectoryOpen(directory);
        try (FileChannel channel = openDirectoryChannel(directory.path())) {
            verifyDirectory(directory);
            forceDirectoryChannel(directory, channel);
            afterDirectoryForce(directory);
            verifyDirectory(directory);
        }
    }

    @TestOnly
    void beforeDirectoryOpen(DurableDirectory directory) throws IOException {
    }

    @TestOnly
    FileChannel openDirectoryChannel(Path directory) throws IOException {
        return FileChannel.open(
                directory, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    }

    @TestOnly
    void forceDirectoryChannel(DurableDirectory directory, FileChannel channel) throws IOException {
        channel.force(true);
    }

    @TestOnly
    void afterDirectoryForce(DurableDirectory directory) throws IOException {
    }

    final void verifyDirectories(DirectoryTree directories) throws IOException {
        for (DurableDirectory directory : directories.chain()) {
            verifyDirectory(directory);
        }
        for (DurableDirectory created : directories.created()) {
            DurableDirectory chained = findDirectory(directories.chain(), created.path());
            if (!created.identity().equals(chained.identity())) {
                throw new IOException("A created journal directory changed before its durability barrier");
            }
            verifyDirectory(created);
        }
    }

    final FileGeneration truncate(SegmentContent expected, long size) throws IOException {
        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative");
        }
        if (size > expected.physical().length()) {
            throw new IOException("A recovered journal segment cannot be extended during truncation");
        }
        verifyGeneration(expected);
        try (FileChannel channel = FileChannel.open(
                expected.path(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            verifyGeneration(expected);
            if (channel.size() != expected.physical().length()) {
                throw new IOException("A recovered journal segment changed while opening for truncation");
            }
            truncateChannel(channel, size);
            if (channel.size() != size) {
                throw new IOException("A recovered journal segment was not truncated to its complete prefix");
            }
        }
        FileGeneration after = generation(expected.path(), expected.identity());
        if (after.size() != size) {
            throw new IOException("A recovered journal segment changed after truncation");
        }
        return after;
    }

    @TestOnly
    void truncateChannel(FileChannel channel, long size) throws IOException {
        channel.truncate(size);
    }

    final void publishLink(Path source, Path target) throws IOException {
        Files.createLink(target, source);
    }

    @TestOnly
    boolean delete(CleanupToken expected) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = regularFileAttributes(expected.path());
        } catch (NoSuchFileException e) {
            return false;
        }
        SegmentCatalog.FileIdentity actual = new SegmentCatalog.FileIdentity(attributes.fileKey());
        if (!expected.identity().equals(actual)) {
            throw new IOException("Refusing to delete a journal entry whose identity changed");
        }
        Files.delete(expected.path());
        return true;
    }

    final boolean isPresent(CleanupToken expected) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = regularFileAttributes(expected.path());
        } catch (NoSuchFileException e) {
            return false;
        }
        SegmentCatalog.FileIdentity actual = new SegmentCatalog.FileIdentity(attributes.fileKey());
        if (!expected.identity().equals(actual)) {
            throw new IOException("A journal transition changed identity before cleanup retry");
        }
        return true;
    }

    final void verifyAbsent(Path path) throws IOException {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return;
        }
        throw new IOException("A cleaned journal transition reappeared");
    }

    @TestOnly
    void publishCatalog(Runnable publication) throws IOException {
        publication.run();
    }

    @FunctionalInterface
    interface CreationObserver {
        void created();
    }

    @FunctionalInterface
    interface IdentityObserver {
        void accept(SegmentCatalog.FileIdentity identity) throws IOException;
    }

    private void updateDigestsFromChannel(
            Path path,
            FileChannel channel,
            ByteBuffer buffer,
            long length,
            MessageDigest... digests) throws IOException {
        long remaining = length;
        while (remaining > 0) {
            buffer.clear();
            buffer.limit((int) Math.min(buffer.capacity(), remaining));
            int read = channel.read(buffer);
            if (read <= 0) {
                throw new IOException("Could not read the appended journal segment prefix");
            }
            contentBytesRead(path, read);
            buffer.flip();
            for (MessageDigest digest : digests) {
                digest.update(buffer.asReadOnlyBuffer());
            }
            remaining -= read;
        }
    }

    private void verifyBytesFromChannel(
            Path path,
            FileChannel channel,
            ByteBuffer buffer,
            byte[] expected,
            MessageDigest digest) throws IOException {
        int offset = 0;
        while (offset < expected.length) {
            buffer.clear();
            buffer.limit(Math.min(buffer.capacity(), expected.length - offset));
            int read = channel.read(buffer);
            if (read <= 0) {
                throw new IOException("Could not read the appended journal segment suffix");
            }
            contentBytesRead(path, read);
            buffer.flip();
            digest.update(buffer.asReadOnlyBuffer());
            for (int index = 0; index < read; index++) {
                if (buffer.get() != expected[offset + index]) {
                    throw new IOException("The appended journal segment suffix changed during append");
                }
            }
            offset += read;
        }
    }

    private void updateDigestFromFile(
            Path path,
            SegmentCatalog.FileIdentity identity,
            long length,
            FileGeneration expectedGeneration,
            MessageDigest... digests) throws IOException {
        verifyGeneration(path, identity, expectedGeneration);
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            verifyGeneration(path, identity, expectedGeneration);
            if (channel.size() != expectedGeneration.size() || length > channel.size()) {
                throw new IOException("A journal segment has an unexpected length during verification");
            }
            long remaining = length;
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            while (remaining > 0) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), remaining));
                int read = channel.read(buffer);
                if (read <= 0) {
                    throw new IOException("Could not read the expected journal segment bytes");
                }
                contentBytesRead(path, read);
                buffer.flip();
                for (MessageDigest digest : digests) {
                    digest.update(buffer.asReadOnlyBuffer());
                }
                remaining -= read;
            }
            if (channel.size() != expectedGeneration.size()) {
                throw new IOException("A journal segment changed length during verification");
            }
            verifyGeneration(path, identity, expectedGeneration);
        }
    }

    private RecoveryContent captureRecoveryContentInternal(SegmentCatalog.Segment segment)
            throws IOException {
        Path path = segment.physicalPath();
        SegmentCatalog.FileIdentity identity = segment.identity();
        long committedLength = segment.completeByteLength();
        MessageDigest committedDigest = sha256();
        MessageDigest physicalDigest = sha256();
        FileGeneration expectedGeneration = generation(path, identity);
        long physicalLength;
        try (FileChannel channel = FileChannel.open(
                path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            verifyGeneration(path, identity, expectedGeneration);
            physicalLength = channel.size();
            if (physicalLength < committedLength) {
                throw new IOException("A recovered journal segment is shorter than its complete prefix");
            }
            long remaining = physicalLength;
            long committedRemaining = committedLength;
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            while (remaining > 0) {
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), remaining));
                int read = channel.read(buffer);
                if (read <= 0) {
                    throw new IOException("Could not read recovered journal segment bytes");
                }
                contentBytesRead(path, read);
                buffer.flip();
                physicalDigest.update(buffer.asReadOnlyBuffer());
                if (committedRemaining > 0) {
                    ByteBuffer committedBytes = buffer.asReadOnlyBuffer();
                    int committedBytesRead = (int) Math.min(
                            committedBytes.remaining(), committedRemaining);
                    committedBytes.limit(committedBytesRead);
                    committedDigest.update(committedBytes);
                    committedRemaining -= committedBytesRead;
                }
                remaining -= read;
            }
            if (channel.size() != physicalLength) {
                throw new IOException("A recovered journal segment changed length during capture");
            }
            verifyGeneration(path, identity, expectedGeneration);
        }
        PhysicalFingerprint physical = new PhysicalFingerprint(
                physicalLength, physicalDigest.digest(), expectedGeneration);
        SegmentContent committed = new SegmentContent(
                path, identity, committedLength, committedDigest.digest(), physical);
        return new RecoveryContent(committed, SegmentCatalog.Representation.UNCOMPRESSED);
    }

    private static boolean isMissing(Path path) throws IOException {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return false;
        } catch (NoSuchFileException e) {
            return true;
        }
    }

    private static void requireDirectory(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory()) {
            throw new IOException("Journal directory path is not a directory: " + path);
        }
    }

    private static DurableDirectory directory(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return directory(path, attributes);
    }

    private static DurableDirectory directory(Path path, BasicFileAttributes attributes)
            throws IOException {
        if (!attributes.isDirectory() || attributes.fileKey() == null) {
            throw new IOException("Could not establish journal directory identity: " + path);
        }
        return new DurableDirectory(path, new SegmentCatalog.FileIdentity(attributes.fileKey()));
    }

    private static void verifyDirectory(DurableDirectory expected) throws IOException {
        DurableDirectory actual = directory(expected.path());
        if (!expected.identity().equals(actual.identity())) {
            throw new IOException("A journal directory changed during a durability operation");
        }
    }

    private static DurableDirectory findDirectory(
            List<DurableDirectory> directories,
            Path path) throws IOException {
        for (DurableDirectory directory : directories) {
            if (directory.path().equals(path)) {
                return directory;
            }
        }
        throw new IOException("A created journal directory is missing from the durability chain");
    }

    private static SegmentCatalog.FileIdentity regularFileIdentity(Path path) throws IOException {
        BasicFileAttributes attributes = regularFileAttributes(path);
        return new SegmentCatalog.FileIdentity(attributes.fileKey());
    }

    private static BasicFileAttributes regularFileAttributes(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.fileKey() == null) {
            throw new IOException("Could not establish append segment identity");
        }
        return attributes;
    }

    private static FileGeneration generation(
            Path path,
            SegmentCatalog.FileIdentity expected) throws IOException {
        BasicFileAttributes attributes = regularFileAttributes(path);
        SegmentCatalog.FileIdentity actual = new SegmentCatalog.FileIdentity(attributes.fileKey());
        if (!expected.equals(actual)) {
            throw new IOException("A journal segment changed during metadata verification");
        }
        return new FileGeneration(attributes.size(), attributes.lastModifiedTime());
    }

    private static void verifyGeneration(SegmentContent expected) throws IOException {
        verifyGeneration(expected.path(), expected.identity(), expected.physical().generation());
    }

    private static void verifyGeneration(
            Path path,
            SegmentCatalog.FileIdentity identity,
            FileGeneration expected) throws IOException {
        if (!expected.equals(generation(path, identity))) {
            throw new IOException("A journal segment generation changed during durability verification");
        }
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable for journal verification", e);
        }
    }

    record AppendFile(FileChannel channel, SegmentCatalog.FileIdentity identity)
            implements AutoCloseable {
        AppendFile {
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(identity, "identity");
        }

        @Override
        public void close() throws IOException {
            channel.close();
        }
    }

    record DurableDirectory(Path path, SegmentCatalog.FileIdentity identity) {
        DurableDirectory {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(identity, "identity");
        }
    }

    record SegmentContent(
            Path path,
            SegmentCatalog.FileIdentity identity,
            long length,
            byte[] digest,
            PhysicalFingerprint physical) {
        SegmentContent(
                Path path,
                SegmentCatalog.FileIdentity identity,
                long length,
                byte[] digest,
                FileGeneration generation) {
            this(path, identity, length, digest, new PhysicalFingerprint(length, digest, generation));
        }

        SegmentContent {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(identity, "identity");
            if (length < 0) {
                throw new IllegalArgumentException("length must be non-negative");
            }
            digest = Objects.requireNonNull(digest, "digest").clone();
            Objects.requireNonNull(physical, "physical");
        }

        @Override
        public byte[] digest() {
            return digest.clone();
        }
    }

    record PhysicalFingerprint(long length, byte[] digest, FileGeneration generation) {
        PhysicalFingerprint {
            if (length < 0) {
                throw new IllegalArgumentException("physical length must be non-negative");
            }
            digest = Objects.requireNonNull(digest, "digest").clone();
            Objects.requireNonNull(generation, "generation");
            if (length != generation.size()) {
                throw new IllegalArgumentException("physical length must match its generation");
            }
        }

        @Override
        public byte[] digest() {
            return digest.clone();
        }
    }

    record CleanupToken(Path path, SegmentCatalog.FileIdentity identity) {
        CleanupToken {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(identity, "identity");
        }
    }

    record FileGeneration(long size, FileTime lastModifiedTime) {
        FileGeneration {
            if (size < 0) {
                throw new IllegalArgumentException("size must be non-negative");
            }
            Objects.requireNonNull(lastModifiedTime, "lastModifiedTime");
        }
    }

    record RecoveryContent(
            SegmentContent content,
            SegmentCatalog.Representation representation) {
        RecoveryContent {
            Objects.requireNonNull(content, "content");
            Objects.requireNonNull(representation, "representation");
            if (representation == SegmentCatalog.Representation.UNCOMPRESSED
                    && content.length() > content.physical().length()) {
                throw new IllegalArgumentException("recovery content must describe one complete file prefix");
            }
        }
    }

    record DirectoryTree(
            List<DurableDirectory> chain,
            List<DurableDirectory> created) {
        DirectoryTree {
            chain = List.copyOf(Objects.requireNonNull(chain, "chain"));
            created = List.copyOf(Objects.requireNonNull(created, "created"));
        }

        DurableDirectory leaf() {
            return chain.getFirst();
        }

        boolean created(Path path) {
            for (DurableDirectory captured : created) {
                if (!captured.path().equals(path)) {
                    continue;
                }
                for (DurableDirectory chained : chain) {
                    if (chained.path().equals(path)) {
                        return captured.identity().equals(chained.identity());
                    }
                }
                return false;
            }
            return false;
        }
    }
}
