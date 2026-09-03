package pro.deta.orion.agent.server.journal;

import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Streams one closed segment through a crash-safe temporary replacement and publishes the verified result. */
final class SegmentCompressor {
    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    private final SegmentReader reader;
    private final DurableFileOperations operations;

    SegmentCompressor(SegmentReader reader, DurableFileOperations operations) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    void compress(
            SessionJournal journal,
            SessionJournal.CompressionTarget target,
            BooleanSupplier accepting) throws IOException, JournalStorageException {
        Path source = target.segment().physicalPath();
        Path compressed = source.resolveSibling(source.getFileName() + ".zst");
        Path temporary = compressed.resolveSibling(compressed.getFileName() + ".tmp");
        DurableFileOperations.DirectoryTree directories = operations.createDirectories(source.getParent());
        requireDirectory(target.catalog(), directories.leaf());

        SegmentReader.CompressedSegment verified = createReplacement(
                journal, target, compressed, temporary, directories, accepting);
        if (verified == null || !accepting.getAsBoolean()) {
            return;
        }

        operations.forceDirectory(directories.leaf());
        operations.afterCompressionPublished(compressed);
        if (!accepting.getAsBoolean()) {
            return;
        }
        operations.verifyDigest(target.content());
        operations.verifyDigest(verified.content());
        journal.publishCompression(target, verified, accepting);
    }

    private SegmentReader.CompressedSegment createReplacement(
            SessionJournal journal,
            SessionJournal.CompressionTarget target,
            Path compressed,
            Path temporary,
            DurableFileOperations.DirectoryTree directories,
            BooleanSupplier accepting) throws IOException, JournalStorageException {
        if (!accepting.getAsBoolean()) {
            return null;
        }
        DurableFileOperations.CleanupToken temporaryCleanup = streamToTemporary(
                journal, target.content(), temporary, directories.leaf());
        operations.forceDirectory(directories.leaf());
        reader.validateCompressedReplacement(
                target.catalog(), target.segment(), target.content(), temporary);
        operations.afterCompressionTempVerified(temporary);
        if (!accepting.getAsBoolean()) {
            return null;
        }
        DurableFileOperations.CleanupToken publishedCleanup =
                new DurableFileOperations.CleanupToken(compressed, temporaryCleanup.identity());
        journal.reserveAbsentTransition(publishedCleanup);
        try {
            operations.beforeCompressionPublication(temporary, compressed);
            journal.publishReservedLink(temporary, publishedCleanup);
            operations.forceDirectory(directories.leaf());
            SegmentReader.CompressedSegment published = reader.validateCompressedReplacement(
                    target.catalog(), target.segment(), target.content(), compressed);
            journal.cleanupTransition(temporaryCleanup, directories.leaf());
            return published;
        } catch (IOException | JournalStorageException e) {
            if (Files.notExists(compressed, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    journal.cancelAbsentTransition(publishedCleanup);
                } catch (IOException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            throw e;
        }
    }

    private DurableFileOperations.CleanupToken streamToTemporary(
            SessionJournal journal,
            DurableFileOperations.SegmentContent source,
            Path temporary,
            DurableFileOperations.DurableDirectory directory)
            throws IOException, JournalStorageException {
        operations.verifyDigest(source);
        SessionJournal.RegisteredTemporary registered = journal.createTemporary(temporary, directory);
        try (FileChannel inputChannel = FileChannel.open(
                source.path(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                DurableFileOperations.AppendFile temporaryFile = registered.file()) {
            InputStream input = operations.observeReads(
                    source.path(), Channels.newInputStream(inputChannel));
            OutputStream channelOutput = new FilterOutputStream(
                    Channels.newOutputStream(temporaryFile.channel())) {
                @Override
                public void close() throws IOException {
                    flush();
                }
            };
            try (ZstdCompressorOutputStream output = new ZstdCompressorOutputStream(channelOutput)) {
                copyExactly(input, output, source.length());
            }
            operations.beforeCompressionTempForce(temporary);
            operations.forceFile(temporaryFile.channel());
            operations.verifyDigest(source);
            return registered.cleanup();
        }
    }

    private static void copyExactly(InputStream input, OutputStream output, long length)
            throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long remaining = length;
        while (remaining > 0) {
            int requested = (int) Math.min(buffer.length, remaining);
            int read = input.read(buffer, 0, requested);
            if (read < 0) {
                throw new IOException("Closed journal segment became shorter during compression");
            }
            if (read == 0) {
                continue;
            }
            output.write(buffer, 0, read);
            remaining -= read;
        }
        if (input.read() != -1) {
            throw new IOException("Closed journal segment became longer during compression");
        }
    }

    private static void requireDirectory(
            SegmentCatalog catalog,
            DurableFileOperations.DurableDirectory actual) throws IOException {
        if (catalog.sessionDirectoryIdentity().isEmpty()
                || !catalog.sessionDirectoryIdentity().get().equals(actual.identity())) {
            throw new IOException("Session journal changed before compression");
        }
    }
}
