package pro.deta.orion.git.parser.v2.storage.memory;

import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.git.parser.v2.read.GitPackRead;
import pro.deta.orion.git.parser.v2.storage.GitStorageApi;
import pro.deta.orion.git.parser.v2.storage.GitStorageAccess;
import pro.deta.orion.git.parser.v2.storage.shared.PackByteSource;
import pro.deta.orion.git.parser.v2.storage.shared.PackHandle;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static pro.deta.orion.git.parser.v2.storage.shared.PackSupport.closeUnreturned;

/** Owns pack bytes for one transient repository; writable handles borrow the underlying buffers. */
public final class InMemoryStorage implements GitStorageApi {
    private final Map<PackId, MemoryPackHandle> packs = new LinkedHashMap<>();
    private boolean closed;
    private int accesses;

    @Override
    public synchronized GitStorageAccess createAccess() throws IOException {
        if (closed) throw new ClosedChannelException();
        accesses++;
        return new Access();
    }

    @Override
    public synchronized void close() {
        closed = true;
        releaseBytes();
    }

    private void releaseBytes() {
        if (!closed || accesses != 0) return;
        for (MemoryPackHandle bytes : packs.values()) bytes.close();
        packs.clear();
    }

    private final class Access implements GitStorageAccess {
        private final List<Writer> writers = new ArrayList<>();
        private boolean closed;

        @Override
        public PackHandle newPack(PackId packId) throws IOException {
            synchronized (InMemoryStorage.this) {
                requireOpen();
                Objects.requireNonNull(packId, "packId");
                if (packs.containsKey(packId)) throw new IOException("Pack already exists: " + packId);
                MemoryPackHandle bytes = new MemoryPackHandle();
                packs.put(packId, bytes);
                Writer writer = new Writer(bytes);
                writers.add(writer);
                return writer;
            }
        }

        @Override
        public <R> R readPack(PackId packId, long offset, long length, GitPackRead<R> reader)
                throws IOException {
            Objects.requireNonNull(reader, "reader");
            MemoryPackHandle bytes;
            synchronized (InMemoryStorage.this) {
                requireOpen();
                bytes = packs.get(Objects.requireNonNull(packId, "packId"));
                if (bytes == null) throw new IOException("Missing stored pack: " + packId);
            }
            if (offset < 0 || length < 0 || offset > bytes.size() || length > bytes.size() - offset) {
                throw new EOFException("Invalid stored pack byte range");
            }
            R result = null;
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
        public boolean exists(PackId packId) throws IOException {
            synchronized (InMemoryStorage.this) {
                requireOpen();
                return packs.containsKey(Objects.requireNonNull(packId, "packId"));
            }
        }

        @Override
        public Set<PackId> packIds() throws IOException {
            synchronized (InMemoryStorage.this) {
                requireOpen();
                return Set.copyOf(packs.keySet());
            }
        }

        private void requireOpen() throws ClosedChannelException {
            if (closed) throw new ClosedChannelException();
        }

        @Override
        public void close() {
            synchronized (InMemoryStorage.this) {
                if (closed) return;
                closed = true;
                for (Writer writer : writers) writer.close();
                writers.clear();
                accesses--;
                releaseBytes();
            }
        }
    }

    private static final class Writer implements PackHandle {
        private final MemoryPackHandle bytes;
        private boolean closed;

        private Writer(MemoryPackHandle bytes) {
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
