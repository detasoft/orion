package pro.deta.orion.git.nativestorage.pack;

import java.io.ByteArrayOutputStream;

final class PackDeltaApplier {
    private static final int DEFAULT_COPY_SIZE = 0x10000;

    private PackDeltaApplier() {
    }

    static byte[] apply(
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
                CopyInstruction copy = readCopyInstruction(
                        source,
                        delta,
                        opcode,
                        offset);
                target.write(source, copy.offset(), copy.size());
                offset = copy.nextOffset();
            } else if (opcode != 0) {
                int insertSize = opcode & 0x7f;
                if (offset + insertSize > delta.length) {
                    throw new PackParseException(
                            "Delta insert instruction exceeds delta data");
                }
                target.write(delta, offset, insertSize);
                offset += insertSize;
            } else {
                throw new PackParseException("Invalid zero delta opcode");
            }
        }
        byte[] resolved = target.toByteArray();
        if (resolved.length != targetSize.value()) {
            throw new PackParseException(
                    "Delta target size does not match resolved object");
        }
        return resolved;
    }

    private static CopyInstruction readCopyInstruction(
            byte[] source,
            byte[] delta,
            int opcode,
            int offset) {
        int copyOffset = 0;
        int copySize = 0;
        if ((opcode & 0x01) != 0) {
            copyOffset = readDeltaByte(delta, offset++) & 0xff;
        }
        if ((opcode & 0x02) != 0) {
            copyOffset |= (readDeltaByte(delta, offset++) & 0xff) << 8;
        }
        if ((opcode & 0x04) != 0) {
            copyOffset |= (readDeltaByte(delta, offset++) & 0xff) << 16;
        }
        if ((opcode & 0x08) != 0) {
            copyOffset |= (readDeltaByte(delta, offset++) & 0xff) << 24;
        }
        if ((opcode & 0x10) != 0) {
            copySize = readDeltaByte(delta, offset++) & 0xff;
        }
        if ((opcode & 0x20) != 0) {
            copySize |= (readDeltaByte(delta, offset++) & 0xff) << 8;
        }
        if ((opcode & 0x40) != 0) {
            copySize |= (readDeltaByte(delta, offset++) & 0xff) << 16;
        }
        if (copySize == 0) {
            copySize = DEFAULT_COPY_SIZE;
        }
        long copyEnd = (long) copyOffset + copySize;
        if (copyOffset < 0
                || copySize < 0
                || copyEnd > source.length) {
            throw new PackParseException(
                    "Delta copy instruction exceeds base object size");
        }
        return new CopyInstruction(copyOffset, copySize, offset);
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
            throw new PackParseException("Truncated delta copy instruction");
        }
        return delta[offset];
    }

    private record CopyInstruction(
            int offset,
            int size,
            int nextOffset) {
    }

    private record DeltaVarInt(long value, int nextOffset) {
    }
}
