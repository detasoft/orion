package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.git.parser.wire.utils.RawSink;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.util.Objects;

public final class GitMinimalWireMachine implements AutoCloseable {

    private final ByteBufAllocator allocator;
    private final RawTargetFactory rawTargetFactory;
    private final GitFixedControlFrameReader controlReader;
    private final RawSink rawSink = new RawSink();

    private ControlState currentControlState = ControlState.ControlEmpty.INSTANCE;
    private RawPayload rawPayload;
    private Phase phase = Phase.CONTROL;

    public GitMinimalWireMachine(
            ByteBufAllocator allocator,
            RawTargetFactory rawTargetFactory) {
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.controlReader = new GitFixedControlFrameReader(this.allocator);
        this.rawTargetFactory = Objects.requireNonNull(rawTargetFactory, "rawTargetFactory");
    }

    public boolean accept(ByteBuf input) {
        Objects.requireNonNull(input, "input");
        while (input.isReadable()) {
            if (phase == Phase.CONTROL) {
                currentControlState = controlReader.accept(currentControlState, input);
                if (currentControlState instanceof ControlState.MoreDataNeeded) {
                    return true;
                } else if (currentControlState instanceof ControlState.ControlSuccess controlSuccess) {
                    currentControlState = ControlState.ControlEmpty.INSTANCE;
                    rawPayload = new RawPayload(controlSuccess);
                    phase = Phase.RAW;
                }
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
                rawPayload != null && rawPayload.targetCreated());
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
            boolean rawTargetCreated) {
    }

    @FunctionalInterface
    public interface RawTargetFactory {
        RawSink.Target create(ControlState.ControlSuccess control);
    }


    private final class RawPayload implements AutoCloseable {
        private final ControlState.ControlSuccess control;
        private final FixedByteBufForwarder forwarder;
        private RawSink.Target target;

        private RawPayload(ControlState.ControlSuccess control) {
            this.control = control;
            forwarder = new FixedByteBufForwarder(control.payloadLength());
        }

        private void forward(ByteBuf input) {
            if (forwarder.isComplete()) {
                return;
            }
            forwarder.forward(input, slice -> rawSink.accept(target(), slice));
        }

        private boolean isComplete() {
            return forwarder.isComplete();
        }

        private boolean targetCreated() {
            return target != null;
        }

        private RawSink.Target target() {
            if (target == null) {
                target = Objects.requireNonNull(rawTargetFactory.create(control), "rawTarget");
            }
            return target;
        }

        @Override
        public void close() {
            rawSink.close(target);
        }
    }
}
