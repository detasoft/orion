package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.util.Objects;

public final class GitMinimalWireMachine implements AutoCloseable {

    private final ByteBufAllocator allocator;
    private final RawSinkFactory rawSinkFactory;
    private GitFixedControlFrameReader controlReader;
    private RawPayload rawPayload;
    private Phase phase = Phase.CONTROL;

    public GitMinimalWireMachine(
            ByteBufAllocator allocator,
            RawSinkFactory rawSinkFactory) {
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        controlReader = new GitFixedControlFrameReader(this.allocator);
        this.rawSinkFactory = Objects.requireNonNull(rawSinkFactory, "rawSinkFactory");
    }

    public boolean accept(ByteBuf input) {
        Objects.requireNonNull(input, "input");
        while (input.isReadable()) {
            if (phase == Phase.CONTROL) {
                ControlState state = controlReader.accept(input);
                if (state instanceof ControlState.MoreDataNeeded) {
                    return true;
                }
                ControlState.ControlSuccess control = (ControlState.ControlSuccess) state;
                resetControlReader();
                int payloadLength = control.payloadLength();
                if (payloadLength > 0) {
                    rawPayload = new RawPayload(control, payloadLength);
                    phase = Phase.RAW;
                }
                continue;
            }
            if (phase == Phase.RAW) {
                forwardRawPayload(input);
                if (rawPayload != null && !rawPayload.isComplete()) {
                    return true;
                }
                completeRawPayload();
            }
        }
        return true;
    }

    @TestOnly
    ComposedState state() {
        return new ComposedState(
                phase,
                rawPayload != null && rawPayload.sinkCreated());
    }

    private void forwardRawPayload(ByteBuf input) {
        if (!input.isReadable()) {
            return;
        }
        rawPayload.forward(input);
    }

    private void completeRawPayload() {
        if (rawPayload != null) {
            rawPayload.close();
            rawPayload = null;
        }
        phase = Phase.CONTROL;
    }

    private void resetControlReader() {
        controlReader = new GitFixedControlFrameReader(allocator);
    }

    @Override
    public void close() {
        if (rawPayload != null) {
            rawPayload.close();
        }
    }

    enum Phase {
        CONTROL,
        RAW
    }

    record ComposedState(
            Phase phase,
            boolean rawSinkCreated) {
    }

    @FunctionalInterface
    public interface RawSinkFactory {
        RawSink create(ControlState.ControlSuccess control);
    }

    public interface RawSink extends AutoCloseable {
        void accept(ByteBuf input);

        @Override
        default void close() {
        }
    }

    private final class RawPayload implements AutoCloseable {
        private final ControlState.ControlSuccess control;
        private final FixedByteBufForwarder forwarder;
        private RawSink sink;

        private RawPayload(ControlState.ControlSuccess control, int length) {
            this.control = control;
            forwarder = new FixedByteBufForwarder(length);
        }

        private void forward(ByteBuf input) {
            forwarder.forward(input, sink()::accept);
        }

        private boolean isComplete() {
            return forwarder.isComplete();
        }

        private boolean sinkCreated() {
            return sink != null;
        }

        private RawSink sink() {
            if (sink == null) {
                sink = Objects.requireNonNull(rawSinkFactory.create(control), "rawSink");
            }
            return sink;
        }

        @Override
        public void close() {
            if (sink != null) {
                sink.close();
            }
        }
    }
}
