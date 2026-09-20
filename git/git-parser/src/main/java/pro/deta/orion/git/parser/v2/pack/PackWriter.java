package pro.deta.orion.git.parser.v2.pack;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.net.io.BufferedByteInputV2;
import pro.deta.orion.net.io.BufferedByteOutput;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.Deflater;

public final class PackWriter implements AutoCloseable {
    private final BufferedByteOutput output;
    private final MessageDigest checksum;
    private final long objectCount;
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
        checksum = GitHashAlgorithm.SHA1.newDigest();
        byte[] header = ByteBuffer.allocate(12).putInt(0x5041434b).putInt(2).putInt((int) objectCount).array();
        write(header, 0, header.length);
    }

    public long writeCompressed(GitObjectType type, long size, Optional<ObjectId> baseId,
                                BufferedByteInputV2 content) throws IOException {
        requireEntry();
        Objects.requireNonNull(content, "content");
        long offset = position;
        try {
            writeHeader(type, size, baseId);
            long start = position;
            ByteBuffer buffer;
            while ((buffer = content.buffer()) != null) {
                write(buffer);
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

    public long writeObject(GitObjectType type, long size, BufferedByteInputV2 content) throws IOException {
        requireEntry();
        Objects.requireNonNull(content, "content");
        long offset = position;
        try {
            if (deflater == null) {
                deflater = new Deflater();
                compressed = new byte[8192];
            }
            PackEntryWriter entry = this::write;
            entry.writeObject(type, size, content, deflater, compressed, ignored -> {});
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
        }
    }

    private void writeHeader(GitObjectType type, long size, Optional<ObjectId> baseId) throws IOException {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(baseId, "baseId");
        if (size < 0 || type == GitObjectType.OFS_DELTA
                || (type == GitObjectType.REF_DELTA) != baseId.isPresent()) {
            throw new IllegalArgumentException("Invalid outgoing pack entry");
        }
        byte[] header = PackEntryWriter.objectHeader(type, size);
        write(header, 0, header.length);
        if (baseId.isPresent()) {
            byte[] id = baseId.orElseThrow().toBytes();
            write(id, 0, id.length);
        }
    }

    private void write(ByteBuffer bytes) throws IOException {
        int length = bytes.remaining();
        ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
        try {
            output.write(buffer);
            checksum.update(bytes);
            position += length;
        } finally {
            buffer.release();
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
