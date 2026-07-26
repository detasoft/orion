package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import pro.deta.orion.git.parser.wire.control.ControlState;
import pro.deta.orion.git.parser.wire.utils.RawSink;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;

public final class GitSideBandDecoder implements AutoCloseable {
    private final Context context;
    private Phase phase;
    private boolean complete;

    public GitSideBandDecoder(
            ByteBufAllocator allocator,
            GitSideBandMode mode,
            RawSink.Target dataTarget,
            Consumer<String> progressConsumer) {
        ByteBufAllocator checkedAllocator = Objects.requireNonNull(allocator, "allocator");
        this.context = new Context(
                checkedAllocator,
                Objects.requireNonNull(mode, "mode"),
                Objects.requireNonNull(dataTarget, "dataTarget"),
                Objects.requireNonNull(progressConsumer, "progressConsumer"));
        this.phase = newControlPhase();
    }

    public void accept(ByteBuf input) {
        Objects.requireNonNull(input, "input");
        if (complete && input.isReadable()) {
            throw new IllegalStateException("Git side-band stream is already complete");
        }
        while (!complete && input.isReadable()) {
            phase = phase.accept(input);
        }
    }

    public boolean isComplete() {
        return complete;
    }

    @Override
    public void close() {
        if (complete) {
            return;
        }
        phase.close();
        complete = true;
        phase = new DonePhase();
    }

    private ControlPhase newControlPhase() {
        return new ControlPhase(ControlState.ControlEmpty.INSTANCE, context.nextPacketIndex, context.nextByteOffset);
    }

    private void completePacket(ControlState.ControlSuccess control) {
        context.nextPacketIndex++;
        context.nextByteOffset += control.length();
    }

    private void completeStream(ControlState.ControlSuccess control) {
        completePacket(control);
        complete = true;
    }

    private static final class Context {
        private final ByteBufAllocator allocator;
        private final GitSideBandMode mode;
        private final RawSink.Target dataTarget;
        private final Consumer<String> progressConsumer;
        private final GitFixedControlFrameReader controlReader;
        private final RawSink rawSink = new RawSink();
        private long nextPacketIndex;
        private long nextByteOffset;

        private Context(
                ByteBufAllocator allocator,
                GitSideBandMode mode,
                RawSink.Target dataTarget,
                Consumer<String> progressConsumer) {
            this.allocator = allocator;
            this.mode = mode;
            this.dataTarget = dataTarget;
            this.progressConsumer = progressConsumer;
            this.controlReader = new GitFixedControlFrameReader(allocator);
        }
    }

    sealed interface Phase permits ControlPhase, BandPhase, DataPayloadPhase, BufferedPayloadPhase, DonePhase {
        Phase accept(ByteBuf input);

        default void close() {
        }
    }

    private final class ControlPhase implements Phase {
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
            ControlState nextState = context.controlReader.accept(state, input, packetIndex, byteOffset);
            if (nextState instanceof ControlState.MoreDataNeeded) {
                return new ControlPhase(nextState, packetIndex, byteOffset);
            }
            if (nextState instanceof ControlState.ControlSuccess control) {
                return phaseAfterControl(control);
            }
            return new ControlPhase(nextState, packetIndex, byteOffset);
        }

        private Phase phaseAfterControl(ControlState.ControlSuccess control) {
            return switch (control.type()) {
                case FLUSH -> {
                    completeStream(control);
                    yield new DonePhase();
                }
                case DELIMITER, RESPONSE_END -> throw GitWireException.of(
                        GitWireError.Kind.INVALID_SIDE_BAND,
                        GitWireError.Phase.SIDE_BAND,
                        packetIndex,
                        byteOffset,
                        "Git side-band stream must contain only data or flush packets");
                case DATA -> {
                    validateDataPacket(control);
                    yield new BandPhase(control, packetIndex, byteOffset);
                }
            };
        }

        private void validateDataPacket(ControlState.ControlSuccess control) {
            if (control.length() > context.mode.maxPacketLength()) {
                throw GitWireException.of(
                        GitWireError.Kind.LENGTH_EXCEEDS_LIMIT,
                        GitWireError.Phase.SIDE_BAND,
                        packetIndex,
                        byteOffset,
                        "Git side-band packet exceeds negotiated mode limit");
            }
            if (control.payloadLength() == 0) {
                throw GitWireException.of(
                        GitWireError.Kind.INVALID_SIDE_BAND,
                        GitWireError.Phase.SIDE_BAND,
                        packetIndex,
                        byteOffset,
                        "Git side-band data packet has no band id");
            }
        }

