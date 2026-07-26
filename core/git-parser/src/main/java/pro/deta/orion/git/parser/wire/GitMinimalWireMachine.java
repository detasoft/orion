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

    private final Context context;
    private Phase phase;

    public GitMinimalWireMachine(
            ByteBufAllocator allocator,
            FrameConsumer frameConsumer,
            StructuredPayloadConsumer structuredPayloadConsumer,
            RawTargetFactory rawTargetFactory) {
        ByteBufAllocator checkedAllocator = Objects.requireNonNull(allocator, "allocator");
        this.context = new Context(
                checkedAllocator,
                Objects.requireNonNull(rawTargetFactory, "rawTargetFactory"),
                Objects.requireNonNull(frameConsumer, "frameConsumer"),
                Objects.requireNonNull(structuredPayloadConsumer, "structuredPayloadConsumer"),
                new GitFixedControlFrameReader(checkedAllocator));
        this.phase = new ControlPhase(ControlState.ControlEmpty.INSTANCE);
    }

    public boolean accept(ByteBuf input) {
        Objects.requireNonNull(input, "input");
        while (input.isReadable()) {
            phase = phase.accept(input);
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
        StructuredPayloadPhase structuredPayloadPhase = new StructuredPayloadPhase(control);
        if (control.payloadLength() == 0) {
            return structuredPayloadPhase.accept(Unpooled.EMPTY_BUFFER);
        }
        return structuredPayloadPhase;
    }

    @TestOnly
    ComposedState state() {
        return new ComposedState(phase);
    }

    @Override
    public void close() {
        try {
            phase.close();
        } finally {
            phase = new ControlPhase(ControlState.ControlEmpty.INSTANCE);
        }
    }

    private record Context(
            ByteBufAllocator allocator,
            RawTargetFactory rawTargetFactory,
            FrameConsumer frameConsumer,
            StructuredPayloadConsumer structuredPayloadConsumer,
            GitFixedControlFrameReader controlReader) {
    }

    sealed interface Phase extends AutoCloseable permits ControlPhase, RawStreamPhase, StructuredPayloadPhase {
        Phase accept(ByteBuf input);

        @Override
        default void close() {
        }
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

    final class ControlPhase implements Phase {
        private final ControlState state;

        private ControlPhase(ControlState state) {
            this.state = state;
        }

        @Override
        public Phase accept(ByteBuf input) {
            ControlState nextControlState = context.controlReader.accept(state, input);
            if (nextControlState instanceof ControlState.MoreDataNeeded) {
                return new ControlPhase(nextControlState);
            }
            if (nextControlState instanceof ControlState.ControlSuccess controlSuccess) {
                CallbackFlowControl flowControl = new CallbackFlowControl();
                context.frameConsumer.accept(controlSuccess, flowControl);
                return phaseAfterControl(controlSuccess, flowControl);
            }
            return new ControlPhase(nextControlState);
        }

        ControlState state() {
            return state;
        }

        @Override
        public void close() {
            if (state instanceof ControlState.MoreDataNeeded moreDataNeeded) {
                moreDataNeeded.fragment().release();
                throw new IllegalStateException("Incomplete Git pkt-line header");
            }
        }
    }

    final class StructuredPayloadPhase implements Phase {
        private final ControlState.ControlSuccess control;
        private CachingByteBuf fragment;
        private boolean complete;

        private StructuredPayloadPhase(ControlState.ControlSuccess control) {
            this.control = control;
        }

        @Override
        public Phase accept(ByteBuf input) {
            read(input);
            if (!complete) {
                return this;
            }
            return new ControlPhase(ControlState.ControlEmpty.INSTANCE);
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
                fragment = new CachingByteBuf(context.allocator, input, payloadLength, CachingByteBuf.Mode.BUFFERED);
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
                context.structuredPayloadConsumer.accept(control, payload);
            } catch (RuntimeException | Error e) {
                payload.release();
                throw e;
            }
            complete = true;
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
            if (!complete) {
                throw new IllegalStateException("Incomplete Git pkt-line payload");
            }
        }
    }

    final class RawStreamPhase implements Phase {
        private final ControlState.ControlSuccess control;
        private RawSink.Target target;

        private RawStreamPhase(ControlState.ControlSuccess control) {
            this.control = control;
        }

        @Override
        public Phase accept(ByteBuf input) {
            if (!input.isReadable()) {
                return this;
            }
            ByteBuf slice = input.readRetainedSlice(input.readableBytes());
            try {
                target().accept(slice);
            } catch (RuntimeException | Error e) {
                slice.release();
                throw e;
            }
            return this;
        }

        ControlState.ControlSuccess control() {
            return control;
        }

        boolean targetCreated() {
            return target != null;
        }

        private RawSink.Target target() {
            if (target == null) {
                target = Objects.requireNonNull(context.rawTargetFactory.create(control), "rawTarget");
            }
            return target;
        }

        @Override
        public void close() {
            if (target != null) {
                target.close();
            }
        }
    }
}
