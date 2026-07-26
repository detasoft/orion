package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.git.parser.wire.utils.RawSink;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.util.Objects;

/**
 * Minimal streaming pkt-line machine used to prove the native Git wire parser
 * boundary before the production session API is introduced. The machine owns
 * the durable parser phase, delegates fixed control-frame reads to stateless
 * helpers, routes structured pkt-line payload bytes through a bounded
 * structured callback, and forwards raw stream bytes to a lazily created raw
 * target only after the control callback requests the raw phase.
 */
public final class GitMinimalWireMachine implements AutoCloseable {

    private final ByteBufAllocator allocator;
    private final RawTargetFactory rawTargetFactory;
    private final FrameConsumer frameConsumer;
    private final StructuredPayloadConsumer structuredPayloadConsumer;
    private final GitFixedControlFrameReader controlReader;
    private final RawSink rawSink = new RawSink();

    private Phase phase = new ControlPhase(ControlState.ControlEmpty.INSTANCE);

    public GitMinimalWireMachine(
            ByteBufAllocator allocator,
            FrameConsumer frameConsumer,
            StructuredPayloadConsumer structuredPayloadConsumer,
            RawTargetFactory rawTargetFactory) {
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.controlReader = new GitFixedControlFrameReader(this.allocator);
        this.frameConsumer = Objects.requireNonNull(frameConsumer, "frameConsumer");
        this.structuredPayloadConsumer = Objects.requireNonNull(structuredPayloadConsumer, "structuredPayloadConsumer");
        this.rawTargetFactory = Objects.requireNonNull(rawTargetFactory, "rawTargetFactory");
    }

    public boolean accept(ByteBuf input) {
        Objects.requireNonNull(input, "input");
        while (input.isReadable()) {
            if (phase instanceof ControlPhase controlPhase) {
                ControlState nextControlState = controlReader.accept(controlPhase.state(), input);
                if (nextControlState instanceof ControlState.MoreDataNeeded) {
                    phase = new ControlPhase(nextControlState);
                    return true;
                } else if (nextControlState instanceof ControlState.ControlSuccess controlSuccess) {
                    CallbackFlowControl flowControl = new CallbackFlowControl();
                    frameConsumer.accept(controlSuccess, flowControl);
                    phase = phaseAfterControl(controlSuccess, flowControl);
                } else {
                    phase = new ControlPhase(nextControlState);
                }
            }
            if (phase instanceof StructuredPayloadPhase structuredPayloadPhase) {
                structuredPayloadPhase.read(input);
                if (!structuredPayloadPhase.isComplete()) {
                    return true;
                }
                phase = new ControlPhase(ControlState.ControlEmpty.INSTANCE);
            }
            if (phase instanceof RawStreamPhase rawStreamPhase) {
                rawStreamPhase.forward(input);
                return true;
            }
        }
        return true;
    }

    private Phase phaseAfterControl(ControlState.ControlSuccess control, CallbackFlowControl flowControl) {
        if (flowControl.forwardRawPayload) {
            if (control.type() == ControlState.ControlType.DATA) {
                throw new IllegalStateException("Raw payload forwarding cannot start before a DATA payload is handled");
            }
            return new RawStreamPhase(control);
        }
        if (control.type() != ControlState.ControlType.DATA) {
            return new ControlPhase(ControlState.ControlEmpty.INSTANCE);
        }
        return new StructuredPayloadPhase(control);
    }

    @TestOnly
    ComposedState state() {
        return new ComposedState(phase);
    }

    @Override
    public void close() {
        if (phase instanceof ControlPhase controlPhase
                && controlPhase.state() instanceof ControlState.MoreDataNeeded moreDataNeeded) {
            moreDataNeeded.fragment().release();
            phase = new ControlPhase(ControlState.ControlEmpty.INSTANCE);
            throw new IllegalStateException("Incomplete Git pkt-line header");
        }
        if (phase instanceof StructuredPayloadPhase structuredPayloadPhase) {
            structuredPayloadPhase.close();
            if (!structuredPayloadPhase.isComplete()) {
                phase = new ControlPhase(ControlState.ControlEmpty.INSTANCE);
                throw new IllegalStateException("Incomplete Git pkt-line payload");
            }
        }
        if (phase instanceof RawStreamPhase rawStreamPhase) {
            rawStreamPhase.close();
            phase = new ControlPhase(ControlState.ControlEmpty.INSTANCE);
        }
    }

    sealed interface Phase permits ControlPhase, RawStreamPhase, StructuredPayloadPhase {
    }

    record ControlPhase(ControlState state) implements Phase {
    }

    record ComposedState(
            Phase phase) {
    }

    @FunctionalInterface
    public interface RawTargetFactory {
        RawSink.Target create(ControlState.ControlSuccess control);
    }

    @FunctionalInterface
    public interface FrameConsumer {
        void accept(ControlState.ControlSuccess control, FlowControl flow);
    }

    @FunctionalInterface
    public interface StructuredPayloadConsumer {
        void accept(ControlState.ControlSuccess control, ByteBuf payload);
    }

    public interface FlowControl {
        void forwardRawPayload();
    }

    private static final class CallbackFlowControl implements FlowControl {
        private boolean forwardRawPayload;

        @Override
        public void forwardRawPayload() {
            forwardRawPayload = true;
        }
    }

    final class StructuredPayloadPhase implements Phase, AutoCloseable {
        private final ControlState.ControlSuccess control;
        private CachingByteBuf fragment;
        private boolean complete;

        private StructuredPayloadPhase(ControlState.ControlSuccess control) {
            this.control = control;
        }

        private void read(ByteBuf input) {
            if (complete) {
                return;
            }
            int payloadLength = control.payloadLength();
            if (payloadLength == 0) {
                deliver(Unpooled.EMPTY_BUFFER.retainedDuplicate());
                return;
            }
            if (fragment == null && input.readableBytes() >= payloadLength) {
                deliver(input.readRetainedSlice(payloadLength));
                return;
            }
            if (fragment == null) {
                fragment = new CachingByteBuf(allocator, input, payloadLength, CachingByteBuf.Mode.BUFFERED);
            } else {
                fragment.append(input);
            }
            if (fragment.isComplete()) {
                CachingByteBuf completedFragment = fragment;
                fragment = null;
                deliver(completedFragment);
            }
        }

        private void deliver(ByteBuf payload) {
            try {
                structuredPayloadConsumer.accept(control, payload);
            } catch (RuntimeException | Error e) {
                payload.release();
                throw e;
            }
            complete = true;
        }

        private boolean isComplete() {
            return complete;
        }

        ControlState.ControlSuccess control() {
            return control;
        }

        int remaining() {
            if (complete) {
                return 0;
            }
            if (fragment == null) {
                return control.payloadLength();
            }
            return control.payloadLength() - fragment.readableBytes();
        }

        @Override
        public void close() {
            if (fragment != null) {
                fragment.release();
                fragment = null;
            }
        }
    }

    final class RawStreamPhase implements Phase, AutoCloseable {
        private final ControlState.ControlSuccess control;
        private RawSink.Target target;

        private RawStreamPhase(ControlState.ControlSuccess control) {
            this.control = control;
        }

        private void forward(ByteBuf input) {
            if (!input.isReadable()) {
                return;
            }
            ByteBuf slice = input.readRetainedSlice(input.readableBytes());
            try {
                rawSink.accept(target(), slice);
            } catch (RuntimeException | Error e) {
                slice.release();
                throw e;
            }
        }

        ControlState.ControlSuccess control() {
            return control;
        }

        boolean targetCreated() {
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
