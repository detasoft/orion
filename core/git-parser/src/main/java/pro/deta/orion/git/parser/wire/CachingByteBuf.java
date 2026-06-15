package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.WrappedByteBuf;

import java.util.Objects;

public final class CachingByteBuf extends WrappedByteBuf {
    private final int fixedCapacity;
    private final Append append;

    public CachingByteBuf(ByteBufAllocator allocator, ByteBuf input, int fixedCapacity, Mode mode) {
        super(newBackingBuffer(allocator, input, fixedCapacity, mode));
        this.fixedCapacity = fixedCapacity;
        this.append = mode.append;
        try {
            append(input);
        } catch (RuntimeException | Error e) {
            release();
            throw e;
        }
    }

    public int append(ByteBuf input) {
        Objects.requireNonNull(input, "input");

        return append.append(buf, input, fixedCapacity);
    }

    public boolean isComplete() {
        return readableBytes() >= fixedCapacity;
    }

    private static ByteBuf newBackingBuffer(ByteBufAllocator allocator, ByteBuf input, int capacity, Mode mode) {
        Objects.requireNonNull(allocator, "allocator");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(mode, "mode");
        validateCapacity(capacity);
        return switch (mode) {
            case BUFFERED -> allocator.buffer(Math.min(input.readableBytes(), capacity), capacity);
            case COMPOSITE -> allocator.compositeBuffer();
        };
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
        BUFFERED(new BufferedAppend()),
        COMPOSITE(new CompositeAppend());

        private final Append append;

        Mode(Append append) {
            this.append = append;
        }
    }

    private interface Append {
        int append(ByteBuf output, ByteBuf input, int capacity);
    }

    private static final class BufferedAppend implements Append {
        @Override
        public int append(ByteBuf output, ByteBuf input, int capacity) {
            int length = appendLength(output, input, capacity);
            output.writeBytes(input, length);
            return length;
        }
    }

    private static final class CompositeAppend implements Append {
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
