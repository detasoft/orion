package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.git.parser.wire.utils.RawSink;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Minimal streaming pkt-line machine used to prove the native Git wire parser
 * boundary before the production session API is introduced. The machine owns
 * the durable parser phase, delegates fixed control-frame reads to stateless
 * helpers, and forwards declared pkt-line payload bytes to a lazily created raw
 * target without buffering whole raw packets.
 */
public final class GitMinimalWireMachine implements AutoCloseable {

    private final ByteBufAllocator allocator;
    private final RawTargetFactory rawTargetFactory;
    private final Consumer<ControlState.ControlSuccess> frameConsumer;
    private final GitFixedControlFrameReader controlReader;
    private final RawSink rawSink = new RawSink();

    private Phase phase = new ControlPhase(ControlState.ControlEmpty.INSTANCE);

    public GitMinimalWireMachine(
            ByteBufAllocator allocator,
            RawTargetFactory rawTargetFactory) {
        this(allocator, _control -> {}, rawTargetFactory);
    }

    public GitMinimalWireMachine(
            ByteBufAllocator allocator,
            Consumer<ControlState.ControlSuccess> frameConsumer,
            RawTargetFactory rawTargetFactory) {
        this.allocator = Objects.requireNonNull(allocator, "allocator");
        this.controlReader = new GitFixedControlFrameReader(this.allocator);
        this.frameConsumer = Objects.requireNonNull(frameConsumer, "frameConsumer");
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
                    frameConsumer.accept(controlSuccess);
                    phase = new RawSinkPhase(controlSuccess);
                } else {
                    phase = new ControlPhase(nextControlState);
                }
            }
            if (phase instanceof RawSinkPhase rawSinkPhase) {
                rawSinkPhase.forward(input);
                if (!rawSinkPhase.isComplete()) {
                    return true;
                }
                rawSinkPhase.close();
                phase = new ControlPhase(ControlState.ControlEmpty.INSTANCE);
            }
        }
        return true;
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
        if (phase instanceof RawSinkPhase rawSinkPhase) {
            rawSinkPhase.close();
            if (!rawSinkPhase.isComplete()) {
                phase = new ControlPhase(ControlState.ControlEmpty.INSTANCE);
                throw new IllegalStateException("Incomplete Git pkt-line payload");
            }
        }
    }

    sealed interface Phase permits ControlPhase, RawSinkPhase {
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


    final class RawSinkPhase implements Phase, AutoCloseable {
        private final ControlState.ControlSuccess control;
        private final FixedByteBufForwarder forwarder;
        private RawSink.Target target;

        private RawSinkPhase(ControlState.ControlSuccess control) {
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

        ControlState.ControlSuccess control() {
            return control;
        }

        int remaining() {
            return forwarder.remaining();
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
