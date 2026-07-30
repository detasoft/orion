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

public final class PackIngestor implements PackIngestionSession {
    private static final byte[] PACK_MAGIC = {'P', 'A', 'C', 'K'};
    private static final int PACK_VERSION = 2;
    private static final int HEADER_BYTES = 12;
    private static final int SHA1_BYTES = 20;
    private static final int OFS_DELTA_TYPE = 6;
    private static final int REF_DELTA_TYPE = 7;
    private static final int INPUT_CHUNK_BYTES = 8192;

    private enum Phase {
        PACK_HEADER,
        OBJECT_HEADER,
        DELTA_BASE,
        OBJECT_DATA,
        TRAILER,
        COMPLETE,
        FAILED,
        CLOSED
    }

    private final PackIngestionLimits limits;
    private final LooseObjectStore baseStore;
    private LooseObjectStore quarantine = new LooseObjectStore();
    private final MessageDigest packDigest = sha1Digest();
    private final byte[] header = new byte[HEADER_BYTES];
    private final byte[] trailer = new byte[SHA1_BYTES];
    private final byte[] refDeltaBase = new byte[SHA1_BYTES];
    private final byte[] inflaterInput = new byte[INPUT_CHUNK_BYTES];
    private final Map<Long, GitObjectId> objectsByOffset = new HashMap<>();

    private Phase phase = Phase.PACK_HEADER;
    private PackParseException failure;
    private int headerBytes;
    private int trailerBytes;
    private int refDeltaBaseBytes;
    private long packBytes;
    private int declaredObjectCount;
    private int completedObjectCount;
    private long objectOffset;
    private int objectTypeId;
    private long declaredObjectSize;
    private int objectSizeShift;
    private long offsetDeltaDistance;
    private boolean offsetDeltaStarted;
    private Inflater inflater;
    private byte[] inflatedObject;
    private int inflatedBytes;
    private int inflaterInputLength;
    private int inflaterInputConsumed;
    private LooseObject deltaBase;
    private boolean quarantineTransferred;

    public PackIngestor(long maxPackBytes) {
        this(
                new PackIngestionLimits(
                        maxPackBytes,
                        Integer.MAX_VALUE,
                        Integer.MAX_VALUE),
                new LooseObjectStore());
    }

