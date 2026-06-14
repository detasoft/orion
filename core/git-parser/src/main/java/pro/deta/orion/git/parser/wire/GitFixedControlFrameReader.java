package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import pro.deta.orion.lifecycle.state.TestOnly;

import java.util.Objects;

public final class GitFixedControlFrameReader implements AutoCloseable {
    static final int MAX_PKT_LINE_LENGTH = 65_520;

    private static final int HEADER_SIZE = 4;

    private final ByteBufAllocator allocator;
    private ByteBuf buffer;
    private ByteBuf retainedFrame;
    private ByteBuf retainedSource;
    private int wireLength;
    private boolean ready;

    public GitFixedControlFrameReader(ByteBufAllocator allocator) {
        this.allocator = Objects.requireNonNull(allocator, "allocator");
    }

    public ControlReadState accept(ByteBuf input) {
        Objects.requireNonNull(input, "input");
        if (ready) {
            throw new IllegalStateException("Control frame is already complete");
        }
        if (buffer != null) {
            copyReadableBytes(input);
            return ready ? ControlReadState.CONTROL_COMPLETE : ControlReadState.NEEDS_MORE_DATA;
        }
        if (input.readableBytes() < HEADER_SIZE) {
            throw new IllegalArgumentException("Pkt-line header must be available in one input buffer");
        }

        int length = wireLength(input, input.readerIndex());
        if (input.readableBytes() >= length) {
            retainedSource = input;
            retainedFrame = input.readRetainedSlice(length);
            ready = true;
            return ControlReadState.CONTROL_COMPLETE;
        }

        wireLength = length;
        buffer = allocator.buffer(wireLength, MAX_PKT_LINE_LENGTH);
        buffer.writeBytes(input, input.readableBytes());
        return ControlReadState.NEEDS_MORE_DATA;
    }

    ByteBuf bytes() {
        if (!ready) {
            throw new IllegalStateException("Control frame is not complete");
        }
        if (buffer != null) {
            return buffer.slice(buffer.readerIndex(), buffer.readableBytes());
        }
        return retainedFrame.slice(retainedFrame.readerIndex(), retainedFrame.readableBytes());
    }

    @TestOnly
    int bufferedBytes() {
        return buffer == null ? 0 : buffer.readableBytes();
    }

    @TestOnly
    boolean isRetainedFrom(ByteBuf input) {
        return retainedSource == input;
    }

    void releaseCompletedStorage() {
        wireLength = 0;
        ready = false;
        if (retainedFrame != null) {
            retainedFrame.release();
            retainedFrame = null;
        }
        retainedSource = null;
        if (buffer != null) {
            buffer.release();
            buffer = null;
        }
    }

    @Override
    public void close() {
        releaseCompletedStorage();
    }

    private void copyReadableBytes(ByteBuf input) {
        int missing = wireLength - buffer.readableBytes();
        int copied = Math.min(missing, input.readableBytes());
        if (copied > 0) {
            buffer.writeBytes(input, copied);
        }
        ready = buffer.readableBytes() == wireLength;
    }

    private int wireLength(ByteBuf input, int headerIndex) {
        int packetLength = 0;
        for (int i = 0; i < HEADER_SIZE; i++) {
            packetLength = (packetLength << 4) | hexValue(input.getByte(headerIndex + i));
        }
        return resolveWireLength(packetLength);
    }

    private int resolveWireLength(int packetLength) {
        if (packetLength == 3) {
            throw new IllegalArgumentException("Pkt-line length 0003 is reserved");
        }
        if (packetLength < HEADER_SIZE) {
            return HEADER_SIZE;
        }
        if (packetLength > MAX_PKT_LINE_LENGTH) {
            throw new IllegalArgumentException("Pkt-line length exceeds Git pkt-line limit");
        }
        return packetLength;
    }

    private static int hexValue(byte value) {
        int unsigned = value & 0xff;
        if (unsigned >= '0' && unsigned <= '9') {
            return unsigned - '0';
        }
        if (unsigned >= 'a' && unsigned <= 'f') {
            return unsigned - 'a' + 10;
        }
        if (unsigned >= 'A' && unsigned <= 'F') {
            return unsigned - 'A' + 10;
        }
        throw new IllegalArgumentException("Pkt-line length contains non-hex byte");
    }

    public enum ControlReadState {
        NEEDS_MORE_DATA,
        CONTROL_COMPLETE
    }
}
