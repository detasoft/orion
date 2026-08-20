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
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.zip.Deflater;

public final class DeltaPackBuilder {
    private static final int REF_DELTA_TYPE = 7;
    private static final int SHA1_BYTES = 20;
    private static final int MAX_BASE_CANDIDATES = 16;
    private static final int MAX_INSERT_SIZE = 127;
    private static final int MAX_COPY_SIZE = 0x10000;
    private static final int SCRATCH_SIZE = 8 * 1024;

    public NativePackProducer producer(
            LooseObjectStore objects,
            Collection<GitObjectId> objectIds) {
        return producer(objects, objectIds, List.of());
    }

    public NativePackProducer producer(
            LooseObjectStore objects,
            Collection<GitObjectId> objectIds,
            Collection<GitObjectId> externalBaseIds) {
        Objects.requireNonNull(objects, "objects");
        Objects.requireNonNull(objectIds, "objectIds");
        Objects.requireNonNull(externalBaseIds, "externalBaseIds");
        List<GitObjectId> sortedObjectIds = sortedObjectIds(objectIds);
        List<GitObjectId> externalBases = new ArrayList<>(externalBaseIds);
        externalBases.removeAll(sortedObjectIds);
        return new Producer(plan(objects, sortedObjectIds, externalBases));
    }

    private static List<GitObjectId> sortedObjectIds(
            Collection<GitObjectId> objectIds) {
        List<GitObjectId> sorted = new ArrayList<>(objectIds);
        sorted.sort(Comparator.comparing(GitObjectId::value));
        return sorted;
    }

