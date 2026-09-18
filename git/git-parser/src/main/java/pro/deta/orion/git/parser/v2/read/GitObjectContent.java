package pro.deta.orion.git.parser.v2.read;

import pro.deta.orion.git.parser.v2.data.GitObjectType;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface GitObjectContent extends AutoCloseable {
    GitObjectType type();

    long size();

    int read(long offset, ByteBuffer destination) throws IOException;

    @Override
    void close() throws IOException;
}
