package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.zip.Deflater;

@FunctionalInterface
interface PackEntryWriter {
    void write(byte[] bytes, int offset, int length) throws IOException;

    default int writeObject(GitObjectType type, long size, BufferedByteInputV2 content,
                            Deflater deflater, byte[] compressed, Consumer<ByteBuffer> observer) throws IOException {
        Objects.requireNonNull(type, "type");
        if (size < 0 || type == GitObjectType.OFS_DELTA || type == GitObjectType.REF_DELTA) {
            throw new IllegalArgumentException("Invalid full pack entry");
        }
        byte[] header = objectHeader(type, size);
        write(header, 0, header.length);
        deflater.reset();
        long remaining = size;
        ByteBuffer buffer;
        while ((buffer = content.buffer()) != null) {
            int count = buffer.remaining();
            if (count > remaining) {
                throw new IOException("Object content exceeds its declared size");
            }
            observer.accept(buffer.asReadOnlyBuffer());
            deflater.setInput(buffer);
            while (!deflater.needsInput()) {
                deflate(deflater, compressed);
            }
            remaining -= count;
        }
        if (remaining != 0) {
            throw new EOFException("Truncated object content");
        }
        deflater.finish();
        while (!deflater.finished()) {
            deflate(deflater, compressed);
        }
        return header.length;
    }

    private void deflate(Deflater deflater, byte[] compressed) throws IOException {
        int count = deflater.deflate(compressed);
        if (count > 0) {
            write(compressed, 0, count);
        } else if (!deflater.needsInput() && !deflater.finished()) {
            throw new IOException("Deflater made no progress");
        }
    }

    static byte[] objectHeader(GitObjectType type, long size) {
        byte[] header = new byte[10];
        int count = 0;
        int part = type.code() << 4 | (int) (size & 15);
        size >>>= 4;
        while (size != 0) {
            header[count++] = (byte) (part | 128);
            part = (int) (size & 127);
            size >>>= 7;
        }
        header[count++] = (byte) part;
        return Arrays.copyOf(header, count);
    }
}
