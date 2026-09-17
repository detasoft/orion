package pro.deta.orion.git.parser.v2.storage;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import pro.deta.orion.git.parser.v2.data.ObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.pack.PackObjectParser;
import pro.deta.orion.git.parser.v2.read.ResolvedGitObjectRead;
import pro.deta.orion.net.io.BufferedByteInput;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.zip.DeflaterOutputStream;

/**
 * Stores pack bytes and indexes internally behind GitStorageApi; files and paths stay inside storage.
 * Object resolution and operation-specific policy belong to callers. uploadNewPack creates PackUpload with
 * the owning GitStorageApi, caller-owned input, a PackByteStore, and an empty storage-provided PackIndex.
 * Static PackObjectParser methods consume entries into that sink with bounded buffers. Upload accumulates
 * the pack checksum incrementally, excluding the trailer from the digest. Only bytes through that trailer
 * are retained; later protocol bytes remain available through the caller's source.
 * PackIndex accumulates provisional metadata, resolved ObjectIds, candidate bases, and waiting dependencies
 * directly in storage. It need not reside entirely in memory or share the byte store's backing format.
 * Each upload owns a PackByteStore: parsing borrows its combined append and positional-read interface.
 * upload.readObject(offset, reader) opens a bounded source from that store and invokes the reader. Raw readers
 * process compressed bytes; content readers inflate them before invoking the consumer. The upload owns the
 * invocation source; independently owned result resources belong to the caller. The planned implementation
 * uses a private FileChannel behind PackByteStore, with no public file handles or additional memory tier.
 * The upload owns its parsing state, sink, index, commit, and rollback without a public upload ID. Rollback releases
 * resources without closing source input or discarding a completed publication or another attempt's data.
 *
 * <p>All publishers of one repository share an internal GitLock. Acquire ownership for the verified PackId,
 * then check durable publication metadata and reuse an existing publication or publish this pack and index.
 * Before publication, require completed parsing and index.hasUnresolved() == false. Finish pending byte and
 * index writes before publishing their durable association. The populated index stays in storage; no complete
 * collection must be transferred at commit. Waiting lookup structures can be discarded after publication.
 * Release ownership after publication writes and cleanup. Different pack IDs publish independently; a waiter
 * rechecks the manifest rather than assuming the preceding attempt succeeded. Retry after an uncertain I/O
 * outcome also checks the manifest. Complete publications survive recovery; incomplete staging stays invisible.
 *
 * <p>Only published packs contribute to object lookup and outgoing pack selection.
 * Before publication, missing delta bases are appended as full objects and registered in the index.
 * Updating the pack header and checksum yields its final PackId. Published packs are self-contained;
 * candidate bases and waiting dependencies are temporary state. Ref rejection never undoes an already
 * committed publication.
 *
 * <p>Preliminary methods:
 * <ul>
 *   <li>{@code uploadNewPack(source)} - create an upload with byte storage and an empty PackIndex.</li>
 *   <li>{@code commit(...)} - internally publish the completed self-contained pack and its index.</li>
 *   <li>{@code publishedPacks()} - list metadata of published packs.</li>
 * </ul>
 * Pack completion is implemented: it verifies the received file, appends each missing base as a full object,
 * verifies its canonical ObjectId, updates the header, and rehashes the completed file with bounded buffers.
 * Original entry offsets remain unchanged. An unchanged self-contained pack retains its received PackId.
 * Success forces bytes and finalizes the index; failure closes both handles, leaving permanent staging
 * cleanup to the upload owner. No partially completed attempt may be resumed or published.
 * Base restoration uses ResolvedGitObjectRead and inherits its current REF_DELTA-only resolution and
 * in-memory base-size limits. Publishing, repository lookup, and upload lifecycle wiring remain pending.
 */
