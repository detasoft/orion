package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public final class PackIngestor implements AutoCloseable {
    private final BufferedByteInputV2 input;
    private final IndexedPack target;
    private final ByteBuffer retained = ByteBuffer.allocate(8192);
    private final byte[] inflated = new byte[8192];
    private final MessageDigest checksum = sha1();
    private final MessageDigest objectHash = sha1();
    private long position;
    private boolean started;
    private boolean trailer;
    private boolean ownsTarget = true;

    public PackIngestor(BufferedByteInputV2 input, IndexedPack target) {
        this.input = Objects.requireNonNull(input, "input");
        this.target = Objects.requireNonNull(target, "target");
    }

    public IndexedPack ingest() throws IOException {
        if (started) {
            throw new IllegalStateException("Pack ingestion has already started or closed");
        }
        started = true;
        if (readInt() != 0x5041434b) {
            throw new IOException("Invalid pack magic bytes");
        }
        int version = readInt();
        if (version != 2) {
            throw new IOException("Unsupported pack version: " + version);
        }
        long count = Integer.toUnsignedLong(readInt());
        Inflater inflater = new Inflater();
        try {
            for (long entryNumber = 0; entryNumber < count; entryNumber++) {
                readEntry(inflater);
            }
        } finally {
            inflater.end();
        }
        byte[] expected = checksum.digest();
        trailer = true;
        byte[] received = readBytes(expected.length);
        if (!MessageDigest.isEqual(expected, received)) {
            throw new IOException("Pack checksum mismatch");
        }
        flush();
        ownsTarget = false;
        return target;
    }

    private void readEntry(Inflater inflater) throws IOException {
        long offset = position;
        int part = readByte();
        GitObjectType type = GitObjectType.valueOf((part >>> 4) & 7);
        long size = part & 15;
        int shift = 4;
        while ((part & 128) != 0) {
            part = readByte();
            if (shift > 60 || shift == 60 && (part & 127) > 7) {
                throw new IOException("Pack object size overflows a signed long");
            }
            size |= (long) (part & 127) << shift;
            shift += 7;
        }
        OptionalLong baseOffset = OptionalLong.empty();
        Optional<ObjectId> baseId = Optional.empty();
        if (type == GitObjectType.OFS_DELTA) {
            part = readByte();
            long distance = part & 127;
            while ((part & 128) != 0) {
                part = readByte();
                if (distance >= (Long.MAX_VALUE >>> 7)) {
                    throw new IOException("Pack delta offset overflows a signed long");
                }
                distance = ((distance + 1) << 7) | (part & 127);
            }
            if (distance == 0 || distance > offset - 12) {
                throw new IOException("Pack delta base must precede the entry and follow the pack header");
            }
            baseOffset = OptionalLong.of(offset - distance);
        } else if (type == GitObjectType.REF_DELTA) {
            baseId = Optional.of(new ObjectId(readBytes(checksum.getDigestLength())));
        }
        long dataOffset = position;
        boolean full = type != GitObjectType.OFS_DELTA && type != GitObjectType.REF_DELTA;
        if (full) {
            objectHash.reset();
            String header = type.name().toLowerCase(Locale.ROOT) + " " + size + "\0";
            objectHash.update(header.getBytes(StandardCharsets.US_ASCII));
        }
        inflater.reset();
        readContent(inflater, size, full);
        target.addEntry(offset, dataOffset, size, type, baseOffset, baseId);
        if (full) {
            target.addObject(offset, new ObjectId(objectHash.digest()), type, size);
        }
    }

    private void readContent(Inflater inflater, long size, boolean full) throws IOException {
        long inflatedSize = 0;
        ByteBuffer compressed = null;
        while (!inflater.finished()) {
            if (inflater.needsInput()) {
                compressed = input.buffer();
                if (compressed == null) {
                    throw new EOFException("Truncated pack zlib stream");
                }
                inflater.setInput(compressed);
            }
            int start = compressed.position();
            int count;
            try {
                count = inflater.inflate(inflated);
            } catch (DataFormatException error) {
                throw new IOException("Invalid pack zlib stream", error);
            }
            int consumed = compressed.position() - start;
            retain(compressed, start, consumed);
            if (count > size - inflatedSize) {
                throw new IOException("Inflated size exceeds declared object size");
            }
            inflatedSize += count;
            if (full) {
                objectHash.update(inflated, 0, count);
            }
            if (inflater.needsDictionary()) {
                throw new IOException("Pack zlib stream requires a dictionary");
            }
            if (count == 0 && consumed == 0 && !inflater.finished() && !inflater.needsInput()) {
                throw new IOException("Pack zlib stream made no progress");
            }
        }
        if (inflatedSize != size) {
            throw new IOException("Inflated size differs from declared object size");
        }
    }

    private int readInt() throws IOException {
        if (position > Long.MAX_VALUE - Integer.BYTES) {
            throw new IOException("Pack offset overflows a signed long");
        }
        if (retained.remaining() < Integer.BYTES) {
            flush();
        }
        int value = input.readInt();
        retained.putInt(value);
        position += Integer.BYTES;
        if (!trailer) {
            checksum.update(retained.array(), retained.position() - Integer.BYTES, Integer.BYTES);
        }
        if (!retained.hasRemaining()) {
            flush();
        }
        return value;
    }

    private byte[] readBytes(int length) throws IOException {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) readByte();
        }
        return bytes;
    }

    private int readByte() throws IOException {
        if (position == Long.MAX_VALUE) {
            throw new IOException("Pack offset overflows a signed long");
        }
        int value = input.readUnsignedByte();
        retained.put((byte) value);
        position++;
        if (!trailer) {
            checksum.update((byte) value);
        }
        if (!retained.hasRemaining()) {
            flush();
        }
        return value;
    }

    private void retain(ByteBuffer bytes, int offset, int length) throws IOException {
        if (length == 0) {
            return;
        }
        if (length > Long.MAX_VALUE - position) {
            throw new IOException("Pack offset overflows a signed long");
        }
        checksum.update(bytes.slice(offset, length));
        position += length;
        while (length > 0) {
            int count = Math.min(length, retained.remaining());
            retained.put(retained.position(), bytes, offset, count);
            retained.position(retained.position() + count);
            offset += count;
            length -= count;
            if (!retained.hasRemaining()) {
                flush();
            }
        }
    }

    private void flush() throws IOException {
        if (retained.position() != 0) {
            retained.flip();
            target.append(retained);
            retained.clear();
        }
    }

    @Override
    public void close() throws IOException {
        started = true;
        if (ownsTarget) {
            target.discard();
            ownsTarget = false;
        }
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-1 is required for Git", error);
        }
    }
}
