package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.util.Objects;

public final class GitMinimalWireMachine implements AutoCloseable {
    private final GitFixedControlFrameReader controlReader;
    private final RawSinkFactory rawSinkFactory;
    private RawSink rawSink;
    private Phase phase = Phase.CONTROL;

    public GitMinimalWireMachine(
            ByteBufAllocator allocator,
            RawSinkFactory rawSinkFactory) {
        controlReader = new GitFixedControlFrameReader(Objects.requireNonNull(allocator, "allocator"));
        this.rawSinkFactory = Objects.requireNonNull(rawSinkFactory, "rawSinkFactory");
    }

    public boolean accept(ByteBuf input) {
        Objects.requireNonNull(input, "input");
        while (input.isReadable()) {
            if (phase == Phase.CONTROL) {
                GitFixedControlFrameReader.ControlReadState state = controlReader.accept(input);
                if (state == GitFixedControlFrameReader.ControlReadState.NEEDS_MORE_DATA) {
                    return true;
                }
                phase = Phase.RAW;
                continue;
            }
            if (phase == Phase.RAW) {
                forwardRaw(input);
                return true;
            }
        }
        return true;
    }

    @TestOnly
    ComposedState state() {
        return new ComposedState(
                phase,
                rawSink != null,
                controlReader.bufferedBytes());
    }

    private void forwardRaw(ByteBuf input) {
        if (!input.isReadable()) {
            return;
        }
        RawSink sink = rawSink();
        ByteBuf raw = input.readRetainedSlice(input.readableBytes());
        sink.accept(raw);
    }

    private RawSink rawSink() {
        if (rawSink == null) {
            rawSink = Objects.requireNonNull(rawSinkFactory.create(control()), "rawSink");
            controlReader.releaseCompletedStorage();
        }
        return rawSink;
    }

    private ByteBuf control() {
        return controlReader.bytes();
    }

    @Override
    public void close() {
        try {
            if (rawSink != null) {
                rawSink.close();
            }
        } finally {
            controlReader.close();
        }
    }

    enum Phase {
        CONTROL,
        RAW
    }

    record ComposedState(
            Phase phase,
            boolean rawSinkCreated,
            int bufferedControlBytes) {
    }

    @FunctionalInterface
    public interface RawSinkFactory {
        RawSink create(ByteBuf control);
    }

    public interface RawSink extends AutoCloseable {
        void accept(ByteBuf input);

        @Override
        default void close() {
        }
    }
}
