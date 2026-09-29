package pro.deta.orion.git.parser.v2.storage.shared;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface PackDataStorage extends AutoCloseable {
    int read(long offset, ByteBuffer target) throws IOException;

    void write(long offset, ByteBuffer source) throws IOException;

    long size() throws IOException;

    void truncate(long size) throws IOException;

    void flush() throws IOException;

    boolean isOpen();

    @Override
    void close() throws IOException;

}
