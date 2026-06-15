package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;

import java.util.Objects;

final class FixedByteBufForwarder {
    private int remaining;

    FixedByteBufForwarder(int length) {
        if (length < 0) {
            throw new IllegalArgumentException("Length must be non-negative");
        }
        remaining = length;
    }

    int remaining() {
        return remaining;
    }

    boolean isComplete() {
        return remaining == 0;
    }

    int forward(ByteBuf input, Sink sink) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(sink, "sink");
        int length = Math.min(remaining, input.readableBytes());
        if (length == 0) {
            return 0;
        }
        ByteBuf slice = input.readRetainedSlice(length);
        try {
            sink.accept(slice);
        } catch (RuntimeException | Error e) {
            slice.release();
            throw e;
        }
        remaining -= length;
        return length;
    }

    @FunctionalInterface
    interface Sink {
        void accept(ByteBuf input);
    }
}