    public PackIngestor(
            PackIngestionLimits limits,
            LooseObjectStore baseStore) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.baseStore = Objects.requireNonNull(baseStore, "baseStore");
    }

    public LooseObjectStore ingest(ByteBuf packBuffer) {
        return ingest(packBuffer, new LooseObjectStore());
    }

    public LooseObjectStore ingest(
            ByteBuf packBuffer,
            LooseObjectStore publishedObjects) {
        Objects.requireNonNull(packBuffer, "packBuffer");
        PackIngestor session = new PackIngestor(
                limits,
                Objects.requireNonNull(publishedObjects, "baseStore"));
        PackIngestionResult result = session.accept(packBuffer);
        if (result instanceof PackIngestionResult.NeedInput) {
            result = session.endOfInput();
        }
        if (result instanceof PackIngestionResult.Complete complete) {
            return complete.quarantine();
        }
        throw ((PackIngestionResult.Failed) result).failure();
    }

    @Override
    public PackIngestionResult accept(ByteBuf input) {
        Objects.requireNonNull(input, "input");
        if (phase == Phase.COMPLETE) {
            return fail("Unexpected bytes after pack checksum");
        }
        if (phase == Phase.FAILED || phase == Phase.CLOSED) {
            return new PackIngestionResult.Failed(failure);
        }
        try {
            while (input.isReadable() && !terminal()) {
                switch (phase) {
                    case PACK_HEADER -> readPackHeader(input);
                    case OBJECT_HEADER -> readObjectHeader(input);
                    case DELTA_BASE -> readDeltaBase(input);
                    case OBJECT_DATA -> readObjectData(input);
                    case TRAILER -> readTrailer(input);
                    default -> throw new IllegalStateException(
                            "Unexpected pack ingestion phase " + phase);
                }
            }
            if (phase == Phase.COMPLETE) {
                if (input.isReadable()) {
                    return fail("Unexpected bytes after pack checksum");
                }
                if (quarantineTransferred) {
                    return fail("Pack quarantine was already transferred");
                }
                quarantineTransferred = true;
                return new PackIngestionResult.Complete(quarantine);
            }
            if (phase == Phase.FAILED) {
                return new PackIngestionResult.Failed(failure);
            }
            return new PackIngestionResult.NeedInput();
        } catch (PackParseException error) {
            return fail(error);
        } catch (RuntimeException error) {
            return fail(new PackParseException(
                    "Malformed pack: " + error.getMessage()));
        }
    }

    @Override
    public PackIngestionResult endOfInput() {
        if (phase == Phase.COMPLETE && !quarantineTransferred) {
            quarantineTransferred = true;
            return new PackIngestionResult.Complete(quarantine);
        }
        if (phase == Phase.FAILED || phase == Phase.CLOSED) {
            return new PackIngestionResult.Failed(failure);
        }
        return fail(new PackParseException(
                PackParseException.Kind.INCOMPLETE,
                "Pack input ended before checksum completion"));
    }

    @Override
    public void close() {
        endInflater();
        if (failure == null) {
            failure = new PackParseException(
                    PackParseException.Kind.INCOMPLETE,
                    phase == Phase.COMPLETE
                            ? "Pack ingestion session is closed"
                            : "Pack ingestion closed before completion");
        }
        discardSessionState();
        phase = Phase.CLOSED;
    }

    private void readPackHeader(ByteBuf input) {
        while (input.isReadable() && headerBytes < HEADER_BYTES) {
            header[headerBytes++] = readPackByte(input);
        }
        if (headerBytes != HEADER_BYTES) {
            return;
        }
        if (!Arrays.equals(
                Arrays.copyOf(header, PACK_MAGIC.length),
                PACK_MAGIC)) {
            throw new PackParseException("Invalid pack magic bytes");
        }
        int version = intAt(header, 4);
        if (version != PACK_VERSION) {
            throw new PackParseException(
                    "Unsupported pack version: " + version);
        }
        declaredObjectCount = intAt(header, 8);
        if (declaredObjectCount < 0
                || declaredObjectCount > limits.maxObjectCount()) {
            throw new PackParseException(
                    PackParseException.Kind.LIMIT_EXCEEDED,
                    "Pack object count exceeds limit");
        }
        phase = declaredObjectCount == 0
                ? Phase.TRAILER
                : Phase.OBJECT_HEADER;
    }

    private void readObjectHeader(ByteBuf input) {
        if (objectSizeShift == 0) {
            objectOffset = packBytes;
            int first = readPackByte(input) & 0xff;
            objectTypeId = (first >>> 4) & 0x07;
            declaredObjectSize = first & 0x0f;
            objectSizeShift = 4;
            if ((first & 0x80) == 0) {
                finishObjectHeader();
            }
            return;
        }
        while (input.isReadable() && phase == Phase.OBJECT_HEADER) {
            int next = readPackByte(input) & 0xff;
            if (objectSizeShift > 60) {
                throw new PackParseException(
                        "Pack object size is too large");
            }
            declaredObjectSize |=
                    (long) (next & 0x7f) << objectSizeShift;
            objectSizeShift += 7;
            if ((next & 0x80) == 0) {
                finishObjectHeader();
            }
        }
    }

    private void finishObjectHeader() {
        if (declaredObjectSize > limits.maxInflatedObjectBytes()
                || declaredObjectSize > Integer.MAX_VALUE) {
            throw new PackParseException(
                    PackParseException.Kind.LIMIT_EXCEEDED,
                    "Inflated object size exceeds limit");
        }
        if (objectTypeId == OFS_DELTA_TYPE
                || objectTypeId == REF_DELTA_TYPE) {
            phase = Phase.DELTA_BASE;
            return;
        }
        try {
            ObjectType.fromPackTypeId(objectTypeId);
        } catch (IllegalArgumentException error) {
            throw new PackParseException(
                    "Unknown pack object type id: " + objectTypeId);
        }
        startInflater();
    }

    private void readDeltaBase(ByteBuf input) {
        if (objectTypeId == REF_DELTA_TYPE) {
            while (input.isReadable()
                    && refDeltaBaseBytes < SHA1_BYTES) {
                refDeltaBase[refDeltaBaseBytes++] = readPackByte(input);
            }
            if (refDeltaBaseBytes == SHA1_BYTES) {
                GitObjectId id = GitObjectId.of(
                        HexFormat.of().formatHex(refDeltaBase));
                deltaBase = findBase(id);
                startInflater();
            }
            return;
        }
        while (input.isReadable() && phase == Phase.DELTA_BASE) {
            int next = readPackByte(input) & 0xff;
            if (!offsetDeltaStarted) {
                offsetDeltaDistance = next & 0x7fL;
                offsetDeltaStarted = true;
            } else {
                if (offsetDeltaDistance > (Long.MAX_VALUE >>> 7)) {
                    throw new PackParseException(
                            "Offset delta distance is too large");
                }
                offsetDeltaDistance =
                        ((offsetDeltaDistance + 1) << 7)
                                | (next & 0x7fL);
            }
            if ((next & 0x80) == 0) {
                long baseOffset = objectOffset - offsetDeltaDistance;
                GitObjectId id = objectsByOffset.get(baseOffset);
                if (offsetDeltaDistance <= 0 || id == null) {
                    throw new PackParseException(
                            "Offset delta base object is unavailable");
                }
                deltaBase = findBase(id);
                startInflater();
            }
        }
    }

    private void startInflater() {
        inflatedObject = new byte[(int) declaredObjectSize];
        inflater = new Inflater();
        phase = Phase.OBJECT_DATA;
    }

    private void readObjectData(ByteBuf input) {
        while (phase == Phase.OBJECT_DATA) {
            if (inflater.needsInput()) {
                if (!input.isReadable()) {
                    return;
                }
                inflaterInputLength = Math.min(
                        input.readableBytes(),
                        inflaterInput.length);
                inflaterInputConsumed = 0;
                input.getBytes(
                        input.readerIndex(),
                        inflaterInput,
                        0,
                        inflaterInputLength);
                inflater.setInput(
                        inflaterInput,
                        0,
                        inflaterInputLength);
            }

            int beforeRemaining = inflater.getRemaining();
            int produced;
            try {
                if (inflatedBytes < inflatedObject.length) {
                    produced = inflater.inflate(
                            inflatedObject,
                            inflatedBytes,
                            inflatedObject.length - inflatedBytes);
                } else {
                    byte[] overflow = new byte[1];
                    produced = inflater.inflate(overflow);
                }
            } catch (DataFormatException error) {
                throw new PackParseException(
                        "Invalid deflate stream in pack: "
                                + error.getMessage());
            }
            int consumed = beforeRemaining - inflater.getRemaining();
            consumeCompressedInput(input, consumed);
            inflatedBytes += produced;
            if (inflatedBytes > inflatedObject.length) {
                throw new PackParseException(
                        "Inflated size exceeds declared object size");
            }
            if (inflater.finished()) {
                finishObject();
                return;
            }
            if (produced == 0 && consumed == 0) {
                if (inflater.needsDictionary()) {
                    throw new PackParseException(
                            "Pack deflate stream requires a dictionary");
                }
                if (inflater.needsInput()) {
                    continue;
                }
                throw new PackParseException(
                        "Invalid stalled deflate stream in pack");
            }
        }
    }

    private void consumeCompressedInput(ByteBuf input, int consumed) {
        if (consumed == 0) {
            return;
        }
        if (consumed > input.readableBytes()
                || inflaterInputConsumed + consumed
                > inflaterInputLength) {
            throw new PackParseException(
                    "Invalid deflate input accounting");
        }
        packDigest.update(
                inflaterInput,
                inflaterInputConsumed,
                consumed);
        input.skipBytes(consumed);
        inflaterInputConsumed += consumed;
        addPackBytes(consumed);
    }

    private void finishObject() {
        endInflater();
        if (inflatedBytes != declaredObjectSize) {
            throw new PackParseException(
                    "Inflated size does not match declared object size");
        }
        ObjectType type;
        byte[] content;
        if (objectTypeId == OFS_DELTA_TYPE
                || objectTypeId == REF_DELTA_TYPE) {
            type = deltaBase.type();
            content = applyDelta(
                    deltaBase.data(),
                    inflatedObject,
                    limits.maxInflatedObjectBytes());
        } else {
            type = ObjectType.fromPackTypeId(objectTypeId);
            content = inflatedObject;
        }
        GitObjectId id = quarantine.write(type, content);
        objectsByOffset.put(objectOffset, id);
        completedObjectCount++;
        resetObject();
        phase = completedObjectCount == declaredObjectCount
                ? Phase.TRAILER
                : Phase.OBJECT_HEADER;
    }

    private void readTrailer(ByteBuf input) {
        while (input.isReadable() && trailerBytes < SHA1_BYTES) {
            addPackBytes(1);
            trailer[trailerBytes++] = input.readByte();
        }
        if (trailerBytes == SHA1_BYTES) {
            if (!Arrays.equals(packDigest.digest(), trailer)) {
                throw new PackParseException("Pack checksum mismatch");
            }
            phase = Phase.COMPLETE;
        }
    }

    private byte readPackByte(ByteBuf input) {
        byte value = input.readByte();
        packDigest.update(value);
        addPackBytes(1);
        return value;
    }

    private void addPackBytes(long count) {
        packBytes += count;
        if (packBytes > limits.maxPackBytes()) {
            throw new PackParseException(
                    PackParseException.Kind.LIMIT_EXCEEDED,
                    "Pack exceeds size limit");
        }
    }

    private LooseObject findBase(GitObjectId id) {
        return quarantine.read(id)
                .or(() -> baseStore.read(id))
                .orElseThrow(() -> new PackParseException(
                        "Reference delta base object is unavailable"));
    }

    private void resetObject() {
        objectOffset = 0;
        objectTypeId = 0;
        declaredObjectSize = 0;
        objectSizeShift = 0;
        offsetDeltaDistance = 0;
        offsetDeltaStarted = false;
        refDeltaBaseBytes = 0;
        Arrays.fill(refDeltaBase, (byte) 0);
        inflatedObject = null;
        inflatedBytes = 0;
        inflaterInputLength = 0;
        inflaterInputConsumed = 0;
        deltaBase = null;
    }

    private void endInflater() {
        if (inflater != null) {
            inflater.end();
            inflater = null;
        }
    }

    private boolean terminal() {
        return phase == Phase.COMPLETE
                || phase == Phase.FAILED
                || phase == Phase.CLOSED;
    }

    private PackIngestionResult fail(String message) {
        return fail(new PackParseException(message));
    }

    private PackIngestionResult fail(PackParseException error) {
        endInflater();
        failure = error;
        discardSessionState();
        phase = Phase.FAILED;
        return new PackIngestionResult.Failed(error);
    }

    private void discardSessionState() {
        inflatedObject = null;
        deltaBase = null;
        objectsByOffset.clear();
        quarantine = null;
        inflaterInputLength = 0;
        inflaterInputConsumed = 0;
    }

    private static int intAt(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 24)
                | ((data[offset + 1] & 0xff) << 16)
                | ((data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
    }

    private static MessageDigest sha1Digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-1 not available", error);
        }
    }

    private static byte[] applyDelta(
            byte[] source,
            byte[] delta,
            int maxInflatedObjectBytes) {
        DeltaVarInt sourceSize = readDeltaVarInt(delta, 0, "source");
        if (sourceSize.value() != source.length) {
            throw new PackParseException(
                    "Delta source size does not match base object");
        }
        DeltaVarInt targetSize = readDeltaVarInt(
                delta,
                sourceSize.nextOffset(),
                "target");
        if (targetSize.value() > maxInflatedObjectBytes
                || targetSize.value() > Integer.MAX_VALUE) {
            throw new PackParseException(
                    PackParseException.Kind.LIMIT_EXCEEDED,
                    "Delta target size is too large");
        }
        int offset = targetSize.nextOffset();
        ByteArrayOutputStream target =
                new ByteArrayOutputStream((int) targetSize.value());
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
                if (copySize == 0) copySize = 0x10000;
                if (copyOffset < 0 || copySize < 0
                        || copyOffset + copySize > source.length) {
                    throw new PackParseException(
                            "Delta copy instruction exceeds base object size");
                }
                target.write(source, copyOffset, copySize);
            } else if (opcode != 0) {
                int insertSize = opcode & 0x7f;
                if (offset + insertSize > delta.length) {
                    throw new PackParseException(
                            "Delta insert instruction exceeds delta data");
                }
                target.write(delta, offset, insertSize);
                offset += insertSize;
            } else {
                throw new PackParseException(
                        "Invalid zero delta opcode");
            }
        }
        byte[] resolved = target.toByteArray();
        if (resolved.length != targetSize.value()) {
            throw new PackParseException(
                    "Delta target size does not match resolved object");
        }
        return resolved;
    }

    private static DeltaVarInt readDeltaVarInt(
            byte[] delta,
            int offset,
            String name) {
        long value = 0;
        int shift = 0;
        int currentOffset = offset;
        int current;
        do {
            if (currentOffset >= delta.length) {
                throw new PackParseException(
                        "Truncated delta " + name + " size");
            }
            current = delta[currentOffset++] & 0xff;
            value |= (long) (current & 0x7f) << shift;
            shift += 7;
            if (shift > 63) {
                throw new PackParseException(
                        "Delta " + name + " size is too large");
            }
        } while ((current & 0x80) != 0);
        return new DeltaVarInt(value, currentOffset);
    }

    private static byte readDeltaByte(byte[] delta, int offset) {
        if (offset >= delta.length) {
            throw new PackParseException(
                    "Truncated delta copy instruction");
        }
        return delta[offset];
    }

    private record DeltaVarInt(long value, int nextOffset) {
    }
}
