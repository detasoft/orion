package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;

final class PackByteSource implements BufferedByteInputV2.Source {
    private final PackDataStorage storage;
    private final ByteBuffer buffer = ByteBuffer.allocate(8192);
    private final long end;
    private long position;

    PackByteSource(PackDataStorage storage, long position, long end) {
        this.storage = Objects.requireNonNull(storage, "storage");
        if (position < 0 || end < position) {
            throw new IllegalArgumentException("Invalid pack byte range");
        }
        this.position = position;
        this.end = end;
    }

    @Override
    public ByteBuffer read() throws IOException {
        if (position == end) {
            return null;
        }
        buffer.clear().limit((int) Math.min(buffer.capacity(), end - position));
        int count = storage.read(position, buffer);
        if (count < 0) {
            throw new EOFException("Truncated stored object");
        }
        if (count == 0) {
            throw new IOException("Pack storage made no read progress");
        }
        position += count;
        return buffer.flip().asReadOnlyBuffer();
    }

    @Override
    public void release() {}

    @Override
    public void close() {}
}
