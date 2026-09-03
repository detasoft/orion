package pro.deta.orion.agent.server.journal;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Incrementally validates Zstd frame structure and window declarations before decoder allocation. */
final class ZstdFrameInputStream extends FilterInputStream {
    private static final long FRAME_MAGIC = 0xfd2fb528L;
    private static final long SKIPPABLE_MAGIC_MIN = 0x184d2a50L;
    private static final long SKIPPABLE_MAGIC_MAX = 0x184d2a5fL;

    private final long maxWindowBytes;
    private final byte[] fields = new byte[14];
    private State state = State.MAGIC;
    private long littleEndianValue;
    private long payloadRemaining;
    private int fieldIndex;
    private int fieldLength;
    private int frameContentSizeFlag;
    private int dictionaryIdSize;
    private boolean singleSegment;
    private boolean checksum;
    private boolean sawFrame;
    private boolean eof;

    ZstdFrameInputStream(InputStream input, long maxWindowBytes) {
        super(Objects.requireNonNull(input, "input"));
        if (maxWindowBytes < 1) {
            throw new IllegalArgumentException("maxWindowBytes must be positive");
        }
        this.maxWindowBytes = maxWindowBytes;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value < 0) {
            verifyEnd();
        } else {
            accept(value);
        }
        return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        int read = super.read(bytes, offset, length);
        if (read < 0) {
            verifyEnd();
            return -1;
        }
        for (int index = offset; index < offset + read; index++) {
            accept(bytes[index] & 0xff);
        }
        return read;
    }

    private void accept(int value) throws IOException {
        switch (state) {
            case MAGIC -> acceptMagic(value);
            case SKIPPABLE_SIZE -> acceptSkippableSize(value);
            case SKIPPABLE_PAYLOAD -> acceptSkippablePayload();
            case FRAME_DESCRIPTOR -> acceptFrameDescriptor(value);
            case FRAME_FIELDS -> acceptFrameField(value);
            case BLOCK_HEADER -> acceptBlockHeader(value);
            case BLOCK_PAYLOAD -> acceptBlockPayload();
            case CHECKSUM -> acceptChecksum();
        }
    }

    private void acceptMagic(int value) throws IOException {
        appendLittleEndian(value);
        if (fieldIndex != Integer.BYTES) {
            return;
        }
        long magic = takeLittleEndian();
        if (magic == FRAME_MAGIC) {
            sawFrame = true;
            state = State.FRAME_DESCRIPTOR;
        } else if (magic >= SKIPPABLE_MAGIC_MIN && magic <= SKIPPABLE_MAGIC_MAX) {
            sawFrame = true;
            state = State.SKIPPABLE_SIZE;
        } else {
            throw malformed("A compressed journal segment has an invalid frame magic");
        }
    }

    private void acceptSkippableSize(int value) {
        appendLittleEndian(value);
        if (fieldIndex == Integer.BYTES) {
            payloadRemaining = takeLittleEndian();
            state = payloadRemaining == 0 ? State.MAGIC : State.SKIPPABLE_PAYLOAD;
        }
    }

    private void acceptSkippablePayload() {
        payloadRemaining--;
        if (payloadRemaining == 0) {
            state = State.MAGIC;
        }
    }

    private void acceptFrameDescriptor(int descriptor) throws IOException {
        if ((descriptor & 0x18) != 0) {
            throw malformed("A compressed journal frame uses reserved descriptor bits");
        }
        frameContentSizeFlag = descriptor >>> 6;
        singleSegment = (descriptor & 0x20) != 0;
        checksum = (descriptor & 0x04) != 0;
        dictionaryIdSize = switch (descriptor & 0x03) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 2;
            default -> 4;
        };
        int contentSizeBytes = switch (frameContentSizeFlag) {
            case 0 -> singleSegment ? 1 : 0;
            case 1 -> 2;
            case 2 -> 4;
            default -> 8;
        };
        fieldLength = (singleSegment ? 0 : 1) + dictionaryIdSize + contentSizeBytes;
        fieldIndex = 0;
        state = State.FRAME_FIELDS;
    }

    private void acceptFrameField(int value) throws IOException {
        fields[fieldIndex++] = (byte) value;
        if (fieldIndex != fieldLength) {
            return;
        }
        long window = singleSegment
                ? frameContentSize()
                : windowSize(fields[0] & 0xff);
        if (window > maxWindowBytes) {
            throw malformed("A compressed journal frame exceeds the configured window limit");
        }
        resetField();
        state = State.BLOCK_HEADER;
    }

    private long frameContentSize() {
        int size = switch (frameContentSizeFlag) {
            case 0 -> 1;
            case 1 -> 2;
            case 2 -> 4;
            default -> 8;
        };
        int offset = dictionaryIdSize;
        long contentSize = unsignedLittleEndian(fields, offset, size);
        return frameContentSizeFlag == 1 ? contentSize + 256 : contentSize;
    }

    private static long windowSize(int descriptor) {
        int exponent = descriptor >>> 3;
        int mantissa = descriptor & 0x07;
        long base = 1L << (10 + exponent);
        return base + (base >>> 3) * mantissa;
    }

    private void acceptBlockHeader(int value) throws IOException {
        appendLittleEndian(value);
        if (fieldIndex != 3) {
            return;
        }
        long header = takeLittleEndian();
        boolean last = (header & 1) != 0;
        int type = (int) ((header >>> 1) & 0x03);
        if (type == 3) {
            throw malformed("A compressed journal frame has a reserved block type");
        }
        long blockSize = header >>> 3;
        payloadRemaining = type == 1 ? 1 : blockSize;
        if (payloadRemaining != 0) {
            state = State.BLOCK_PAYLOAD;
            fieldLength = last ? 1 : 0;
        } else {
            finishBlock(last);
        }
    }

    private void acceptBlockPayload() {
        payloadRemaining--;
        if (payloadRemaining == 0) {
            finishBlock(fieldLength == 1);
        }
    }

    private void finishBlock(boolean last) {
        if (!last) {
            state = State.BLOCK_HEADER;
        } else if (checksum) {
            fieldIndex = 0;
            state = State.CHECKSUM;
        } else {
            state = State.MAGIC;
        }
    }

    private void acceptChecksum() {
        fieldIndex++;
        if (fieldIndex == Integer.BYTES) {
            resetField();
            state = State.MAGIC;
        }
    }

    private void appendLittleEndian(int value) {
        littleEndianValue |= (long) value << (Byte.SIZE * fieldIndex);
        fieldIndex++;
    }

    private long takeLittleEndian() {
        long value = littleEndianValue;
        resetField();
        return value;
    }

    private void resetField() {
        littleEndianValue = 0;
        fieldIndex = 0;
        fieldLength = 0;
    }

    private static long unsignedLittleEndian(byte[] bytes, int offset, int length) {
        if (length == Long.BYTES && (bytes[offset + length - 1] & 0x80) != 0) {
            return Long.MAX_VALUE;
        }
        long value = 0;
        for (int index = 0; index < length; index++) {
            value |= (long) (bytes[offset + index] & 0xff) << (Byte.SIZE * index);
        }
        return value;
    }

    private void verifyEnd() throws IOException {
        if (eof) {
            return;
        }
        eof = true;
        if (!sawFrame || state != State.MAGIC || fieldIndex != 0) {
            throw malformed("A compressed journal segment has a truncated frame");
        }
    }

    private static ZstdFrameValidationException malformed(String message) {
        return new ZstdFrameValidationException(message);
    }

    private enum State {
        MAGIC,
        SKIPPABLE_SIZE,
        SKIPPABLE_PAYLOAD,
        FRAME_DESCRIPTOR,
        FRAME_FIELDS,
        BLOCK_HEADER,
        BLOCK_PAYLOAD,
        CHECKSUM
    }

    static final class ZstdFrameValidationException extends IOException {
        private ZstdFrameValidationException(String message) {
            super(message);
        }
    }
}