        @Override
        public void close() {
            if (state instanceof ControlState.MoreDataNeeded moreDataNeeded) {
                moreDataNeeded.fragment().release();
            }
        }
    }

    private final class BandPhase implements Phase {
        private final ControlState.ControlSuccess control;
        private final long packetIndex;
        private final long byteOffset;

        private BandPhase(ControlState.ControlSuccess control, long packetIndex, long byteOffset) {
            this.control = control;
            this.packetIndex = packetIndex;
            this.byteOffset = byteOffset;
        }

        @Override
        public Phase accept(ByteBuf input) {
            if (!input.isReadable()) {
                return this;
            }
            GitSideBandBand band = GitSideBandBand.fromId(input.readUnsignedByte(), packetIndex, byteOffset);
            int payloadLength = control.payloadLength() - 1;
            if (band == GitSideBandBand.DATA) {
                if (payloadLength == 0) {
                    completePacket(control);
                    return newControlPhase();
                }
                return new DataPayloadPhase(control, payloadLength).accept(input);
            }
            if (payloadLength == 0) {
                deliverControlPayload(band, input.alloc().buffer(0, 0), control);
                return newControlPhase();
            }
            if (input.readableBytes() >= payloadLength) {
                ByteBuf payload = input.readRetainedSlice(payloadLength);
                deliverControlPayload(band, payload, control);
                return newControlPhase();
            }
            return new BufferedPayloadPhase(band, control, new CachingByteBuf(
                    context.allocator,
                    input,
                    payloadLength,
                    CachingByteBuf.Mode.BUFFERED));
        }
    }

    private final class DataPayloadPhase implements Phase {
        private final ControlState.ControlSuccess control;
        private int remaining;

        private DataPayloadPhase(ControlState.ControlSuccess control, int remaining) {
            this.control = control;
            this.remaining = remaining;
        }

        @Override
        public Phase accept(ByteBuf input) {
            int length = Math.min(remaining, input.readableBytes());
            if (length == 0) {
                return this;
            }
            ByteBuf slice = input.readRetainedSlice(length);
            try {
                context.rawSink.accept(context.dataTarget, slice);
            } catch (RuntimeException | Error e) {
                if (slice.refCnt() > 0) {
                    slice.release();
                }
                throw e;
            }
            remaining -= length;
            if (remaining > 0) {
                return this;
            }
            completePacket(control);
            return newControlPhase();
        }
    }

    private final class BufferedPayloadPhase implements Phase {
        private final GitSideBandBand band;
        private final ControlState.ControlSuccess control;
        private CachingByteBuf fragment;

        private BufferedPayloadPhase(
                GitSideBandBand band,
                ControlState.ControlSuccess control,
                CachingByteBuf fragment) {
            this.band = band;
            this.control = control;
            this.fragment = fragment;
        }

        @Override
        public Phase accept(ByteBuf input) {
            fragment.append(input);
            if (!fragment.isComplete()) {
                return this;
            }
            CachingByteBuf completed = fragment;
            fragment = null;
            deliverControlPayload(band, completed, control);
            return newControlPhase();
        }

        @Override
        public void close() {
            if (fragment != null) {
                fragment.release();
            }
        }
    }

    private final class DonePhase implements Phase {
        @Override
        public Phase accept(ByteBuf input) {
            return this;
        }
    }

    private void deliverControlPayload(
            GitSideBandBand band,
            ByteBuf payload,
            ControlState.ControlSuccess control) {
        try {
            String message = payload.toString(StandardCharsets.UTF_8);
            if (band == GitSideBandBand.FATAL) {
                throw GitWireException.of(
                        GitWireError.Kind.SIDE_BAND_FATAL,
                        GitWireError.Phase.SIDE_BAND,
                        context.nextPacketIndex,
                        context.nextByteOffset,
                        fatalMessage(message));
            }
            context.progressConsumer.accept(message);
            completePacket(control);
        } finally {
            payload.release();
        }
    }

    private static String fatalMessage(String message) {
        String sanitized = message.replace('\r', ' ').replace('\n', ' ').strip();
        if (sanitized.isEmpty()) {
            return "Remote Git side-band fatal";
        }
        return "Remote Git side-band fatal: " + sanitized;
    }
}
