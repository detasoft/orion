package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.util.Arrays;
import java.util.Objects;

/**
 * Minimal connection-level state machine for the ByteBuf control/raw ownership
 * model. It reads one fixed-size control frame, switches to raw mode, and lazily
 * creates the raw sink only when raw bytes are actually available.
 */
public final class GitMinimalWireMachine implements AutoCloseable {
    private final GitFixedControlFrameReader controlReader;
    private final ByteBuf controlBuffer;
    private final RawSinkFactory rawSinkFactory;
    private byte[] controlBytes;
    private RawSink rawSink;
    private Phase phase = Phase.CONTROL;

    public GitMinimalWireMachine(
            ByteBufAllocator allocator,
            int controlFrameSize,
            int structuralCapacity,
            RawSinkFactory rawSinkFactory) {
        if (structuralCapacity < controlFrameSize) {
            throw new IllegalArgumentException("Structural capacity must fit one frame");
        }
        controlReader = new GitFixedControlFrameReader(controlFrameSize);
        controlBuffer = Objects.requireNonNull(allocator, "allocator").buffer(structuralCapacity, structuralCapacity);
        this.rawSinkFactory = Objects.requireNonNull(rawSinkFactory, "rawSinkFactory");
    }

    public void accept(ByteBuf input) {
        Objects.requireNonNull(input, "input");
        if (phase == Phase.CONTROL) {
            int controlStart = input.readerIndex();
            GitFixedControlFrameReader.ControlReadState result = controlReader.accept(input);
            int controlLength = input.readerIndex() - controlStart;
            if (controlLength > 0) {
                controlBuffer.writeBytes(input, controlStart, controlLength);
            }
            if (result == GitFixedControlFrameReader.ControlReadState.NEEDS_MORE_CONTROL) {
                input.release();
                return;
            }
            if (result == GitFixedControlFrameReader.ControlReadState.CONTROL_COMPLETE) {
                controlBytes = controlBytesFromBuffer();
                controlBuffer.clear();
                phase = Phase.RAW;
            }
            if (!input.isReadable()) {
                input.release();
                return;
            }
        }
        forwardRaw(input);
    }

    public byte[] controlBytes() {
        if (controlBytes == null) {
            return null;
        }
        return Arrays.copyOf(controlBytes, controlBytes.length);
    }

    private byte[] controlBytesFromBuffer() {
        byte[] bytes = new byte[controlBuffer.readableBytes()];
        controlBuffer.getBytes(controlBuffer.readerIndex(), bytes);
        return bytes;
    }

    private void forwardRaw(ByteBuf input) {
        if (!input.isReadable()) {
            input.release();
            return;
        }
        ByteBuf raw = input.readRetainedSlice(input.readableBytes());
        try {
            rawSink().accept(raw);
        } finally {
            input.release();
        }
    }

    private RawSink rawSink() {
        if (rawSink == null) {
            rawSink = Objects.requireNonNull(rawSinkFactory.create(controlBytes()), "rawSink");
        }
        return rawSink;
    }

    @Override
    public void close() {
        try {
            if (rawSink != null) {
                rawSink.close();
            }
        } finally {
            controlBuffer.release();
        }
    }

    private enum Phase {
        CONTROL,
        RAW
    }

    @FunctionalInterface
    public interface RawSinkFactory {
        RawSink create(byte[] controlBytes);
    }

    public interface RawSink extends AutoCloseable {
        void accept(ByteBuf input);

        @Override
        default void close() {
        }
    }
}
