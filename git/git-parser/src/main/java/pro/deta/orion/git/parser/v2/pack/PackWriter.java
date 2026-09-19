package pro.deta.orion.git.parser.v2.pack;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.Deflater;

public final class PackWriter implements AutoCloseable {
    private final BufferedByteOutput output;
    private final MessageDigest checksum;
    private final long objectCount;
    private final ByteBuf buffer;
    private Deflater deflater;
    private byte[] compressed;
    private long writtenObjects;
    private long position;
    private boolean failed;
    private boolean finished;
    private boolean closed;

    public PackWriter(BufferedByteOutput output, long objectCount) throws IOException {
        this.output = Objects.requireNonNull(output, "output");
        if (objectCount < 0 || objectCount > 0xffff_ffffL) {
            throw new IllegalArgumentException("Invalid pack object count");
        }
        this.objectCount = objectCount;
        try {
            checksum = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-1 is required for Git packs", error);
        }
        buffer = Unpooled.buffer(8192, 8192);
        try {
            byte[] header = ByteBuffer.allocate(12).putInt(0x5041434b).putInt(2).putInt((int) objectCount).array();
            write(header, 0, header.length);
        } catch (IOException | RuntimeException | Error error) {
            buffer.release();
            throw error;
        }
    }

    public long writeCompressed(GitObjectType type, long size, Optional<ObjectId> baseId,
                                BufferedByteInput content) throws IOException {
        requireEntry();
        Objects.requireNonNull(content, "content");
        long offset = position;
        try {
            writeHeader(type, size, baseId);
            long start = position;
            int count;
            buffer.clear();
            while ((count = content.readInto(buffer, buffer.writableBytes())) != 0) {
                write(buffer.array(), buffer.arrayOffset() + buffer.readerIndex(), count);
                buffer.clear();
            }
            if (position == start) {
                throw new EOFException("Missing compressed object content");
            }
            writtenObjects++;
            return offset;
        } catch (IOException | RuntimeException | Error error) {
            failed = true;
            throw error;
        }
    }

    public long writeObject(GitObjectType type, long size, BufferedByteInput content) throws IOException {
        requireEntry();
        Objects.requireNonNull(content, "content");
        long offset = position;
        try {
            writeHeader(type, size, Optional.empty());
            if (deflater == null) {
                deflater = new Deflater();
                compressed = new byte[8192];
            } else {
                deflater.reset();
            }
            long remaining = size;
            int count;
            buffer.clear();
            while ((count = content.readInto(buffer, buffer.writableBytes())) != 0) {
                if (count > remaining) {
                    throw new IOException("Object content exceeds its declared size");
                }
                deflater.setInput(buffer.array(), buffer.arrayOffset() + buffer.readerIndex(), count);
                while (!deflater.needsInput()) {
                    deflate();
                }
                remaining -= count;
                buffer.clear();
            }
            if (remaining != 0) {
                throw new EOFException("Truncated object content");
            }
            deflater.finish();
            while (!deflater.finished()) {
                deflate();
            }
            writtenObjects++;
            return offset;
        } catch (IOException | RuntimeException | Error error) {
            failed = true;
            throw error;
        }
    }

    public PackId finish() throws IOException {
        requireOpen();
        if (writtenObjects != objectCount) {
            failed = true;
            throw new IOException("Pack object count does not match its header");
        }
        try {
            byte[] digest = checksum.digest();
            output.write(digest);
            position += digest.length;
            finished = true;
            return new PackId(digest);
        } catch (IOException | RuntimeException | Error error) {
            failed = true;
            throw error;
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            if (deflater != null) {
                deflater.end();
            }
            buffer.release();
        }
    }

    private void writeHeader(GitObjectType type, long size, Optional<ObjectId> baseId) throws IOException {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(baseId, "baseId");
        if (size < 0 || type == GitObjectType.OFS_DELTA
                || (type == GitObjectType.REF_DELTA) != baseId.isPresent()) {
            throw new IllegalArgumentException("Invalid outgoing pack entry");
        }
        byte[] header = objectHeader(type, size);
        write(header, 0, header.length);
        if (baseId.isPresent()) {
            byte[] id = baseId.orElseThrow().toBytes();
            write(id, 0, id.length);
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

    private void deflate() throws IOException {
        int count = deflater.deflate(compressed);
        if (count > 0) {
            write(compressed, 0, count);
        } else if (!deflater.needsInput() && !deflater.finished()) {
            throw new IOException("Deflater made no progress");
        }
    }

    private void write(byte[] bytes, int offset, int length) throws IOException {
        output.write(bytes, offset, length);
        checksum.update(bytes, offset, length);
        position += length;
    }

    private void requireEntry() throws IOException {
        requireOpen();
        if (writtenObjects == objectCount) {
            failed = true;
            throw new IOException("Pack has more entries than declared");
        }
    }

    private void requireOpen() throws IOException {
        if (closed || finished || failed) {
            throw new IOException("Pack writer is closed, finished or failed");
        }
    }
}
