package pro.deta.orion.git.parser.v2.pack;

import pro.deta.orion.git.parser.v2.data.GitObjectType;
import pro.deta.orion.git.parser.v2.data.GitHashAlgorithm;
import pro.deta.orion.git.parser.v2.id.ObjectId;
import pro.deta.orion.git.parser.v2.id.PackId;
import pro.deta.orion.net.io.BufferedByteInputV2;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Pull reader for one pack, independent of storage. A single inflater validates entry boundaries and
 * sizes and hashes full objects. Raw bytes, including headers and trailer, are returned unchanged.
 * Returned buffers are borrowed until the next operation. The caller owns the input, which remains
 * positioned immediately after the trailer. End, failure or close terminates the reader.
 */
public final class PackReader implements AutoCloseable {
    private enum State { HEADER, ENTRY_HEADER, CONTENT, TRAILER, END, CLOSED }

    private final BufferedByteInputV2 input;
    private final Inflater inflater = new Inflater();
    private final ByteBuffer header = ByteBuffer.allocate(64);
    private final byte[] inflated = new byte[8192];
    private final MessageDigest checksum = GitHashAlgorithm.SHA1.newDigest();
    private final MessageDigest objectHash = GitHashAlgorithm.SHA1.newDigest();
    private State state = State.HEADER;
    private long position;
    private long remainingEntries;
    private long inflatedSize;
    private PackEntry entry;
    private ByteBuffer compressed;
    private PackId id;

    public PackReader(BufferedByteInputV2 input) {
        this.input = Objects.requireNonNull(input, "input");
    }

    public PackReadStep next() throws IOException {
        if (state == State.CLOSED) {
            throw new IllegalStateException("Pack reader has ended, failed or closed");
        }
        try {
            return switch (state) {
                case HEADER -> {
                    header.clear();
                    remainingEntries = PackHeader.read(readBytes(PackHeader.SIZE));
                    state = remainingEntries == 0 ? State.TRAILER : State.ENTRY_HEADER;
                    yield hashBytes(header.flip());
                }
                case ENTRY_HEADER -> {
                    header.clear();
                    readEntry();
                    state = State.CONTENT;
                    yield hashBytes(header.flip());
                }
                case CONTENT -> {
                    ByteBuffer bytes = readContent();
                    if (bytes != null) {
                        yield hashBytes(bytes);
                    }
                    Optional<ObjectId> objectId = fullEntry() ? Optional.of(new ObjectId(objectHash.digest()))
                            : Optional.empty();
                    remainingEntries--;
                    state = remainingEntries == 0 ? State.TRAILER : State.ENTRY_HEADER;
                    yield new PackReadStep.EntryEnd(entry, objectId);
                }
                case TRAILER -> {
                    header.clear();
                    byte[] expected = checksum.digest();
                    byte[] received = readBytes(expected.length);
                    if (!MessageDigest.isEqual(expected, received)) {
                        throw new IOException("Pack checksum mismatch");
                    }
                    id = new PackId(received);
                    state = State.END;
                    yield new PackReadStep.Bytes(header.flip().asReadOnlyBuffer());
                }
                case END -> {
                    close();
                    yield new PackReadStep.End(id);
                }
                case CLOSED -> throw new IllegalStateException("Pack reader is closed");
            };
        } catch (IOException | RuntimeException | Error failure) {
            close();
            throw failure;
        }
    }

    private PackReadStep.Bytes hashBytes(ByteBuffer bytes) {
        checksum.update(bytes.duplicate());
        return new PackReadStep.Bytes(bytes.asReadOnlyBuffer());
    }

    private void readEntry() throws IOException {
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
            if (distance == 0 || distance > offset - PackHeader.SIZE) {
                throw new IOException("Pack delta base must precede the entry and follow the pack header");
            }
            baseOffset = OptionalLong.of(offset - distance);
        } else if (type == GitObjectType.REF_DELTA) {
            baseId = Optional.of(new ObjectId(readBytes(checksum.getDigestLength())));
        }
        long packOffset = position;
        boolean full = type != GitObjectType.OFS_DELTA && type != GitObjectType.REF_DELTA;
        if (full) {
            objectHash.reset();
            String header = type.name().toLowerCase(Locale.ROOT) + " " + size + "\0";
            objectHash.update(header.getBytes(StandardCharsets.US_ASCII));
        }
        inflater.reset();
        inflatedSize = 0;
        compressed = null;
        entry = new PackEntry(offset, packOffset, size, type, baseOffset, baseId);
    }

    private boolean fullEntry() {
        return entry.type() != GitObjectType.OFS_DELTA && entry.type() != GitObjectType.REF_DELTA;
    }

    private ByteBuffer readContent() throws IOException {
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
            if (count > entry.inflatedSize() - inflatedSize) {
                throw new IOException("Inflated size exceeds declared object size");
            }
            inflatedSize += count;
            if (entry.type() == GitObjectType.TREE && count > 0) {
                verifyTree(inflated, count);
            }
            if (fullEntry()) {
                objectHash.update(inflated, 0, count);
            }
            if (inflater.needsDictionary()) {
                throw new IOException("Pack zlib stream requires a dictionary");
            }
            if (count == 0 && consumed == 0 && !inflater.finished() && !inflater.needsInput()) {
                throw new IOException("Pack zlib stream made no progress");
            }
            if (inflater.finished() && inflatedSize != entry.inflatedSize()) {
                throw new IOException("Inflated size differs from declared object size");
            }
            if (consumed > 0) {
                if (consumed > Long.MAX_VALUE - position) {
                    throw new IOException("Pack offset overflows a signed long");
                }
                position += consumed;
                return compressed.slice(start, consumed);
            }
        }
        return null;
    }

    private void verifyTree(byte[] data, int length) throws IOException {
        // @todo Verify tree entry ordering across inflated chunks without rewriting bytes or object IDs.
        // This is a placeholder: tree ordering is not currently checked. Delta trees need resolved content
        // before verification; PackReader only sees their delta instructions.
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
        header.put((byte) value);
        position++;
        return value;
    }

    @Override
    public void close() {
        if (state != State.CLOSED) {
            state = State.CLOSED;
            inflater.end();
            compressed = null;
        }
    }
}
