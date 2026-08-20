package pro.deta.orion.git.nativestorage.pack;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.object.LooseObject;
import pro.deta.orion.git.nativestorage.object.LooseObjectStore;
import pro.deta.orion.git.nativestorage.object.ObjectType;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Ingests receive-pack input and records whether published pack bytes are
 * self-contained. A pack is thin when a delta base is found in the repository
 * object store instead of inside the pack being ingested; those external base
 * ids are preserved in the publication request.
 */
public final class PackIngestor implements PackIngestionSession {
    private static final byte[] PACK_MAGIC = {'P', 'A', 'C', 'K'};
    private static final int PACK_VERSION = 2;
    private static final int HEADER_BYTES = 12;
    private static final int SHA1_BYTES = 20;
    private static final int OFS_DELTA_TYPE = 6;
    private static final int REF_DELTA_TYPE = 7;
    private static final int INPUT_CHUNK_BYTES = 8192;
    private static final int PACK_INDEX_MAGIC = 0xff744f63;
    private static final int PACK_INDEX_VERSION = 2;
    private static final int LARGE_OFFSET_FLAG = 0x80000000;
    private static final long MAX_SMALL_OFFSET = 0x7fffffffL;

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
    private final PackPublicationStore publicationStore;
    private LooseObjectStore quarantine = new LooseObjectStore();
    private final MessageDigest packDigest = sha1Digest();
    private final ByteArrayOutputStream rawPack = new ByteArrayOutputStream();
    private final byte[] header = new byte[HEADER_BYTES];
    private final byte[] trailer = new byte[SHA1_BYTES];
    private final byte[] refDeltaBase = new byte[SHA1_BYTES];
    private final byte[] inflaterInput = new byte[INPUT_CHUNK_BYTES];
    private final Map<Long, GitObjectId> objectsByOffset = new HashMap<>();
    private final List<PackIndexObject> packIndexObjects = new ArrayList<>();
    private final Set<GitObjectId> externalBaseIds = new LinkedHashSet<>();