final class GitPackStorage {
    static PackId complete(FilePackByteStore bytes, FilePackIndex index,
                           GitStorageApi storage, PackId receivedId) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(receivedId, "receivedId");
        try {
            if (index.hasUnresolved()) {
                throw new IOException("Pack contains unresolved objects");
            }
            long size = bytes.size();
            if (size < 32) {
                throw new IOException("Truncated received pack");
            }
            ByteBuffer header = ByteBuffer.wrap(readExactly(bytes, 0, 12));
            if (header.getInt() != 0x5041434b || header.getInt() != 2) {
                throw new IOException("Invalid received pack header");
            }
            long objectCount = Integer.toUnsignedLong(header.getInt());
            if (objectCount != index.entryCount()) {
                throw new IOException("Pack object count does not match its index");
            }
            if (objectCount != index.objectCount()) {
                throw new IOException("Pack contains duplicate objects");
            }
            byte[] checksum = digest(bytes, size - 20);
            if (!MessageDigest.isEqual(checksum, receivedId.toBytes())
                    || !MessageDigest.isEqual(checksum, readExactly(bytes, size - 20, 20))) {
                throw new IOException("Received pack checksum mismatch");
            }
            Optional<ObjectId> missing = index.nextExternalBase();
            PackId finalId = receivedId;
            if (missing.isPresent()) {
                bytes.truncate(size - 20);
                while (missing.isPresent()) {
                    if (objectCount == 0xffff_ffffL) {
                        throw new IOException("Completed pack exceeds the object count limit");
                    }
                    ObjectId base = missing.orElseThrow();
                    var entry = storage.readObject(base, new ResolvedGitObjectRead<>(storage,
                            (type, length, unused, content) -> appendBase(bytes, base, type, length, content)))
                            .orElseThrow(() -> new IOException("Missing external base: " + base));
                    index.addEntry(entry);
                    index.addObject(entry, base, entry.type(), entry.inflatedSize());
                    objectCount++;
                    missing = index.nextExternalBase();
                }
                bytes.rewrite(8, ByteBuffer.allocate(4).putInt((int) objectCount).flip());
                byte[] completedChecksum = digest(bytes, bytes.size());
                append(bytes, ByteBuffer.wrap(completedChecksum));
                finalId = new PackId(completedChecksum);
            }
            bytes.force();
            index.finish();
            return finalId;
        } catch (IOException | RuntimeException | Error failure) {
            closeFailed(index, failure);
            closeFailed(bytes, failure);
            throw failure;
        }
    }

    private static PackObjectParser.Entry appendBase(FilePackByteStore bytes, ObjectId expected,
            ObjectType type, long size, BufferedByteInput content) throws IOException {
        String name = switch (type) {
            case COMMIT -> "commit";
            case TREE -> "tree";
            case BLOB -> "blob";
            case TAG -> "tag";
            case OFS_DELTA, REF_DELTA -> throw new IOException("Pack completion requires restored base content");
        };
        if (size < 0) {
            throw new IOException("Negative base object size");
        }
        long offset = bytes.size();
        append(bytes, ByteBuffer.wrap(objectHeader(type, size)));
        long dataOffset = bytes.size();
        MessageDigest hash = sha1();
        hash.update((name + " " + size + "\0").getBytes(StandardCharsets.US_ASCII));
        ByteBuf buffer = Unpooled.buffer(8192, 8192);
        try (var zlib = new DeflaterOutputStream(new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                write(new byte[]{(byte) value}, 0, 1);
            }

            @Override
            public void write(byte[] data, int start, int count) throws IOException {
                append(bytes, ByteBuffer.wrap(data, start, count));
            }
        })) {
            long remaining = size;
            int count;
            while ((count = content.readInto(buffer, buffer.writableBytes())) != 0) {
                if (count > remaining) {
                    throw new IOException("Base content exceeds its declared size");
                }
                hash.update(buffer.array(), buffer.arrayOffset() + buffer.readerIndex(), count);
                zlib.write(buffer.array(), buffer.arrayOffset() + buffer.readerIndex(), count);
                remaining -= count;
                buffer.clear();
            }
            if (remaining != 0) {
                throw new EOFException("Truncated base content");
            }
            if (!MessageDigest.isEqual(hash.digest(), expected.toBytes())) {
                throw new IOException("External base ObjectId does not match its content");
            }
        } finally {
            buffer.release();
        }
        return new PackObjectParser.Entry(offset, dataOffset, size, type, OptionalLong.empty(), Optional.empty());
    }

    private static byte[] objectHeader(ObjectType type, long size) {
        byte[] header = new byte[10];
        int count = 0;
        int part = type.code() << 4 | (int) (size & 15);
        size >>>= 4;
        while (size != 0) {
            header[count++] = (byte) (part | 128);
            part = (int) (size & 127);
            size >>>= 7;
        }
        header[count++] = (byte) part;
        return Arrays.copyOf(header, count);
    }

    private static void append(FilePackByteStore bytes, ByteBuffer source) throws IOException {
        while (source.hasRemaining()) {
            bytes.write(source);
        }
    }

    private static byte[] readExactly(FilePackByteStore bytes, long offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            int count = bytes.read(offset, buffer);
            if (count < 0) {
                throw new EOFException("Truncated pack file");
            }
            offset += count;
        }
        return buffer.array();
    }

    private static byte[] digest(FilePackByteStore bytes, long length) throws IOException {
        MessageDigest hash = sha1();
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        long position = 0;
        while (position < length) {
            buffer.clear().limit((int) Math.min(buffer.capacity(), length - position));
            int count = bytes.read(position, buffer);
            if (count < 0) {
                throw new EOFException("Truncated pack file during checksum calculation");
            }
            hash.update(buffer.array(), 0, count);
            position += count;
        }
        return hash.digest();
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-1 is required for Git packs", error);
        }
    }

    private static void closeFailed(AutoCloseable resource, Throwable failure) {
        try {
            resource.close();
        } catch (Throwable cleanup) {
            if (cleanup != failure) {
                failure.addSuppressed(cleanup);
            }
        }
    }
}
