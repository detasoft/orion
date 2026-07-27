package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.git.parser.wire.utils.RawSink;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.util.Objects;
import java.util.Optional;

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
                new GitFixedControlFrameReader(checkedAllocator),
                null);
        this.phase = newControlPhase();
    }

    <T> GitMinimalWireMachine(
            ByteBufAllocator allocator,
            Class<T> resultType,
            SemanticPhase initialPhase,
            RawTargetFactory rawTargetFactory) {
        ByteBufAllocator checkedAllocator = Objects.requireNonNull(allocator, "allocator");
        this.context = new Context(
                checkedAllocator,
                Objects.requireNonNull(rawTargetFactory, "rawTargetFactory"),
                null,
                null,
                new GitFixedControlFrameReader(checkedAllocator),
                new SemanticContext(
                        Objects.requireNonNull(resultType, "resultType"),
                        Objects.requireNonNull(initialPhase, "initialPhase")));
        this.phase = newControlPhase();
    }

    public boolean accept(ByteBuf input) {
        Objects.requireNonNull(input, "input");
        try {
            while (input.isReadable()) {
                phase = phase.accept(input);
            }
        } catch (GitWireException e) {
            if (!context.hasSemanticContext()) {
                throw e;
            }
            phase.abort();
            context.semanticContext.fail(e.error());
            phase = new FailedPhase();
        }
        return true;
    }

    public <T> Optional<GitWireOutcome<T>> outcome(Class<T> type) {
        SemanticContext semanticContext = context.requireSemanticContext(type);
        if (semanticContext.outcome == null) {
            return Optional.empty();
        }
        if (semanticContext.outcome instanceof GitWireOutcome.Success<?> success) {
            return Optional.of(new GitWireOutcome.Success<>(type.cast(success.value())));
        }
        GitWireOutcome.Failure<?> failure = (GitWireOutcome.Failure<?>) semanticContext.outcome;
        return Optional.of(new GitWireOutcome.Failure<>(failure.failure()));
    }

    public <T> T result(Class<T> type) {
        GitWireOutcome<T> completedOutcome = outcome(type)
                .orElseThrow(() -> new IllegalStateException("Git wire semantic result is not complete"));
        if (completedOutcome instanceof GitWireOutcome.Success<T> success) {
            return success.value();
        }
        GitWireOutcome.Failure<T> failure = (GitWireOutcome.Failure<T>) completedOutcome;
        throw new GitWireException(failure.failure().error());
    }

    private Phase phaseAfterControl(
            ControlState.ControlSuccess control,
            CallbackFlowControl flowControl,
            long packetIndex,
            long byteOffset) {
        if (flowControl.forwardRawPayload) {
            if (control.type() == ControlState.ControlType.DATA) {
                throw new IllegalStateException("Raw payload forwarding cannot start before a DATA payload is handled");
            }
            context.completePacket(control);
            return new RawStreamPhase(control);
        }
        if (control.type() != ControlState.ControlType.DATA) {
            context.completePacket(control);
            return newControlPhase();
        }
        StructuredPayloadPhase structuredPayloadPhase = new StructuredPayloadPhase(control, packetIndex, byteOffset);
        if (control.payloadLength() == 0) {
            return structuredPayloadPhase.accept(Unpooled.EMPTY_BUFFER);
        }
        return structuredPayloadPhase;
    }

    private ControlPhase newControlPhase() {
        return new ControlPhase(ControlState.ControlEmpty.INSTANCE, context.nextPacketIndex(), context.nextByteOffset());
    }

    private Phase phaseAfterSemanticTransition(SemanticTransition transition) {
        if (transition instanceof SemanticTransition.Next next) {
            context.semanticContext.phase = next.phase();
            return newControlPhase();
        }
        SemanticTransition.Complete<?> complete = (SemanticTransition.Complete<?>) transition;
        context.semanticContext.complete(complete);
        return new CompletedPhase();
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
            phase = newControlPhase();
        }
    }

    private static final class Context {
        private final ByteBufAllocator allocator;
        private final RawTargetFactory rawTargetFactory;
        private final FrameConsumer frameConsumer;
        private final StructuredPayloadConsumer structuredPayloadConsumer;
        private final GitFixedControlFrameReader controlReader;
        private final SemanticContext semanticContext;
        private long nextPacketIndex;
        private long nextByteOffset;

        private Context(
                ByteBufAllocator allocator,
                RawTargetFactory rawTargetFactory,
                FrameConsumer frameConsumer,
                StructuredPayloadConsumer structuredPayloadConsumer,
                GitFixedControlFrameReader controlReader,
                SemanticContext semanticContext) {
            this.allocator = allocator;
            this.rawTargetFactory = rawTargetFactory;
            this.frameConsumer = frameConsumer;
            this.structuredPayloadConsumer = structuredPayloadConsumer;
            this.controlReader = controlReader;
            this.semanticContext = semanticContext;
        }

        private long nextPacketIndex() {
            return nextPacketIndex;
        }

        private long nextByteOffset() {
            return nextByteOffset;
        }

        private void completePacket(ControlState.ControlSuccess control) {
            nextPacketIndex++;
            nextByteOffset += control.length();
        }

        private void forwardRawBytes(int length) {
            nextByteOffset += length;
        }

        private boolean hasSemanticContext() {
            return semanticContext != null;
        }

        private <T> SemanticContext requireSemanticContext(Class<T> resultType) {
            Objects.requireNonNull(resultType, "resultType");
            if (semanticContext == null) {
                throw new IllegalStateException("Git wire machine has no semantic result");
            }
            if (semanticContext.resultType != resultType) {
                throw new IllegalArgumentException(
                        "Git wire machine result type is " + semanticContext.resultType.getSimpleName()
                                + ", not " + resultType.getSimpleName());
            }
            return semanticContext;
        }
    }

    private static final class SemanticContext {
        private final Class<?> resultType;
        private final GitWireValueStack values = new GitWireValueStack();
        private SemanticPhase phase;
        private GitWireOutcome<?> outcome;

        private SemanticContext(Class<?> resultType, SemanticPhase phase) {
            this.resultType = resultType;
            this.phase = phase;
        }

        private void complete(SemanticTransition.Complete<?> complete) {
            if (complete.type() != resultType) {
                throw new IllegalStateException(
                        "Git wire semantic phase completed " + complete.type().getSimpleName()
                                + " but machine expects " + resultType.getSimpleName());
            }
            pushCompleteValue(complete);
            outcome = new GitWireOutcome.Success<>(values.peek(resultType));
        }

        private <T> void pushCompleteValue(SemanticTransition.Complete<T> complete) {
            values.push(complete.type(), complete.value());
        }

        private void fail(GitWireError error) {
            GitWireFailure failure = new GitWireFailure(error);
            values.push(GitWireFailure.class, failure);
            outcome = new GitWireOutcome.Failure<>(failure);
        }
    }

    sealed interface Phase extends AutoCloseable
            permits CompletedPhase, ControlPhase, FailedPhase, RawStreamPhase, StructuredPayloadPhase {
        Phase accept(ByteBuf input);

        default void abort() {
            close();
        }

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

    @FunctionalInterface
    interface SemanticPhase {
        SemanticTransition accept(
                ControlState.ControlSuccess control,
                ByteBuf payload,
                GitWireValueStack values);

        default void close(GitWireValueStack values, long packetIndex, long byteOffset) {
        }
    }

    sealed interface SemanticTransition
            permits SemanticTransition.Complete, SemanticTransition.Next {

        record Next(SemanticPhase phase) implements SemanticTransition {
            public Next {
                Objects.requireNonNull(phase, "phase");
            }
        }

        record Complete<T>(Class<T> type, T value) implements SemanticTransition {
            public Complete {
                Objects.requireNonNull(type, "type");
                Objects.requireNonNull(value, "value");
            }
        }
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
        private final long packetIndex;
        private final long byteOffset;

        private ControlPhase(ControlState state, long packetIndex, long byteOffset) {
            this.state = state;
            this.packetIndex = packetIndex;
            this.byteOffset = byteOffset;
        }

        @Override
        public Phase accept(ByteBuf input) {
            ControlState nextControlState = context.controlReader.accept(state, input, packetIndex, byteOffset);
            if (nextControlState instanceof ControlState.MoreDataNeeded) {
                return new ControlPhase(nextControlState, packetIndex, byteOffset);
            }
            if (nextControlState instanceof ControlState.ControlSuccess controlSuccess) {
                if (context.hasSemanticContext()) {
                    return phaseAfterSemanticControl(controlSuccess);
                }
                CallbackFlowControl flowControl = new CallbackFlowControl();
                context.frameConsumer.accept(controlSuccess, flowControl);
                return phaseAfterControl(controlSuccess, flowControl, packetIndex, byteOffset);
            }
            return new ControlPhase(nextControlState, packetIndex, byteOffset);
        }

        private Phase phaseAfterSemanticControl(ControlState.ControlSuccess control) {
            if (control.type() == ControlState.ControlType.DATA) {
                StructuredPayloadPhase structuredPayloadPhase =
                        new StructuredPayloadPhase(control, packetIndex, byteOffset);
                if (control.payloadLength() == 0) {
                    return structuredPayloadPhase.accept(Unpooled.EMPTY_BUFFER);
                }
                return structuredPayloadPhase;
            }
            SemanticTransition transition =
                    context.semanticContext.phase.accept(control, Unpooled.EMPTY_BUFFER, context.semanticContext.values);
            context.completePacket(control);
            return phaseAfterSemanticTransition(transition);
        }

        ControlState state() {
            return state;
        }

        @Override
        public void close() {
            if (state instanceof ControlState.MoreDataNeeded moreDataNeeded) {
                moreDataNeeded.fragment().release();
                throw GitWireException.of(
                        GitWireError.Kind.INCOMPLETE_HEADER,
                        GitWireError.Phase.CONTROL_HEADER,
                        packetIndex,
                        byteOffset,
                        "Incomplete Git pkt-line header");
            }
        }

        @Override
        public void abort() {
            if (state instanceof ControlState.MoreDataNeeded moreDataNeeded) {
                moreDataNeeded.fragment().release();
            }
        }
    }

    final class StructuredPayloadPhase implements Phase {
        private final ControlState.ControlSuccess control;
        private final long packetIndex;
        private final long byteOffset;
        private CachingByteBuf fragment;
        private boolean complete;
        private Phase nextPhase;

        private StructuredPayloadPhase(ControlState.ControlSuccess control, long packetIndex, long byteOffset) {
            this.control = control;
            this.packetIndex = packetIndex;
            this.byteOffset = byteOffset;
        }

        @Override
        public Phase accept(ByteBuf input) {
            read(input);
            if (!complete) {
                return this;
            }
            return nextPhase;
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
            if (context.hasSemanticContext()) {
                try {
                    SemanticTransition transition =
                            context.semanticContext.phase.accept(control, payload, context.semanticContext.values);
                    context.completePacket(control);
                    complete = true;
                    nextPhase = phaseAfterSemanticTransition(transition);
                } finally {
                    payload.release();
                }
                return;
            }
            try {
                context.structuredPayloadConsumer.accept(control, payload);
            } catch (RuntimeException | Error e) {
                payload.release();
                throw e;
            }
            context.completePacket(control);
            complete = true;
            nextPhase = newControlPhase();
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
                throw GitWireException.of(
                        GitWireError.Kind.INCOMPLETE_PAYLOAD,
                        GitWireError.Phase.STRUCTURED_PAYLOAD,
                        packetIndex,
                        byteOffset,
                        "Incomplete Git pkt-line payload");
            }
        }

        @Override
        public void abort() {
            if (fragment != null) {
                fragment.release();
                fragment = null;
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
            int length = slice.readableBytes();
            try {
                target().accept(slice);
            } catch (RuntimeException | Error e) {
                slice.release();
                throw e;
            }
            context.forwardRawBytes(length);
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

    final class CompletedPhase implements Phase {
        @Override
        public Phase accept(ByteBuf input) {
            throw new IllegalStateException("Git wire machine is already complete");
        }
    }

    final class FailedPhase implements Phase {
        @Override
        public Phase accept(ByteBuf input) {
            throw new IllegalStateException("Git wire machine has failed");
        }
    }
}
