package pro.deta.orion.git.parser.v2.pack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.NavigableMap;
import java.util.TreeMap;

interface PackDataStorage extends AutoCloseable {
    int read(long offset, ByteBuffer target) throws IOException;

    void write(long offset, ByteBuffer source) throws IOException;

    long size() throws IOException;

    void truncate(long size) throws IOException;

    void flush() throws IOException;

    boolean isOpen();

    @Override
    void close() throws IOException;

    static PackDataStorage open(Path path, StandardOpenOption... options) throws IOException {
        return new FileStorage(FileChannel.open(path, options));
    }

    static PackDataStorage memory() {
        return new MemoryStorage();
    }

    final class FileStorage implements PackDataStorage {
        private final FileChannel channel;

        private FileStorage(FileChannel channel) {
            this.channel = channel;
        }

        @Override
        public int read(long offset, ByteBuffer target) throws IOException {
            return channel.read(target, offset);
        }

        @Override
        public void write(long offset, ByteBuffer source) throws IOException {
            while (source.hasRemaining()) {
                int count = channel.write(source, offset);
                if (count <= 0) {
                    throw new IOException("Pack file write made no progress");
                }
                offset += count;
            }
        }

        @Override
        public long size() throws IOException {
            return channel.size();
        }

        @Override
        public void truncate(long size) throws IOException {
            channel.truncate(size);
        }

        @Override
        public void flush() throws IOException {
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

    final class MemoryStorage implements PackDataStorage {
        private static final int BLOCK_SIZE = 8192;
        private static final byte[] ZERO_BLOCK = new byte[BLOCK_SIZE];
        private final NavigableMap<Long, byte[]> blocks = new TreeMap<>();
        private long size;
        private boolean closed;

        @Override
        public int read(long offset, ByteBuffer target) throws IOException {
            requireOpen();
            if (offset < 0) {
                throw new IllegalArgumentException("Negative pack offset");
            }
            if (!target.hasRemaining()) {
                return 0;
            }
            if (offset >= size) {
                return -1;
            }
            int count = (int) Math.min(target.remaining(), size - offset);
            int remaining = count;
            while (remaining > 0) {
                int start = (int) (offset % BLOCK_SIZE);
                int length = Math.min(remaining, BLOCK_SIZE - start);
                target.put(blocks.getOrDefault(offset / BLOCK_SIZE, ZERO_BLOCK), start, length);
                offset += length;
                remaining -= length;
            }
            return count;
        }

        @Override
        public void write(long offset, ByteBuffer source) throws IOException {
            requireOpen();
            if (offset < 0) {
                throw new IllegalArgumentException("Negative pack offset");
            }
            if (source.remaining() > Long.MAX_VALUE - offset) {
                throw new IOException("Pack size overflows a signed long");
            }
            while (source.hasRemaining()) {
                int start = (int) (offset % BLOCK_SIZE);
                int length = Math.min(source.remaining(), BLOCK_SIZE - start);
                byte[] block = blocks.computeIfAbsent(offset / BLOCK_SIZE, unused -> new byte[BLOCK_SIZE]);
                source.get(block, start, length);
                offset += length;
                size = Math.max(size, offset);
            }
        }

        @Override
        public long size() throws IOException {
            requireOpen();
            return size;
        }

        @Override
        public void truncate(long length) throws IOException {
            requireOpen();
            if (length < 0) {
                throw new IllegalArgumentException("Negative pack size");
            }
            if (length >= size) {
                return;
            }
            long blockIndex = length / BLOCK_SIZE;
            int remainder = (int) (length % BLOCK_SIZE);
            blocks.tailMap(blockIndex + (remainder == 0 ? 0 : 1), true).clear();
            byte[] last = blocks.get(blockIndex);
            if (remainder != 0 && last != null) {
                Arrays.fill(last, remainder, BLOCK_SIZE, (byte) 0);
            }
            size = length;
        }

        @Override
        public void flush() throws IOException {
            requireOpen();
        }

        @Override
        public boolean isOpen() {
            return !closed;
        }

        @Override
        public void close() {
            closed = true;
            blocks.clear();
            size = 0;
        }

        private void requireOpen() throws ClosedChannelException {
            if (closed) {
                throw new ClosedChannelException();
            }
        }
    }
}
