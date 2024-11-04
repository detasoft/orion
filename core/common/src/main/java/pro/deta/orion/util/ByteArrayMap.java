package pro.deta.orion.util;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ByteArrayMap {
    public static final int INT_SIZE = 4;
    public static final int SHORT_SIZE = 2;

    private final byte[] data;
    @Getter
    private final ByteBuffer byteBuffer;
    private final List<Entry> entries = new ArrayList<>();
    private final RunningLengthSize runningLengthSize;

    public ByteArrayMap(byte[] data, RunningLengthSize runningLengthSize) {
        this.data = data;
        this.byteBuffer = ByteBuffer.wrap(data);
        this.runningLengthSize = runningLengthSize;
    }

    public Entry readRLEEntry() {
        int length = readLength();
        ByteBuffer buffer = getByteBuffer();
        int beginIdx = buffer.position();
        if (length > buffer.remaining())
            throw new IllegalStateException(LogUtils.formatMessage("Specified Entry can't be read: {}[{}] but remains: {}", beginIdx, length, buffer.remaining()));
        else
            buffer.position(buffer.position() + length);
        return newEntry(beginIdx, length);
    }

    private Entry newEntry(int beginIdx, int length) {
        Entry e = new Entry(beginIdx, length);
        entries.add(e);
        return e;
    }

    private int readLength() {
        return switch (runningLengthSize) {
            case LENGTH_SIZE_2 -> getByteBuffer().getShort();
            case LENGTH_SIZE_4 -> getByteBuffer().getInt();
        };
    }

    public Entry read4ByteAsInt() {
        int beginIdx = getByteBuffer().position();
        getByteBuffer().position(beginIdx + INT_SIZE);
        return newEntry(beginIdx, INT_SIZE);
    }

    public void endParse() {
        ByteBuffer bb = getByteBuffer();
        int remaining = bb.remaining();
        if (remaining != 0) {
            throw new IllegalStateException(LogUtils.formatMessage("More data left in the array: {} remaining {}\n{}", bb.position(), remaining, readRemaining(bb)));
        }
    }

    private static String readRemaining(ByteBuffer bb) {
        return ByteBufferUtil.toHex(bb, 127);
    }


    @RequiredArgsConstructor
    @Getter
    public class Entry {
        private final int beginIdx;
        private final int length;

        public String getString() {
            return new String(data, beginIdx, length, StandardCharsets.UTF_8);
        }

        public byte[] getArray() {
            return Arrays.copyOfRange(data, beginIdx, beginIdx + length);
        }

        public int getInteger() {
            return fromByteArray(beginIdx);
        }

        // packing an array of 4 bytes to an int, big endian
        // operator precedence: <<, &, |
        // when operators of equal precedence (here bitwise OR) appear in the same expression, they are evaluated from left to right
        int fromByteArray(int beginIdx) {
            return ((data[beginIdx] & 0xFF) << 24) |
                    ((data[beginIdx + 1] & 0xFF) << 16) |
                    ((data[beginIdx + 2] & 0xFF) << 8) |
                    ((data[beginIdx + 3] & 0xFF) << 0);
        }

        @Override
        public String toString() {
            return new StringJoiner(",", Entry.class.getSimpleName(), ")")
                    .add("range: %d[%d]".formatted(beginIdx, length))
                    .toString();
        }

        public String getHexString(boolean wrapInNewLines) {
            if (length > 0) {
                String hex = ByteBufferUtil.toHex(ByteBuffer.wrap(getArray()), 63);
                if (wrapInNewLines)
                    return "\n" + hex + "\n";
                return hex;
            } else
                return "";
        }
    }

    @RequiredArgsConstructor
    public enum RunningLengthSize {
        LENGTH_SIZE_2(2), LENGTH_SIZE_4(4);

        @Getter
        private final int size;
    }
}
