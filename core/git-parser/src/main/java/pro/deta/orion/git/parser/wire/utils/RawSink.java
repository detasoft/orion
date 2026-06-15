package pro.deta.orion.git.parser.wire.utils;

import io.netty.buffer.ByteBuf;

import java.util.Objects;

public final class RawSink {
    public void accept(Target target, ByteBuf input) {
        Objects.requireNonNull(target, "target").accept(Objects.requireNonNull(input, "input"));
    }

    public void close(Target target) {
        if (target != null) {
            target.close();
        }
    }

    public interface Target extends AutoCloseable {
        void accept(ByteBuf input);

        @Override
        default void close() {
        }
    }
}
