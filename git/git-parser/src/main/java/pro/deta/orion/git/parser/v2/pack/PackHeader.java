package pro.deta.orion.git.parser.v2.pack;

import java.io.IOException;
import java.nio.ByteBuffer;

final class PackHeader {
    static final int SIZE = 12;
    private static final int MAGIC = 0x5041434b;

    private PackHeader() {}

    static long read(byte[] bytes) throws IOException {
        if (bytes.length != SIZE) {
            throw new IOException("Truncated pack header");
        }
        ByteBuffer header = ByteBuffer.wrap(bytes);
        if (header.getInt() != MAGIC) {
            throw new IOException("Invalid pack magic bytes");
        }
        int version = header.getInt();
        if (version != 2 && version != 3) {
            throw new IOException("Unsupported pack version: " + version);
        }
        return Integer.toUnsignedLong(header.getInt());
    }

    static byte[] write(long objectCount) {
        if (objectCount < 0 || objectCount > 0xffff_ffffL) {
            throw new IllegalArgumentException("Invalid pack object count");
        }
        return ByteBuffer.allocate(SIZE).putInt(MAGIC).putInt(2).putInt((int) objectCount).array();
    }
}
