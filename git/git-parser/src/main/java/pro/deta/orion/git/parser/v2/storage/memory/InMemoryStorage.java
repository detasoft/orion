package pro.deta.orion.git.parser.v2.storage.memory;

import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.read.GitPackRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.storage.shared.PackByteSource;
import pro.deta.orion.git.parser.v2.storage.shared.PackDataStorage;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import static pro.deta.orion.git.parser.v2.storage.shared.PackSupport.closeUnreturned;

/** Owns pack bytes for one transient repository; writable handles borrow the underlying buffers. */
public final class InMemoryStorage implements GitStorageApi {
    private final Map<PackId, MemoryPackDataStorage> packs = new LinkedHashMap<>();
    private boolean closed;

    @Override
    public synchronized PackDataStorage newPack(PackId packId) throws IOException {
        requireOpen();
        Objects.requireNonNull(packId, "packId");
        if (packs.containsKey(packId)) {
            throw new IOException("Pack already exists: " + packId);
        }
        MemoryPackDataStorage bytes = new MemoryPackDataStorage();
        packs.put(packId, bytes);
        return new Writer(bytes);
    }

    private synchronized MemoryPackDataStorage pack(PackId id) throws IOException {
        requireOpen();
        MemoryPackDataStorage bytes = packs.get(Objects.requireNonNull(id, "packId"));
        if (bytes == null) {
            throw new IOException("Missing stored pack: " + id);
        }
        return bytes;
    }

    @Override
    public <R> R readPack(PackId packId, long offset, long length, GitPackRead<R> reader) throws IOException {
        Objects.requireNonNull(reader, "reader");
        R result = null;
        MemoryPackDataStorage bytes = pack(packId);
        if (offset < 0 || length < 0 || offset > bytes.size() || length > bytes.size() - offset) {
            throw new EOFException("Invalid stored pack byte range");
        }
        try (BufferedByteInputV2 input = new BufferedByteInputV2(
                new PackByteSource(bytes, offset, offset + length))) {
            result = Objects.requireNonNull(reader.read(length, input), "reader result");
            return result;
        } catch (IOException | RuntimeException | Error failure) {
            closeUnreturned(result, failure);
            throw failure;
        }
    }

    @Override
    public synchronized boolean exists(PackId packId) throws IOException {
        requireOpen();
        return packs.containsKey(Objects.requireNonNull(packId, "packId"));
    }

    private void requireOpen() throws ClosedChannelException {
        if (closed) {
            throw new ClosedChannelException();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (MemoryPackDataStorage bytes : packs.values()) {
            bytes.close();
        }
        packs.clear();
    }

    private static final class Writer implements PackDataStorage {
        private final MemoryPackDataStorage bytes;
        private boolean closed;

        private Writer(MemoryPackDataStorage bytes) {
            this.bytes = bytes;
        }

        public int read(long offset, ByteBuffer target) throws IOException {
            requireOpen();
            return bytes.read(offset, target);
        }

        public void write(long offset, ByteBuffer source) throws IOException {
            requireOpen();
            bytes.write(offset, source);
        }

        public long size() throws IOException {
            requireOpen();
            return bytes.size();
        }

        public void truncate(long size) throws IOException {
            requireOpen();
            bytes.truncate(size);
        }

        public void flush() throws IOException {
            requireOpen();
        }

        public boolean isOpen() {
            return !closed && bytes.isOpen();
        }

        public void close() {
            closed = true;
        }

        private void requireOpen() throws ClosedChannelException {
            if (!isOpen()) {
                throw new ClosedChannelException();
            }
        }
    }
}