    private static List<PackEntry> plan(
            LooseObjectStore objects,
            List<GitObjectId> objectIds,
            List<GitObjectId> externalBaseIds) {
        List<PackEntry> entries = new ArrayList<>(objectIds.size());
        Map<ObjectType, List<BaseCandidate>> bases =
                new EnumMap<>(ObjectType.class);
        Map<ObjectType, List<BaseCandidate>> externalBases =
                externalBaseCandidates(objects, externalBaseIds);
        for (GitObjectId objectId : objectIds) {
            LooseObject object = objects.read(objectId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Pack object is unavailable"));
            byte[] objectData = object.data();
            PackEntry whole = wholeEntry(
                    object.type(),
                    objectData);
            List<BaseCandidate> typeBases =
                    bases.computeIfAbsent(
                            object.type(),
                            ignored -> new ArrayList<>());
            PackEntry selected = selectDelta(
                    object,
                    objectData,
                    whole,
                    candidatesFor(
                            typeBases,
                            externalBases.get(object.type())))
                    .orElse(whole);
            entries.add(selected);
            if (!selected.delta()) {
                typeBases.add(new BaseCandidate(
                        object.id(),
                        objectData));
                if (typeBases.size() > MAX_BASE_CANDIDATES) {
                    typeBases.remove(0);
                }
            }
        }
        return List.copyOf(entries);
    }

    private static Map<ObjectType, List<BaseCandidate>> externalBaseCandidates(
            LooseObjectStore objects,
            List<GitObjectId> externalBaseIds) {
        Map<ObjectType, List<BaseCandidate>> bases =
                new EnumMap<>(ObjectType.class);
        for (GitObjectId objectId : externalBaseIds) {
            Optional<LooseObject> object = objects.read(objectId);
            if (object.isEmpty()) {
                continue;
            }
            List<BaseCandidate> typeBases =
                    bases.computeIfAbsent(
                            object.get().type(),
                            ignored -> new ArrayList<>());
            if (typeBases.size() >= MAX_BASE_CANDIDATES) {
                continue;
            }
            typeBases.add(new BaseCandidate(
                    objectId,
                    object.get().data()));
        }
        return bases;
    }

    private static List<BaseCandidate> candidatesFor(
            List<BaseCandidate> internalBases,
            List<BaseCandidate> externalBases) {
        if (externalBases == null || externalBases.isEmpty()) {
            return internalBases;
        }
        List<BaseCandidate> candidates = new ArrayList<>(
                internalBases.size() + externalBases.size());
        candidates.addAll(internalBases);
        candidates.addAll(externalBases);
        return candidates;
    }

    private static Optional<PackEntry> selectDelta(
            LooseObject object,
            byte[] objectData,
            PackEntry whole,
            List<BaseCandidate> bases) {
        PackEntry best = null;
        int bestCost = whole.cost();
        for (BaseCandidate base : bases) {
            byte[] delta = delta(base.data(), objectData);
            byte[] compressed = deflate(delta);
            int cost = objectHeader(REF_DELTA_TYPE, delta.length).length
                    + SHA1_BYTES
                    + compressed.length;
            if (cost >= bestCost) {
                continue;
            }
            if (best == null || cost < bestCost) {
                best = deltaEntry(
                        base.id(),
                        delta,
                        compressed,
                        cost);
                bestCost = cost;
            }
        }
        return Optional.ofNullable(best);
    }

    private static PackEntry wholeEntry(
            ObjectType type,
            byte[] data) {
        byte[] compressed = deflate(data);
        int cost = objectHeader(type.packTypeId(), data.length).length
                + compressed.length;
        return new PackEntry(
                null,
                type.packTypeId(),
                data.length,
                compressed,
                cost);
    }

    private static PackEntry deltaEntry(
            GitObjectId baseId,
            byte[] delta,
            byte[] compressed,
            int cost) {
        return new PackEntry(
                baseId,
                REF_DELTA_TYPE,
                delta.length,
                compressed,
                cost);
    }

    private static byte[] delta(
            byte[] source,
            byte[] target) {
        ByteArrayOutputStream delta = new ByteArrayOutputStream();
        writeDeltaVarInt(delta, source.length);
        writeDeltaVarInt(delta, target.length);
        int prefix = commonPrefix(source, target);
        int suffix = commonSuffix(source, target, prefix);
        if (prefix > 0) {
            writeCopy(delta, 0, prefix);
        }
        int insertLength = target.length - prefix - suffix;
        if (insertLength > 0) {
            writeInsert(delta, target, prefix, insertLength);
        }
        if (suffix > 0) {
            writeCopy(delta, source.length - suffix, suffix);
        }
        return delta.toByteArray();
    }

    private static int commonPrefix(
            byte[] source,
            byte[] target) {
        int limit = Math.min(source.length, target.length);
        int prefix = 0;
        while (prefix < limit && source[prefix] == target[prefix]) {
            prefix++;
        }
        return prefix;
    }

    private static int commonSuffix(
            byte[] source,
            byte[] target,
            int prefix) {
        int sourceLimit = source.length - prefix;
        int targetLimit = target.length - prefix;
        int limit = Math.min(sourceLimit, targetLimit);
        int suffix = 0;
        while (suffix < limit
                && source[source.length - suffix - 1]
                == target[target.length - suffix - 1]) {
            suffix++;
        }
        return suffix;
    }

    private static void writeDeltaVarInt(
            ByteArrayOutputStream output,
            int value) {
        do {
            int next = value & 0x7f;
            value >>>= 7;
            if (value > 0) {
                next |= 0x80;
            }
            output.write(next);
        } while (value > 0);
    }

    private static void writeInsert(
            ByteArrayOutputStream output,
            byte[] data,
            int offset,
            int length) {
        int remaining = length;
        int currentOffset = offset;
        while (remaining > 0) {
            int chunk = Math.min(remaining, MAX_INSERT_SIZE);
            output.write(chunk);
            output.write(data, currentOffset, chunk);
            currentOffset += chunk;
            remaining -= chunk;
        }
    }

    private static void writeCopy(
            ByteArrayOutputStream output,
            int offset,
            int length) {
        int currentOffset = offset;
        int remaining = length;
        while (remaining > 0) {
            int chunk = Math.min(remaining, MAX_COPY_SIZE);
            writeCopyChunk(output, currentOffset, chunk);
            currentOffset += chunk;
            remaining -= chunk;
        }
    }

    private static void writeCopyChunk(
            ByteArrayOutputStream output,
            int offset,
            int size) {
        int encodedSize = size == MAX_COPY_SIZE ? 0 : size;
        ByteArrayOutputStream operands = new ByteArrayOutputStream();
        int opcode = 0x80;
        opcode = addOperandByte(opcode, operands, offset, 0, 0x01);
        opcode = addOperandByte(opcode, operands, offset, 8, 0x02);
        opcode = addOperandByte(opcode, operands, offset, 16, 0x04);
        opcode = addOperandByte(opcode, operands, offset, 24, 0x08);
        opcode = addOperandByte(opcode, operands, encodedSize, 0, 0x10);
        opcode = addOperandByte(opcode, operands, encodedSize, 8, 0x20);
        opcode = addOperandByte(opcode, operands, encodedSize, 16, 0x40);
        output.write(opcode);
        output.writeBytes(operands.toByteArray());
    }

    private static int addOperandByte(
            int opcode,
            ByteArrayOutputStream operands,
            int value,
            int shift,
            int flag) {
        int operand = (value >>> shift) & 0xff;
        if (operand == 0) {
            return opcode;
        }
        operands.write(operand);
        return opcode | flag;
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater();
        byte[] scratch = new byte[SCRATCH_SIZE];
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            deflater.setInput(data);
            deflater.finish();
            while (!deflater.finished()) {
                int length = deflater.deflate(scratch);
                if (length == 0) {
                    throw new IllegalStateException(
                            "Pack deflater made no progress");
                }
                output.write(scratch, 0, length);
            }
            return output.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] packHeader(int objectCount) {
        byte[] header = new byte[12];
        header[0] = 'P';
        header[1] = 'A';
        header[2] = 'C';
        header[3] = 'K';
        writeInt(header, 4, 2);
        writeInt(header, 8, objectCount);
        return header;
    }

    private static byte[] objectHeader(
            int typeId,
            long size) {
        byte[] header = new byte[10];
        int offset = 0;
        int firstByte = (typeId << 4) | (int) (size & 0x0f);
        size >>>= 4;
        if (size > 0) {
            firstByte |= 0x80;
        }
        header[offset++] = (byte) firstByte;
        while (size > 0) {
            int nextByte = (int) (size & 0x7f);
            size >>>= 7;
            if (size > 0) {
                nextByte |= 0x80;
            }
            header[offset++] = (byte) nextByte;
        }
        return Arrays.copyOf(header, offset);
    }

    private static void writeInt(
            byte[] destination,
            int offset,
            int value) {
        destination[offset] = (byte) (value >>> 24);
        destination[offset + 1] = (byte) (value >>> 16);
        destination[offset + 2] = (byte) (value >>> 8);
        destination[offset + 3] = (byte) value;
    }

    private static MessageDigest sha1() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(
                    "SHA-1 not available",
                    error);
        }
    }

    private record BaseCandidate(
            GitObjectId id,
            byte[] data) {
    }

    private record PackEntry(
            GitObjectId baseId,
            int packTypeId,
            int inflatedSize,
            byte[] compressedData,
            int cost) {

        private boolean delta() {
            return baseId != null;
        }
    }

    private static final class Producer
            implements NativePackProducer {
        private final List<PackEntry> entries;
        private final MessageDigest digest = sha1();

        private Phase phase = Phase.HEADER;
        private int entryIndex;
        private byte[] pending;
        private int pendingOffset;
        private int pendingLength;
        private boolean digestPending;
        private boolean closed;

        private Producer(List<PackEntry> entries) {
            this.entries = entries;
        }

        @Override
        public Result produce(ByteBuf destination) {
            Objects.requireNonNull(destination, "destination");
            if (closed) {
                throw new IllegalStateException(
                        "Native pack producer is closed");
            }
            while (destination.isWritable()) {
                if (pending != null) {
                    writePending(destination);
                    if (pending != null) {
                        return Result.MORE;
                    }
                    continue;
                }
                prepareNext();
                if (phase == Phase.COMPLETED && pending == null) {
                    return Result.COMPLETED;
                }
            }
            return phase == Phase.COMPLETED && pending == null
                    ? Result.COMPLETED
                    : Result.MORE;
        }

        private void writePending(ByteBuf destination) {
            int length = Math.min(
                    destination.writableBytes(),
                    pendingLength - pendingOffset);
            destination.writeBytes(pending, pendingOffset, length);
            if (digestPending) {
                digest.update(pending, pendingOffset, length);
            }
            pendingOffset += length;
            if (pendingOffset == pendingLength) {
                pending = null;
                pendingOffset = 0;
                pendingLength = 0;
                digestPending = false;
            }
        }

        private void prepareNext() {
            switch (phase) {
                case HEADER -> {
                    setPending(packHeader(entries.size()), true);
                    phase = entries.isEmpty()
                            ? Phase.TRAILER
                            : Phase.ENTRY_HEADER;
                }
                case ENTRY_HEADER -> {
                    PackEntry entry = entries.get(entryIndex);
                    setPending(
                            objectHeader(
                                    entry.packTypeId(),
                                    entry.inflatedSize()),
                            true);
                    phase = entry.delta()
                            ? Phase.DELTA_BASE
                            : Phase.ENTRY_DATA;
                }
                case DELTA_BASE -> {
                    setPending(
                            HexFormat.of().parseHex(
                                    currentEntry().baseId().value()),
                            true);
                    phase = Phase.ENTRY_DATA;
                }
                case ENTRY_DATA -> {
                    setPending(currentEntry().compressedData(), true);
                    entryIndex++;
                    phase = entryIndex < entries.size()
                            ? Phase.ENTRY_HEADER
                            : Phase.TRAILER;
                }
                case TRAILER -> {
                    setPending(digest.digest(), false);
                    phase = Phase.COMPLETED;
                }
                case COMPLETED -> {
                }
            }
        }

        private PackEntry currentEntry() {
            return entries.get(entryIndex);
        }

        private void setPending(
                byte[] bytes,
                boolean updateDigest) {
            pending = bytes;
            pendingOffset = 0;
            pendingLength = bytes.length;
            digestPending = updateDigest;
        }

        @Override
        public void close() {
            closed = true;
            pending = null;
        }

        private enum Phase {
            HEADER,
            ENTRY_HEADER,
            DELTA_BASE,
            ENTRY_DATA,
            TRAILER,
            COMPLETED
        }
    }
}
