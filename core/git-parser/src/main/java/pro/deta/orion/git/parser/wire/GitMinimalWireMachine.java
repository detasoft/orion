package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import pro.deta.orion.continuation.Closed;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.continuation.ContinuationRuntime;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.git.parser.wire.sideband.GitSideBandDecoder;
import pro.deta.orion.git.parser.wire.sideband.GitSideBandMode;
import pro.deta.orion.git.parser.wire.utils.RawSink;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

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
    private final Wire wire;

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
        this.wire = new Wire(newControlPhase());
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
        this.wire = new Wire(newControlPhase());
    }

    public static GitMinimalWireMachine forV1Advertisement(ByteBufAllocator allocator) {
        return new GitMinimalWireMachine(
                allocator,
                pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement.class,
                GitV1AdvertisementPhases.firstLine(),
                _control -> {
                    throw new IllegalStateException("V1 advertisement parser cannot enter raw forwarding");
                });
    }

    public static GitMinimalWireMachine forV2LsRefsResponse(ByteBufAllocator allocator) {
        return new GitMinimalWireMachine(
                allocator,
                pro.deta.orion.git.parser.wire.protocolv2.response.GitLsRefsResponse.class,
                GitLsRefsResponsePhases.rows(),
                _control -> {
                    throw new IllegalStateException("Ls-refs response parser cannot enter raw forwarding");
                });
    }

    public static GitMinimalWireMachine forV2FetchResponse(
            ByteBufAllocator allocator,
            RawTargetFactory rawTargetFactory,
            Consumer<String> progressConsumer) {
        Objects.requireNonNull(progressConsumer, "progressConsumer");
        return new GitMinimalWireMachine(
                allocator,
                pro.deta.orion.git.parser.wire.protocolv2.response.GitFetchResponse.class,
                GitFetchResponsePhases.firstHeader(progressConsumer),
                rawTargetFactory);
    }

    public boolean accept(ByteBuf input) {
        Objects.requireNonNull(input, "input");
        wire.accept(input);
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

    private ContinuationFlow<ByteBuf> flowAfterControl(
            ControlState.ControlSuccess control,
            CallbackFlowControl flowControl,
            long packetIndex,
            long byteOffset) {
        if (flowControl.forwardRawPayload) {
            if (control.type() == ControlState.ControlType.DATA) {
                return ContinuationFlow.transition(Continuation.completedError(
                        new IllegalStateException("Raw payload forwarding cannot start before a DATA payload is handled")));
            }
            context.completePacket(control);
            return ContinuationFlow.transition(new RawStreamPhase(control));
        }
        if (control.type() != ControlState.ControlType.DATA) {
            context.completePacket(control);
            return ContinuationFlow.transition(newControlPhase());
        }
        return ContinuationFlow.transition(new StructuredPayloadPhase(control));
    }

    private ControlPhase newControlPhase() {
        return new ControlPhase(ControlState.ControlEmpty.INSTANCE, context.nextPacketIndex(), context.nextByteOffset());
    }

    private Continuation<ByteBuf> continuationAfterSemanticTransition(
            SemanticTransition transition,
            ControlState.ControlSuccess transitionControl,
            Continuation<ByteBuf> self) {
        if (transition instanceof SemanticTransition.Next next) {
            context.semanticContext.phase = next.phase();
            return newControlPhase();
        }
        if (transition instanceof SemanticTransition.EnterSideBand enterSideBand) {
            context.semanticContext.phase = enterSideBand.afterSideBand();
            return new SideBandPhase(transitionControl, enterSideBand.progressConsumer());
        }
        SemanticTransition.Complete<?> complete = (SemanticTransition.Complete<?>) transition;
        if (complete.type() != context.semanticContext.resultType) {
            return Continuation.completedError(new IllegalStateException(
                    "Git wire semantic phase completed " + complete.type().getSimpleName()
                            + " but machine expects " + context.semanticContext.resultType.getSimpleName()));
        }
        context.semanticContext.complete(complete);
        return Continuation.completedSuccess(self);
    }

    private ContinuationFlow<ByteBuf> flowAfterSemanticTransition(
            SemanticTransition transition,
            ControlState.ControlSuccess transitionControl,
            Continuation<ByteBuf> self) {
        return ContinuationFlow.transition(continuationAfterSemanticTransition(transition, transitionControl, self));
    }

    @TestOnly
    ComposedState state() {
        return new ComposedState(wire.snapshot());
    }

    @Override
    public void close() {
        if (context.hasSemanticContext()) {
            closeSemanticMachine();
            return;
        }
        closeCurrent();
    }

    private void closeSemanticMachine() {
        if (context.semanticContext.outcome != null) {
            return;
        }
        Continuation<ByteBuf> current = wire.snapshot();
        GitWireException fragmentError = null;
        if (current instanceof ControlPhase cp) {
            fragmentError = cp.releaseFragment();
        } else if (current instanceof StructuredPayloadPhase spp) {
            fragmentError = spp.releasePayload();
        }
        if (fragmentError != null) {
            context.semanticContext.fail(fragmentError.error());
            wire.transitionToError(fragmentError);
            return;
        }
        try {
            context.semanticContext.phase.close(
                    context.semanticContext.values,
                    context.nextPacketIndex(),
                    context.nextByteOffset());
        } catch (GitWireException e) {
            context.semanticContext.fail(e.error());
            wire.transitionToError(e);
        }
    }

    private void closeCurrent() {
        if (wire.terminal()) {
            return;
        }
        Continuation<ByteBuf> current = wire.snapshot();
        if (current instanceof ControlPhase cp) {
            GitWireException error = cp.releaseFragment();
            if (error != null) {
                wire.transitionToError(error);
                return;
            }
        } else if (current instanceof StructuredPayloadPhase spp) {
            GitWireException error = spp.releasePayload();
            if (error != null) {
                wire.transitionToError(error);
                return;
            }
        }
        wire.resetTo(newControlPhase());
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

    private final class Wire extends ContinuationRuntime<ByteBuf> {
        Wire(Continuation<ByteBuf> initial) {
            super(initial);
        }

        Continuation<ByteBuf> snapshot() {
            return current();
        }

        void transitionToError(GitWireException e) {
            transitionTo(current(), ContinuationFlow.transition(Continuation.completedError(e)));
        }

        void resetTo(Continuation<ByteBuf> next) {
            transitionTo(current(), ContinuationFlow.transition(next));
        }
    }

    record ComposedState(Continuation<ByteBuf> phase) {
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
                long packetIndex,
                long byteOffset,
                GitWireValueStack values);

        default void close(GitWireValueStack values, long packetIndex, long byteOffset) {
        }
    }

    sealed interface SemanticTransition
            permits SemanticTransition.Complete, SemanticTransition.EnterSideBand, SemanticTransition.Next {

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

        record EnterSideBand(
                SemanticPhase afterSideBand,
                Consumer<String> progressConsumer) implements SemanticTransition {
            public EnterSideBand {
                Objects.requireNonNull(afterSideBand, "afterSideBand");
                Objects.requireNonNull(progressConsumer, "progressConsumer");
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

    final class ControlPhase implements Continuation<ByteBuf> {
        private ControlState state;
        private final long packetIndex;
        private final long byteOffset;

        private ControlPhase(ControlState state, long packetIndex, long byteOffset) {
            this.state = state;
            this.packetIndex = packetIndex;
            this.byteOffset = byteOffset;
        }

        @Override
        public ContinuationFlow<ByteBuf> process(ByteBuf input) {
            try {
                ControlState old = this.state;
                this.state = ControlState.ControlEmpty.INSTANCE;
                ControlState next = context.controlReader.accept(old, input, packetIndex, byteOffset);
                this.state = next;
                if (next instanceof ControlState.MoreDataNeeded) {
                    return ContinuationFlow.await();
                }
                if (next instanceof ControlState.ControlSuccess controlSuccess) {
                    if (context.hasSemanticContext()) {
                        return phaseAfterSemanticControl(controlSuccess);
                    }
                    CallbackFlowControl flowControl = new CallbackFlowControl();
                    context.frameConsumer.accept(controlSuccess, flowControl);
                    return flowAfterControl(controlSuccess, flowControl, packetIndex, byteOffset);
                }
                return ContinuationFlow.await();
            } catch (GitWireException e) {
                if (context.hasSemanticContext()) {
                    context.semanticContext.fail(e.error());
                }
                return ContinuationFlow.transition(Continuation.completedError(e));
            } catch (RuntimeException e) {
                return ContinuationFlow.transition(Continuation.completedError(e));
            }
        }

        private ContinuationFlow<ByteBuf> phaseAfterSemanticControl(ControlState.ControlSuccess control) {
            if (control.type() == ControlState.ControlType.DATA) {
                return ContinuationFlow.transition(new StructuredPayloadPhase(control));
            }
            SemanticTransition transition =
                    context.semanticContext.phase.accept(
                            control,
                            Unpooled.EMPTY_BUFFER,
                            packetIndex,
                            byteOffset,
                            context.semanticContext.values);
            context.completePacket(control);
            return flowAfterSemanticTransition(transition, control, this);
        }

        ControlState state() {
            return state;
        }

        GitWireException releaseFragment() {
            if (state instanceof ControlState.MoreDataNeeded moreDataNeeded) {
                state = ControlState.ControlEmpty.INSTANCE;
                moreDataNeeded.fragment().release();
                return GitWireException.of(
                        GitWireError.Kind.INCOMPLETE_HEADER,
                        GitWireError.Phase.CONTROL_HEADER,
                        packetIndex,
                        byteOffset,
                        "Incomplete Git pkt-line header");
            }
            return null;
        }
    }

    final class StructuredPayloadPhase implements Continuation<ByteBuf>, Closed {
        private final ControlState.ControlSuccess control;
        private CachingByteBuf fragment;
        private boolean complete;
        private Continuation<ByteBuf> nextContinuation;

        private StructuredPayloadPhase(ControlState.ControlSuccess control) {
            this.control = control;
        }

        @Override
        public ContinuationFlow<ByteBuf> process(ByteBuf input) {
            try {
                read(input);
                if (!complete) {
                    return ContinuationFlow.await();
                }
                return ContinuationFlow.transition(nextContinuation);
            } catch (GitWireException e) {
                if (context.hasSemanticContext()) {
                    context.semanticContext.fail(e.error());
                }
                return ContinuationFlow.transition(Continuation.completedError(e));
            } catch (RuntimeException e) {
                return ContinuationFlow.transition(Continuation.completedError(e));
            }
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
                            context.semanticContext.phase.accept(
                                    control,
                                    payload,
                                    context.nextPacketIndex(),
                                    context.nextByteOffset(),
                                    context.semanticContext.values);
                    context.completePacket(control);
                    complete = true;
                    nextContinuation = continuationAfterSemanticTransition(transition, control, this);
                } finally {
                    payload.release();
                }
                return;
            }
            try {
                context.structuredPayloadConsumer.accept(control, payload);
                context.completePacket(control);
                complete = true;
                nextContinuation = newControlPhase();
            } catch (RuntimeException e) {
                payload.release();
                complete = true;
                nextContinuation = Continuation.completedError(e);
            }
        }

        @Override
        public void close() {
            if (fragment != null) {
                fragment.release();
                fragment = null;
            }
        }

        GitWireException releasePayload() {
            close();
            if (!complete) {
                return GitWireException.of(
                        GitWireError.Kind.INCOMPLETE_PAYLOAD,
                        GitWireError.Phase.STRUCTURED_PAYLOAD,
                        context.nextPacketIndex(),
                        context.nextByteOffset(),
                        "Incomplete Git pkt-line payload");
            }
            return null;
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
    }

    final class RawStreamPhase implements Continuation<ByteBuf>, Closed {
        private final ControlState.ControlSuccess control;
        private RawSink.Target target;

        private RawStreamPhase(ControlState.ControlSuccess control) {
            this.control = control;
        }

        @Override
        public ContinuationFlow<ByteBuf> process(ByteBuf input) {
            if (!input.isReadable()) {
                return ContinuationFlow.await();
            }
            ByteBuf slice = input.readRetainedSlice(input.readableBytes());
            int length = slice.readableBytes();
            try {
                target().accept(slice);
            } catch (RuntimeException e) {
                slice.release();
                return ContinuationFlow.transition(Continuation.completedError(e));
            }
            context.forwardRawBytes(length);
            return ContinuationFlow.await();
        }

        @Override
        public void close() {
            if (target != null) {
                target.close();
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
                target = Objects.requireNonNull(context.rawTargetFactory.create(control), "rawTarget");
            }
            return target;
        }
    }

    final class SideBandPhase implements Continuation<ByteBuf>, Closed {
        private final LazyRawTarget target;
        private final GitSideBandDecoder decoder;

        private SideBandPhase(
                ControlState.ControlSuccess control,
                Consumer<String> progressConsumer) {
            this.target = new LazyRawTarget(control);
            this.decoder = new GitSideBandDecoder(
                    context.allocator,
                    GitSideBandMode.SIDE_BAND_64K,
                    target,
                    progressConsumer);
        }

        @Override
        public ContinuationFlow<ByteBuf> process(ByteBuf input) {
            try {
                int readerIndex = input.readerIndex();
                decoder.accept(input);
                context.forwardRawBytes(input.readerIndex() - readerIndex);
                if (decoder.isComplete()) {
                    return ContinuationFlow.transition(newControlPhase());
                }
                return ContinuationFlow.await();
            } catch (GitWireException e) {
                if (context.hasSemanticContext()) {
                    context.semanticContext.fail(e.error());
                }
                return ContinuationFlow.transition(Continuation.completedError(e));
            } catch (RuntimeException e) {
                return ContinuationFlow.transition(Continuation.completedError(e));
            }
        }

        @Override
        public void close() {
            try {
                decoder.close();
            } finally {
                target.close();
            }
        }

        private final class LazyRawTarget implements RawSink.Target {
            private final ControlState.ControlSuccess control;
            private RawSink.Target delegate;
            private boolean closed;

            private LazyRawTarget(ControlState.ControlSuccess control) {
                this.control = control;
            }

            @Override
            public void accept(ByteBuf input) {
                target().accept(input);
            }

            @Override
            public void close() {
                if (closed) {
                    return;
                }
                closed = true;
                if (delegate != null) {
                    delegate.close();
                }
            }

            private RawSink.Target target() {
                if (closed) {
                    throw new IllegalStateException("Git side-band raw target is closed");
                }
                if (delegate == null) {
                    delegate = Objects.requireNonNull(context.rawTargetFactory.create(control), "rawTarget");
                }
                return delegate;
            }
        }
    }

}
