package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;

import java.util.Objects;

public final class CachingByteBuf {
    private CachingByteBuf() {
    }

    public static ByteBuf start(ByteBufAllocator allocator, ByteBuf input, int capacity, Mode mode) {
        Objects.requireNonNull(allocator, "allocator");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(mode, "mode");
        validateCapacity(capacity);

        Caching caching = mode.caching;
        ByteBuf output = caching.allocate(allocator, input, capacity);
        try {
            append(output, input, capacity, mode);
            return output;
        } catch (RuntimeException | Error e) {
            output.release();
            throw e;
        }
    }

    public static int append(ByteBuf output, ByteBuf input, int capacity, Mode mode) {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(mode, "mode");
        validateCapacity(capacity);

        return mode.caching.append(output, input, capacity);
    }

    public static boolean isComplete(ByteBuf output, int capacity) {
        Objects.requireNonNull(output, "output");
        validateCapacity(capacity);
        return output.readableBytes() >= capacity;
    }

    private static int appendLength(ByteBuf output, ByteBuf input, int capacity) {
        int missing = capacity - output.readableBytes();
        if (missing < 0) {
            throw new IllegalArgumentException("Output already exceeds fixed capacity");
        }
        return Math.min(missing, input.readableBytes());
    }

    private static void validateCapacity(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("Capacity must be non-negative");
        }
    }

    public enum Mode {
        BUFFERED(new BufferedCaching()),
        COMPOSITE(new CompositeCaching());

        private final Caching caching;

        Mode(Caching caching) {
            this.caching = caching;
        }
    }

    private interface Caching {
        ByteBuf allocate(ByteBufAllocator allocator, ByteBuf input, int capacity);

        int append(ByteBuf output, ByteBuf input, int capacity);
    }

    private static final class BufferedCaching implements Caching {
        @Override
        public ByteBuf allocate(ByteBufAllocator allocator, ByteBuf input, int capacity) {
            return allocator.buffer(Math.min(input.readableBytes(), capacity), capacity);
        }

        @Override
        public int append(ByteBuf output, ByteBuf input, int capacity) {
            int length = appendLength(output, input, capacity);
            output.writeBytes(input, length);
            return length;
        }
    }

    private static final class CompositeCaching implements Caching {
        @Override
        public ByteBuf allocate(ByteBufAllocator allocator, ByteBuf input, int capacity) {
            return allocator.compositeBuffer();
        }

        @Override
        public int append(ByteBuf output, ByteBuf input, int capacity) {
            if (!(output instanceof CompositeByteBuf composite)) {
                throw new IllegalArgumentException("Composite mode requires CompositeByteBuf output");
            }
            int length = appendLength(output, input, capacity);
            if (length == 0) {
                return 0;
            }
            ByteBuf slice = input.readRetainedSlice(length);
            try {
                composite.addComponent(true, slice);
            } catch (RuntimeException | Error e) {
                slice.release();
                throw e;
            }
            return length;
        }
    }
}
