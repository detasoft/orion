package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;

import java.util.Objects;

public final class GitFixedControlFrameReader {
    private final int frameSize;
    private int readBytes;

    public GitFixedControlFrameReader(int frameSize) {
        if (frameSize <= 0) {
            throw new IllegalArgumentException("Frame size must be positive");
        }
        this.frameSize = frameSize;
    }

    public ControlReadState accept(ByteBuf input) {
        Objects.requireNonNull(input, "input");
        if (readBytes == frameSize) {
            return ControlReadState.ALREADY_COMPLETE;
        }
        int missing = frameSize - readBytes;
        int copied = Math.min(missing, input.readableBytes());
        input.skipBytes(copied);
        readBytes += copied;
        if (readBytes < frameSize) {
            return ControlReadState.NEEDS_MORE_CONTROL;
        }
        return ControlReadState.CONTROL_COMPLETE;
    }

    public enum ControlReadState {
        NEEDS_MORE_CONTROL,
        CONTROL_COMPLETE,
        ALREADY_COMPLETE
    }
}
