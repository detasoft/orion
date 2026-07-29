package pro.deta.orion.git.nativestorage.pack;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.object.LooseObject;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public final class PackIngestor {
    private static final byte[] PACK_MAGIC = {'P', 'A', 'C', 'K'};
    private static final int PACK_VERSION = 2;
    private static final int SHA1_BYTES = 20;
    private static final int OFS_DELTA_TYPE = 6;
    private static final int REF_DELTA_TYPE = 7;

    private final long maxPackBytes;

    public PackIngestor(long maxPackBytes) {
        if (maxPackBytes < 1) {
            throw new IllegalArgumentException("maxPackBytes must be positive");
        }
        this.maxPackBytes = maxPackBytes;
    }

    public LooseObjectStore ingest(ByteBuf packBuffer) {
        return ingest(packBuffer, new LooseObjectStore());
    }

    public LooseObjectStore ingest(ByteBuf packBuffer, LooseObjectStore baseStore) {
        Objects.requireNonNull(packBuffer, "packBuffer");
        Objects.requireNonNull(baseStore, "baseStore");
        return doIngest(packBuffer, baseStore);
    }

    private LooseObjectStore doIngest(ByteBuf packBuffer, LooseObjectStore baseStore) {
        int packLength = packBuffer.readableBytes();
        if (packLength > maxPackBytes) {
            throw new PackParseException("Pack exceeds size limit of " + maxPackBytes + " bytes");
        }
        if (packLength < PACK_MAGIC.length + 8 + SHA1_BYTES) {
            throw new PackParseException("Pack too short to be valid");
        }

        int packStart = packBuffer.readerIndex();
        int packDataLength = packLength - SHA1_BYTES;
        int trailerOffset = packStart + packDataLength;
        byte[] magic = bytes(packBuffer, packStart, PACK_MAGIC.length);
        if (!Arrays.equals(magic, PACK_MAGIC)) {
            throw new PackParseException("Invalid pack magic bytes");
        }

        byte[] computedChecksum = sha1(packBuffer, packStart, packDataLength);
        byte[] trailingChecksum = bytes(packBuffer, trailerOffset, SHA1_BYTES);
        if (!Arrays.equals(computedChecksum, trailingChecksum)) {
            throw new PackParseException("Pack checksum mismatch");
        }

        PackBuffer buf = new PackBuffer(packBuffer, packStart, trailerOffset);
        buf.readExact(PACK_MAGIC.length);

        int version = buf.readInt();
        if (version != PACK_VERSION) {
            throw new PackParseException("Unsupported pack version: " + version);
        }

        int objectCount = buf.readInt();
        if (objectCount < 0) {
            throw new PackParseException("Invalid pack object count");
        }

        LooseObjectStore quarantine = new LooseObjectStore();
        Map<Integer, LooseObject> objectsByOffset = new HashMap<>();
        for (int i = 0; i < objectCount; i++) {
            int objectOffset = buf.position();
            LooseObject object = ingestObject(buf, quarantine, baseStore, objectsByOffset, objectOffset);
            objectsByOffset.put(objectOffset, object);
        }

        if (buf.hasRemaining()) {
            throw new PackParseException("Unexpected trailing data after last pack object");
        }

        return quarantine;
    }

    private static LooseObject ingestObject(
            PackBuffer buf,
            LooseObjectStore quarantine,
            LooseObjectStore baseStore,
            Map<Integer, LooseObject> objectsByOffset,
            int objectOffset) {
        int firstByte = buf.readByte();
        int typeId = (firstByte >> 4) & 0x07;
        long size = firstByte & 0x0f;
        int shift = 4;
        int current = firstByte;
        while ((current & 0x80) != 0) {
            current = buf.readByte();
            size |= (long) (current & 0x7f) << shift;
            shift += 7;
        }

        if (typeId == OFS_DELTA_TYPE) {
            long baseDistance = readOffsetDeltaBaseDistance(buf);
            int baseOffset = checkedBaseOffset(objectOffset, baseDistance);
            LooseObject base = objectsByOffset.get(baseOffset);
            if (base == null) {
                throw new PackParseException("Offset delta base object is unavailable");
            }
            return writeResolvedDelta(quarantine, base, inflate(buf, size));
        }

        if (typeId == REF_DELTA_TYPE) {
            GitObjectId baseId = GitObjectId.of(HexFormat.of().formatHex(buf.readExact(SHA1_BYTES)));
            LooseObject base = readBaseObject(baseId, quarantine, baseStore);
            return writeResolvedDelta(quarantine, base, inflate(buf, size));
        }

        ObjectType type;
        try {
            type = ObjectType.fromPackTypeId(typeId);
        } catch (IllegalArgumentException e) {
            throw new PackParseException("Unknown pack object type id: " + typeId);
        }

        return writeObject(quarantine, type, inflate(buf, size));
    }

    private static long readOffsetDeltaBaseDistance(PackBuffer buf) {
        int current = buf.readByte();
        long distance = current & 0x7fL;
        while ((current & 0x80) != 0) {
            current = buf.readByte();
            distance = ((distance + 1) << 7) | (current & 0x7fL);
        }
        return distance;
    }

    private static int checkedBaseOffset(int objectOffset, long baseDistance) {
        long baseOffset = objectOffset - baseDistance;
        if (baseDistance <= 0 || baseOffset < 0 || baseOffset > Integer.MAX_VALUE) {
            throw new PackParseException("Invalid offset delta base distance");
        }
        return (int) baseOffset;
    }

    private static LooseObject readBaseObject(
            GitObjectId baseId,
            LooseObjectStore quarantine,
            LooseObjectStore baseStore) {
        return quarantine.read(baseId)
                .or(() -> baseStore.read(baseId))
                .orElseThrow(() -> new PackParseException("Reference delta base object is unavailable"));
    }

    private static LooseObject writeResolvedDelta(LooseObjectStore quarantine, LooseObject base, byte[] delta) {
        byte[] resolved = applyDelta(base.data(), delta);
        return writeObject(quarantine, base.type(), resolved);
    }

    private static LooseObject writeObject(LooseObjectStore quarantine, ObjectType type, byte[] data) {
        GitObjectId id = quarantine.write(type, data);
        return quarantine.read(id).orElseThrow(() -> new PackParseException("Written object is unavailable"));
    }

    private static byte[] applyDelta(byte[] source, byte[] delta) {
        DeltaVarInt sourceSize = readDeltaVarInt(delta, 0, "source");
        if (sourceSize.value() != source.length) {
            throw new PackParseException("Delta source size does not match base object");
        }

        DeltaVarInt targetSize = readDeltaVarInt(delta, sourceSize.nextOffset(), "target");
        if (targetSize.value() > Integer.MAX_VALUE) {
            throw new PackParseException("Delta target size is too large");
        }

        int offset = targetSize.nextOffset();
        ByteArrayOutputStream target = new ByteArrayOutputStream((int) targetSize.value());
        while (offset < delta.length) {
            int opcode = delta[offset++] & 0xff;
            if ((opcode & 0x80) != 0) {
                int copyOffset = 0;
                int copySize = 0;
                if ((opcode & 0x01) != 0) copyOffset = readDeltaByte(delta, offset++) & 0xff;
                if ((opcode & 0x02) != 0) copyOffset |= (readDeltaByte(delta, offset++) & 0xff) << 8;
                if ((opcode & 0x04) != 0) copyOffset |= (readDeltaByte(delta, offset++) & 0xff) << 16;
                if ((opcode & 0x08) != 0) copyOffset |= (readDeltaByte(delta, offset++) & 0xff) << 24;
                if ((opcode & 0x10) != 0) copySize = readDeltaByte(delta, offset++) & 0xff;
                if ((opcode & 0x20) != 0) copySize |= (readDeltaByte(delta, offset++) & 0xff) << 8;
                if ((opcode & 0x40) != 0) copySize |= (readDeltaByte(delta, offset++) & 0xff) << 16;
                if (copySize == 0) {
                    copySize = 0x10000;
                }
                if (copyOffset < 0 || copySize < 0 || copyOffset + copySize > source.length) {
                    throw new PackParseException("Delta copy instruction exceeds base object size");
                }
                target.write(source, copyOffset, copySize);
            } else if (opcode != 0) {
                int insertSize = opcode & 0x7f;
                if (offset + insertSize > delta.length) {
                    throw new PackParseException("Delta insert instruction exceeds delta data");
                }
                target.write(delta, offset, insertSize);
                offset += insertSize;
            } else {
                throw new PackParseException("Invalid zero delta opcode");
            }
        }

        byte[] resolved = target.toByteArray();
        if (resolved.length != targetSize.value()) {
            throw new PackParseException("Delta target size does not match resolved object");
        }
        return resolved;
    }

    private static DeltaVarInt readDeltaVarInt(byte[] delta, int offset, String name) {
        long value = 0;
        int shift = 0;
        int currentOffset = offset;
        int current;
        do {
            if (currentOffset >= delta.length) {
                throw new PackParseException("Truncated delta " + name + " size");
            }
            current = delta[currentOffset++] & 0xff;
            value |= (long) (current & 0x7f) << shift;
            shift += 7;
            if (shift > 63) {
                throw new PackParseException("Delta " + name + " size is too large");
            }
        } while ((current & 0x80) != 0);
        return new DeltaVarInt(value, currentOffset);
    }

    private static byte readDeltaByte(byte[] delta, int offset) {
        if (offset >= delta.length) {
            throw new PackParseException("Truncated delta copy instruction");
        }
        return delta[offset];
    }

    private static byte[] inflate(PackBuffer buf, long expectedSize) {
        Inflater inflater = new Inflater();
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(expectedSize, 1024 * 1024));
        byte[] outputBuf = new byte[4096];
        byte[] input = buf.remainingBytes();
        long totalOutput = 0;

        try {
            inflater.setInput(input);
            while (!inflater.finished()) {
                int produced;
                try {
                    produced = inflater.inflate(outputBuf);
                } catch (DataFormatException e) {
                    throw new PackParseException("Invalid deflate stream in pack: " + e.getMessage());
                }
                if (produced > 0) {
                    output.write(outputBuf, 0, produced);
                    totalOutput += produced;
                    if (totalOutput > expectedSize) {
                        throw new PackParseException("Inflated size exceeds declared object size");
                    }
                } else if (inflater.needsInput()) {
                    throw new PackParseException("Truncated pack: deflate stream ended unexpectedly");
                }
            }
        } finally {
            int consumed = input.length - inflater.getRemaining();
            buf.skip(consumed);
            inflater.end();
        }

        if (totalOutput != expectedSize) {
            throw new PackParseException(
                    "Inflated size " + totalOutput + " does not match declared size " + expectedSize);
        }
        return output.toByteArray();
    }

    private static byte[] sha1(ByteBuf data, int offset, int length) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(bytes(data, offset, length));
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }

    private static byte[] bytes(ByteBuf buffer, int offset, int length) {
        byte[] result = new byte[length];
        buffer.getBytes(offset, result);
        return result;
    }

    private record DeltaVarInt(long value, int nextOffset) {
    }

    private static final class PackBuffer {
        private final ByteBuf data;
        private int pos;
        private final int limit;

        PackBuffer(ByteBuf data, int offset, int limit) {
            this.data = data;
            this.pos = offset;
            this.limit = limit;
        }

        int position() {
            return pos;
        }

        int remaining() {
            return limit - pos;
        }

        byte[] remainingBytes() {
            return bytes(data, pos, remaining());
        }

        boolean hasRemaining() {
            return pos < limit;
        }

        int readByte() {
            if (pos >= limit) {
                throw new PackParseException("Truncated pack: expected more data");
            }
            return data.getByte(pos++) & 0xff;
        }

        int readInt() {
            byte[] bytes = readExact(4);
            return ((bytes[0] & 0xff) << 24)
                    | ((bytes[1] & 0xff) << 16)
                    | ((bytes[2] & 0xff) << 8)
                    | (bytes[3] & 0xff);
        }

        byte[] readExact(int count) {
            if (pos + count > limit) {
                throw new PackParseException("Truncated pack: expected " + count + " bytes");
            }
            byte[] result = bytes(data, pos, count);
            pos += count;
            return result;
        }

        void skip(int count) {
            if (pos + count > limit) {
                throw new PackParseException("Truncated pack: skip past end");
            }
            pos += count;
        }
    }
}
