package pro.deta.orion.git.parser.v2.storage.local;

import pro.deta.orion.git.parser.v2.storage.shared.PackDataStorage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class FilePackDataStorage implements PackDataStorage {
    static PackDataStorage open(Path path, StandardOpenOption... options) throws IOException {
        return new FilePackDataStorage(FileChannel.open(path, options));
    }

    private final FileChannel channel;

    private FilePackDataStorage(FileChannel channel) {
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
