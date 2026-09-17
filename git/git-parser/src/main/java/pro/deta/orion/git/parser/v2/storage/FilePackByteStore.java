package pro.deta.orion.git.parser.v2.storage;

import pro.deta.orion.git.parser.v2.pack.PackByteStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Stores one upload's original pack bytes in a private file, with append writes and positional reads.
 * Storage-only completion may remove the received trailer and rewrite the object count before appending
 * a new checksum. These operations are not exposed through the parser's append-only PackByteStore contract.
 * Closing releases the channel; the upload's storage owner handles publication and staging-file deletion.
 */
final class FilePackByteStore implements PackByteStore {
    private final FileChannel channel;

    FilePackByteStore(Path path) throws IOException {
        channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    @Override
    public int write(ByteBuffer source) throws IOException {
        Objects.requireNonNull(source, "source");
        requireOpen();
        if (!source.hasRemaining()) {
            return 0;
        }
        int count = channel.write(source);
        if (count == 0) {
            throw new IOException("Pack file made no write progress");
        }
        return count;
    }

    @Override
    public int read(long offset, ByteBuffer destination) throws IOException {
        Objects.requireNonNull(destination, "destination");
        if (offset < 0) {
            throw new IllegalArgumentException("Pack offset must be non-negative");
        }
        if (destination.isReadOnly()) {
            throw new ReadOnlyBufferException();
        }
        requireOpen();
        if (!destination.hasRemaining()) {
            return 0;
        }
        int count = channel.read(destination, offset);
        if (count == 0) {
            throw new IOException("Pack file made no read progress");
        }
        return count;
    }

    @Override
    public void force() throws IOException {
        channel.force(true);
    }

    long size() throws IOException {
        return channel.size();
    }

    void truncate(long size) throws IOException {
        if (size < 0 || size > channel.size()) {
            throw new IllegalArgumentException("Invalid retained pack size");
        }
        channel.truncate(size);
        channel.position(size);
    }

    void rewrite(long offset, ByteBuffer source) throws IOException {
        if (offset < 0 || offset > channel.size() - source.remaining()) {
            throw new IllegalArgumentException("Rewrite must stay within retained pack bytes");
        }
        while (source.hasRemaining()) {
            int count = channel.write(source, offset);
            if (count == 0) {
                throw new IOException("Pack file made no rewrite progress");
            }
            offset += count;
        }
    }

    @Override
    public boolean isOpen() {
        return channel.isOpen();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    private void requireOpen() throws ClosedChannelException {
        if (!channel.isOpen()) {
            throw new ClosedChannelException();
        }
    }
}