    private Phase phase = Phase.PACK_HEADER;
    private PackParseException failure;
    private int headerBytes;
    private int trailerBytes;
    private int refDeltaBaseBytes;
    private long packBytes;
    private int declaredObjectCount;
    private int completedObjectCount;
    private long objectOffset;
    private CRC32 objectCrc;
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
    private boolean packPublished;
    private PublishedPack publishedPack;

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
        this(limits, baseStore, PackPublicationStore.NONE);
    }

    public PackIngestor(
            PackIngestionLimits limits,
            LooseObjectStore baseStore,
            PackPublicationStore publicationStore) {
        this.limits = Objects.requireNonNull(limits, "limits");
        this.baseStore = Objects.requireNonNull(baseStore, "baseStore");
        this.publicationStore = Objects.requireNonNull(
                publicationStore,
                "publicationStore");
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
                Objects.requireNonNull(publishedObjects, "baseStore"),
                publicationStore);
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
                return completeResult();
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
            try {
                return completeResult();
            } catch (PackParseException error) {
                return fail(error);
            }
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
            objectCrc = new CRC32();
            int first = readObjectByte(input) & 0xff;
            objectTypeId = (first >>> 4) & 0x07;
            declaredObjectSize = first & 0x0f;
            objectSizeShift = 4;
            if ((first & 0x80) == 0) {
                finishObjectHeader();
            }
            return;
        }
        while (input.isReadable() && phase == Phase.OBJECT_HEADER) {
            int next = readObjectByte(input) & 0xff;
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
                refDeltaBase[refDeltaBaseBytes++] = readObjectByte(input);
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
            int next = readObjectByte(input) & 0xff;
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
        objectCrc.update(
                inflaterInput,
                inflaterInputConsumed,
                consumed);
        rawPack.write(
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
            content = PackDeltaApplier.apply(
                    deltaBase.data(),
                    inflatedObject,
                    limits.maxInflatedObjectBytes());
        } else {
            type = ObjectType.fromPackTypeId(objectTypeId);
            content = inflatedObject;
        }
        GitObjectId id = quarantine.write(type, content);
        objectsByOffset.put(objectOffset, id);
        packIndexObjects.add(new PackIndexObject(
                id,
                objectOffset,
                objectCrc.getValue()));
        completedObjectCount++;
        resetObject();
        phase = completedObjectCount == declaredObjectCount
                ? Phase.TRAILER
                : Phase.OBJECT_HEADER;
    }

    private void readTrailer(ByteBuf input) {
        while (input.isReadable() && trailerBytes < SHA1_BYTES) {
            addPackBytes(1);
            byte value = input.readByte();
            rawPack.write(value & 0xff);
            trailer[trailerBytes++] = value;
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
        rawPack.write(value & 0xff);
        addPackBytes(1);
        return value;
    }

    private byte readObjectByte(ByteBuf input) {
        byte value = readPackByte(input);
        objectCrc.update(value & 0xff);
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

    private PackIngestionResult completeResult() {
        if (quarantineTransferred) {
            return fail("Pack quarantine was already transferred");
        }
        Optional<PublishedPack> pack = publishPack();
        quarantineTransferred = true;
        return new PackIngestionResult.Complete(quarantine, pack);
    }

    private Optional<PublishedPack> publishPack() {
        if (packPublished) {
            return Optional.ofNullable(publishedPack);
        }
        packPublished = true;
        if (publicationStore == PackPublicationStore.NONE) {
            return Optional.empty();
        }
        List<PackIndexObject> entries = sortedPackIndexObjects();
        BuiltPackIndex index = buildPackIndex(entries);
        PackPublicationRequest request = new PackPublicationRequest(
                rawPack.toByteArray(),
                index.bytes(),
                HexFormat.of().formatHex(trailer),
                index.checksum(),
                entries.size(),
                objectIds(entries),
                externalBaseIds);
        try {
            Optional<PublishedPack> result = publicationStore.publish(request);
            publishedPack = result.orElse(null);
            return result;
        } catch (RuntimeException error) {
            String message = error.getMessage();
            if (message == null || message.isBlank()) {
                throw new PackParseException(
                        "Failed to publish native Git pack");
            }
            throw new PackParseException(
                    "Failed to publish native Git pack: " + message);
        }
    }

    private List<PackIndexObject> sortedPackIndexObjects() {
        List<PackIndexObject> entries = new ArrayList<>(packIndexObjects);
        entries.sort((first, second) ->
                first.objectId().value().compareTo(second.objectId().value()));
        return entries;
    }

    private BuiltPackIndex buildPackIndex(List<PackIndexObject> entries) {
        ByteArrayOutputStream index = new ByteArrayOutputStream();
        writeInt(index, PACK_INDEX_MAGIC);
        writeInt(index, PACK_INDEX_VERSION);
        writeFanout(index, entries);
        writeObjectIds(index, entries);
        writeCrcs(index, entries);
        List<Long> largeOffsets = writeSmallOffsets(index, entries);
        writeLargeOffsets(index, largeOffsets);
        index.writeBytes(trailer);
        byte[] checksum = sha1Digest().digest(index.toByteArray());
        index.writeBytes(checksum);
        return new BuiltPackIndex(
                index.toByteArray(),
                HexFormat.of().formatHex(checksum));
    }

    private static void writeFanout(
            ByteArrayOutputStream index,
            List<PackIndexObject> entries) {
        int[] fanout = new int[256];
        for (PackIndexObject entry : entries) {
            fanout[objectIdFirstByte(entry.objectId())]++;
        }
        int cumulative = 0;
        for (int count : fanout) {
            cumulative += count;
            writeInt(index, cumulative);
        }
    }

    private static void writeObjectIds(
            ByteArrayOutputStream index,
            List<PackIndexObject> entries) {
        String previousId = null;
        HexFormat hex = HexFormat.of();
        for (PackIndexObject entry : entries) {
            String objectId = entry.objectId().value();
            if (objectId.equals(previousId)) {
                throw new PackParseException("Duplicate object id in pack");
            }
            index.writeBytes(hex.parseHex(objectId));
            previousId = objectId;
        }
    }

    private static void writeCrcs(
            ByteArrayOutputStream index,
            List<PackIndexObject> entries) {
        for (PackIndexObject entry : entries) {
            writeInt(index, (int) entry.crc32());
        }
    }

    private static List<Long> writeSmallOffsets(
            ByteArrayOutputStream index,
            List<PackIndexObject> entries) {
        List<Long> largeOffsets = new ArrayList<>();
        for (PackIndexObject entry : entries) {
            if (entry.packOffset() < 0) {
                throw new PackParseException("Negative pack object offset");
            }
            if (entry.packOffset() <= MAX_SMALL_OFFSET) {
                writeInt(index, (int) entry.packOffset());
            } else {
                writeInt(index, LARGE_OFFSET_FLAG | largeOffsets.size());
                largeOffsets.add(entry.packOffset());
            }
        }
        return largeOffsets;
    }

    private static void writeLargeOffsets(
            ByteArrayOutputStream index,
            List<Long> largeOffsets) {
        for (long offset : largeOffsets) {
            writeLong(index, offset);
        }
    }

    private static List<GitObjectId> objectIds(
            List<PackIndexObject> entries) {
        List<GitObjectId> objectIds = new ArrayList<>(entries.size());
        for (PackIndexObject entry : entries) {
            objectIds.add(entry.objectId());
        }
        return objectIds;
    }

    private static int objectIdFirstByte(GitObjectId id) {
        String value = id.value();
        if (value.length() < 2) {
            throw new PackParseException("Invalid object id in pack");
        }
        return Integer.parseInt(value.substring(0, 2), 16);
    }

    private LooseObject findBase(GitObjectId id) {
        Optional<LooseObject> quarantined = quarantine.read(id);
        if (quarantined.isPresent()) {
            return quarantined.get();
        }
        Optional<LooseObject> base = baseStore.read(id);
        if (base.isPresent()) {
            externalBaseIds.add(id);
            return base.get();
        }
        throw new PackParseException(
                "Reference delta base object is unavailable");
    }

    private void resetObject() {
        objectOffset = 0;
        objectCrc = null;
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
        packIndexObjects.clear();
        externalBaseIds.clear();
        rawPack.reset();
        objectCrc = null;
        publishedPack = null;
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

    private static void writeInt(
            ByteArrayOutputStream output,
            int value) {
        output.write((value >>> 24) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static void writeLong(
            ByteArrayOutputStream output,
            long value) {
        output.write((int) ((value >>> 56) & 0xff));
        output.write((int) ((value >>> 48) & 0xff));
        output.write((int) ((value >>> 40) & 0xff));
        output.write((int) ((value >>> 32) & 0xff));
        output.write((int) ((value >>> 24) & 0xff));
        output.write((int) ((value >>> 16) & 0xff));
        output.write((int) ((value >>> 8) & 0xff));
        output.write((int) (value & 0xff));
    }

    private static MessageDigest sha1Digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-1 not available", error);
        }
    }

    private record PackIndexObject(
            GitObjectId objectId,
            long packOffset,
            long crc32) {
        private PackIndexObject {
            Objects.requireNonNull(objectId, "objectId");
        }
    }

    private record BuiltPackIndex(byte[] bytes, String checksum) {
        private BuiltPackIndex {
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
            Objects.requireNonNull(checksum, "checksum");
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
