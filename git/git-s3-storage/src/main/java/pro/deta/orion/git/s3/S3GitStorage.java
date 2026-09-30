package pro.deta.orion.git.s3;

import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.read.GitPackRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageAccess;
import pro.deta.orion.git.parser.v2.storage.shared.PackHandle;
import pro.deta.orion.git.parser.v2.storage.shared.PackByteSource;
import pro.deta.orion.net.io.BufferedByteInputV2;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.CompletedPart;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static pro.deta.orion.git.parser.v2.storage.shared.PackSupport.closeUnreturned;

/**
 * Storage access with temporary random-access pack staging. Flush persists bytes independently of the
 * index; closing handles uploads remaining changes and removes scratch files, never stored S3 objects.
 */
final class S3GitStorage implements GitStorageAccess {
    private final S3GitStorageApi owner;
    private final S3RepositoryObjects objects;
    private final List<Writer> owned = new ArrayList<>();
    private boolean closed;

    S3GitStorage(S3GitStorageApi owner) {
        this.owner = owner;
        this.objects = owner.objects;
    }

    private static String key(PackId id) { return "packs/" + Objects.requireNonNull(id, "packId") + ".data"; }

    @Override
    public PackHandle newPack(PackId id) throws IOException {
        requireOpen();
        if (exists(id)) throw new IOException("Pack already exists: " + id);
        Writer writer = new Writer(id);
        synchronized (owner) {
            if (owner.writers.putIfAbsent(id, writer) != null) {
                IOException failure = new IOException("Pack already exists: " + id);
                try { writer.cleanup(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            owned.add(writer);
        }
        return writer;
    }

    @Override
    public <R> R readPack(PackId id, long offset, long length, GitPackRead<R> reader) throws IOException {
        requireOpen();
        Objects.requireNonNull(reader, "reader");
        Writer writer;
        synchronized (owner) { writer = owner.writers.get(id); }
        if (writer != null) {
            validateRange(offset, length, writer.size());
            return read(reader, length, new BufferedByteInputV2(new PackByteSource(writer, offset, offset + length)));
        }
        if (offset < 0 || length < 0 || length > Long.MAX_VALUE - offset) {
            throw new EOFException("Invalid stored pack byte range");
        }
        return objects.operation(() -> {
            if (length == 0) {
                long size = objects.transport().client().headObject(request -> request
                        .overrideConfiguration(objects.overrides()).bucket(objects.bucket())
                        .key(objects.prefix() + key(id))).contentLength();
                validateRange(offset, length, size);
                return read(reader, 0, new BufferedByteInputV2(new ByteArrayInputStream(new byte[0])));
            }
            R result = null;
            try (ResponseInputStream<GetObjectResponse> stream = objects.transport().client().getObject(request ->
                    request.overrideConfiguration(objects.overrides()).bucket(objects.bucket())
                            .key(objects.prefix() + key(id)).range("bytes=" + offset + "-" + (offset + length - 1)))) {
                RangeSource source = new RangeSource(stream, length);
                try {
                    GetObjectResponse response = stream.response();
                    String expected = "bytes " + offset + "-" + (offset + length - 1) + "/";
                    if (response.contentLength() != length || response.contentRange() == null
                            || !response.contentRange().startsWith(expected)) {
                        throw new EOFException("Invalid S3 pack byte range response");
                    }
                    result = read(reader, length, new BufferedByteInputV2(source));
                    return result;
                } finally {
                    if (source.remaining > 0) stream.abort();
                }
            } catch (IOException | RuntimeException | Error failure) {
                closeUnreturned(result, failure);
                throw failure;
            }
        });
    }

    private static <R> R read(GitPackRead<R> reader, long length, BufferedByteInputV2 input) throws IOException {
        R result = null;
        try (input) {
            result = Objects.requireNonNull(reader.read(length, input), "reader result");
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            closeUnreturned(result, failure);
            throw failure;
        }
    }

    private static void validateRange(long offset, long length, long size) throws IOException {
        if (offset < 0 || length < 0 || offset > size || length > size - offset) {
            throw new EOFException("Invalid stored pack byte range");
        }
    }

    @Override
    public boolean exists(PackId id) throws IOException {
        requireOpen();
        synchronized (owner) {
            if (owner.writers.containsKey(id)) return true;
        }
        return objects.list(key(id)).contains(key(id));
    }

    private void requireOpen() throws ClosedChannelException {
        if (closed) throw new ClosedChannelException();
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        for (Writer writer : owned) {
            try {
                writer.close();
            } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        owned.clear();
        if (failure != null) throw failure;
    }

    final class Writer implements PackHandle {
        private final PackId id;
        private final Path path;
        private final FileChannel channel;
        private String etag;
        private boolean dirty = true;

        Writer(PackId id) throws IOException {
            this.id = id;
            path = Files.createTempFile("orion-s3-pack-", ".data");
            try {
                channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            } catch (IOException | RuntimeException | Error failure) {
                try { Files.deleteIfExists(path); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        public int read(long offset, ByteBuffer target) throws IOException { return channel.read(target, offset); }

        public void write(long offset, ByteBuffer source) throws IOException {
            if (offset < 0) throw new IllegalArgumentException("Negative pack offset");
            if (source.remaining() > Long.MAX_VALUE - offset) throw new IOException("Pack size overflows a signed long");
            while (source.hasRemaining()) {
                int count = channel.write(source, offset);
                if (count <= 0) throw new IOException("Pack file write made no progress");
                dirty = true;
                offset += count;
            }
        }

        public long size() throws IOException { return channel.size(); }

        public void truncate(long size) throws IOException {
            long previous = channel.size();
            channel.truncate(size);
            if (size < previous) dirty = true;
        }

        public void flush() throws IOException {
            if (!isOpen()) throw new ClosedChannelException();
            if (!dirty) return;
            channel.force(false);
            etag = objects.operation(() -> upload());
            dirty = false;
        }

        private String upload() throws IOException {
            long size = channel.size();
            if (size <= 64L * 1024 * 1024) {
                return objects.transport().client().putObject(request -> request
                        .overrideConfiguration(objects.overrides()).bucket(objects.bucket())
                        .key(objects.prefix() + key(id)).ifMatch(etag).ifNoneMatch(etag == null ? "*" : null),
                        RequestBody.fromFile(path)).eTag();
            }
            String uploadId = objects.transport().client().createMultipartUpload(request -> request
                    .overrideConfiguration(objects.overrides()).bucket(objects.bucket())
                    .key(objects.prefix() + key(id))).uploadId();
            try {
                List<CompletedPart> parts = new ArrayList<>();
                long partSize = Math.max(64L * 1024 * 1024, (size + 9999) / 10000);
                for (long offset = 0; offset < size; offset += partSize) {
                    long start = offset;
                    long length = Math.min(partSize, size - offset);
                    int number = parts.size() + 1;
                    String partEtag = objects.transport().client().uploadPart(request -> request
                            .overrideConfiguration(objects.overrides()).bucket(objects.bucket())
                            .key(objects.prefix() + key(id)).uploadId(uploadId).partNumber(number),
                            RequestBody.fromContentProvider(() -> fileRange(start, length), length,
                                    "application/octet-stream")).eTag();
                    parts.add(CompletedPart.builder().partNumber(number).eTag(partEtag).build());
                }
                return objects.transport().client().completeMultipartUpload(request -> request
                        .overrideConfiguration(objects.overrides()).bucket(objects.bucket())
                        .key(objects.prefix() + key(id)).uploadId(uploadId)
                        .ifMatch(etag).ifNoneMatch(etag == null ? "*" : null)
                        .multipartUpload(upload -> upload.parts(parts))).eTag();
            } catch (RuntimeException | Error failure) {
                try {
                    objects.transport().client().abortMultipartUpload(request -> request
                            .overrideConfiguration(objects.overrides()).bucket(objects.bucket())
                            .key(objects.prefix() + key(id)).uploadId(uploadId));
                } catch (RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        private InputStream fileRange(long offset, long length) {
            try {
                InputStream input = Files.newInputStream(path);
                try {
                    input.skipNBytes(offset);
                    return new FilterInputStream(input) {
                        private long remaining = length;

                        public int read() throws IOException {
                            if (remaining == 0) return -1;
                            int value = super.read();
                            if (value < 0) throw new EOFException("Truncated staged pack");
                            remaining--;
                            return value;
                        }

                        public int read(byte[] bytes, int start, int count) throws IOException {
                            if (count == 0) return 0;
                            if (remaining == 0) return -1;
                            int read = in.read(bytes, start, (int) Math.min(count, remaining));
                            if (read < 0) throw new EOFException("Truncated staged pack");
                            remaining -= read;
                            return read;
                        }
                    };
                } catch (IOException | RuntimeException | Error failure) {
                    try { input.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                    throw failure;
                }
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        public boolean isOpen() { return channel.isOpen(); }

        public void close() throws IOException {
            if (!isOpen()) return;
            try {
                flush();
            } catch (IOException | RuntimeException | Error failure) {
                try { cleanup(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            } finally {
                synchronized (owner) { owner.writers.remove(id, this); }
            }
            cleanup();
        }

        private void cleanup() throws IOException {
            IOException failure = null;
            try {
                channel.close();
            } catch (IOException error) {
                failure = error;
            }
            try {
                Files.deleteIfExists(path);
            } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
            if (failure != null) throw failure;
        }
    }
    private static final class RangeSource implements BufferedByteInputV2.Source {
        private final InputStream stream;
        private final byte[] bytes = new byte[8192];
        private long remaining;

        private RangeSource(InputStream stream, long length) {
            this.stream = stream;
            this.remaining = length;
        }

        @Override
        public ByteBuffer read() throws IOException {
            if (remaining == 0) return null;
            int count = stream.read(bytes, 0, (int) Math.min(bytes.length, remaining));
            if (count < 0) throw new EOFException("Truncated S3 pack range");
            if (count == 0) throw new IOException("S3 pack read made no progress");
            remaining -= count;
            return ByteBuffer.wrap(bytes, 0, count).asReadOnlyBuffer();
        }

        public void release() {}
        public void close() {}
    }

}
