package pro.deta.orion.git.parser.v2.pack;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.read.GitObjectRead;
import pro.deta.orion.git.parser.v2.read.ResolvedGitObjectRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.net.io.BufferedByteInput;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.zip.DeflaterOutputStream;

public final class GitPackObjectResolver {
    private final IndexedPack pack;
    private final GitStorageApi storage;

    public GitPackObjectResolver(IndexedPack pack, GitStorageApi storage) {
        this.pack = Objects.requireNonNull(pack, "pack");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    public void attemptResolve(IndexedPack.EntryMetadata entry, Optional<ObjectId> objectId) throws IOException {
        throw new UnsupportedOperationException("Pack object resolution is not implemented");
    }

    public <R> Optional<R> getObject(ObjectId objectId, GitObjectRead<R> reader) throws IOException {
        throw new UnsupportedOperationException("Resolved object lookup is not implemented");
    }

    public PackId complete() throws IOException {
        PackId receivedId = pack.id();
        try (PackUploadIndex index = PackUploadIndex.create(pack)) {
            return complete(pack, index, storage, receivedId);
        }
    }

    private static PackId complete(IndexedPack bytes, PackUploadIndex index,
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
            if (objectCount != bytes.entryCount()) {
                throw new IOException("Pack object count does not match its index");
            }
            if (objectCount != bytes.objectCount()) {
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
                    IndexedPack.EntryMetadata entry = storage.readObject(base, new ResolvedGitObjectRead<>(storage,
                            (type, length, unused, content) -> appendBase(bytes, base, type, length, content)))
                            .orElseThrow(() -> new IOException("Missing external base: " + base));
                    index.addEntry(entry);
                    index.addObject(entry, base, entry.type(), entry.inflatedSize());
                    objectCount++;
                    missing = index.nextExternalBase();
                }
                ByteBuffer count = ByteBuffer.allocate(4).putInt((int) objectCount).flip();
                bytes.write(8, count);
                byte[] completedChecksum = digest(bytes, bytes.size());
                bytes.append(ByteBuffer.wrap(completedChecksum));
                finalId = new PackId(completedChecksum);
            }
            index.finish();
            return finalId;
        } catch (IOException | RuntimeException | Error failure) {
            closeFailed(index, failure);
            closeFailed(bytes, failure);
            throw failure;
        }
    }

    private static IndexedPack.EntryMetadata appendBase(IndexedPack bytes, ObjectId expected,
            GitObjectType type, long size, BufferedByteInput content) throws IOException {
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
        bytes.append(ByteBuffer.wrap(PackWriter.objectHeader(type, size)));
        long dataOffset = bytes.size();
        MessageDigest hash = sha1();
        hash.update((name + " " + size + "\0").getBytes(StandardCharsets.US_ASCII));
        ByteBuf buffer = Unpooled.buffer(8192, 8192);
        try (DeflaterOutputStream zlib = new DeflaterOutputStream(new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                write(new byte[]{(byte) value}, 0, 1);
            }

            @Override
            public void write(byte[] data, int start, int count) throws IOException {
                bytes.append(ByteBuffer.wrap(data, start, count));
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
        return new IndexedPack.EntryMetadata(offset, dataOffset, size, type, OptionalLong.empty(), Optional.empty());
    }

    private static byte[] readExactly(IndexedPack bytes, long offset, int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            int count = bytes.read(offset, buffer);
            if (count < 0) {
                throw new EOFException("Truncated pack file");
            }
            if (count == 0) {
                throw new IOException("Pack file read made no progress");
            }
            offset += count;
        }
        return buffer.array();
    }

    private static byte[] digest(IndexedPack bytes, long length) throws IOException {
        MessageDigest hash = sha1();
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        long position = 0;
        while (position < length) {
            buffer.clear().limit((int) Math.min(buffer.capacity(), length - position));
            int count = bytes.read(position, buffer);
            if (count < 0) {
                throw new EOFException("Truncated pack file during checksum calculation");
            }
            if (count == 0) {
                throw new IOException("Pack file read made no progress");
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

    static void closeFailed(AutoCloseable resource, Throwable failure) {
        try {
            resource.close();
        } catch (Throwable cleanup) {
            if (cleanup != failure) {
                failure.addSuppressed(cleanup);
            }
        }
    }
}
