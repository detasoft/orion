package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import pro.deta.orion.git.parser.wire.control.ControlState;

import java.util.Objects;

import static pro.deta.orion.git.parser.wire.control.ControlState.PKT_LINE_HEADER_SIZE;

public final class GitFixedControlFrameReader {
    public static final int MAX_PKT_LINE_LENGTH = 65_520;
    private final ByteBufAllocator allocator;

    public GitFixedControlFrameReader(ByteBufAllocator allocator) {
        this.allocator = Objects.requireNonNull(allocator, "allocator");
    }

    public ControlState accept(ControlState controlState, ByteBuf input) {
        return accept(controlState, input, GitWireError.UNKNOWN_INDEX, input.readerIndex());
    }

    public ControlState accept(ControlState controlState, ByteBuf input, long packetIndex, long byteOffset) {
        Objects.requireNonNull(input, "input");
        controlState = switch (controlState) {
            case ControlState.ControlSuccess _ignored ->
                    throw new IllegalStateException("Control frame is already complete");

            case ControlState.ControlEmpty _ignored -> readEmpty(controlState, input, packetIndex, byteOffset);

            case ControlState.MoreDataNeeded prevData -> readMore(prevData.fragment(), input, packetIndex, byteOffset);
        };
        return controlState;
    }

    private ControlState readEmpty(ControlState controlState, ByteBuf input, long packetIndex, long byteOffset) {
        if (!input.isReadable()) {
            return controlState;
        }
        if (input.readableBytes() >= PKT_LINE_HEADER_SIZE) {
            ControlState state = buildControlState(input, packetIndex, byteOffset);
            input.skipBytes(PKT_LINE_HEADER_SIZE);
            return state;
        }
        return new ControlState.MoreDataNeeded(new CachingByteBuf(allocator, input, PKT_LINE_HEADER_SIZE, CachingByteBuf.Mode.BUFFERED));
    }

    private ControlState readMore(CachingByteBuf previousFragment, ByteBuf input, long packetIndex, long byteOffset) {
        previousFragment.append(input);
        if (!previousFragment.isComplete()) {
            return new ControlState.MoreDataNeeded(previousFragment);
        }
        try {
            return buildControlState(previousFragment, packetIndex, byteOffset);
        } finally {
            previousFragment.release();
        }
    }

    private ControlState buildControlState(ByteBuf input, long packetIndex, long byteOffset) {
        int packetLength = GitNativeUtils.packetLength(
                input,
                input.readerIndex(),
                GitWireError.Phase.CONTROL_HEADER,
                packetIndex,
                byteOffset);
        int length = resolveWireLength(packetLength, packetIndex, byteOffset);
        ControlState.ControlType controlType = switch (packetLength) {
            case 0 -> ControlState.ControlType.FLUSH;
            case 1 -> ControlState.ControlType.DELIMITER;
            case 2 -> ControlState.ControlType.RESPONSE_END;
            default -> ControlState.ControlType.DATA;
        };
        return new ControlState.ControlSuccess(controlType, length);
    }

    private int resolveWireLength(int packetLength, long packetIndex, long byteOffset) {
        if (packetLength == 3) {
            throw GitWireException.of(
                    GitWireError.Kind.RESERVED_LENGTH,
                    GitWireError.Phase.CONTROL_HEADER,
                    packetIndex,
                    byteOffset,
                    "Pkt-line length 0003 is reserved");
        }
        if (packetLength < PKT_LINE_HEADER_SIZE) {
            return PKT_LINE_HEADER_SIZE;
        }
        if (packetLength > MAX_PKT_LINE_LENGTH) {
            throw GitWireException.of(
                    GitWireError.Kind.LENGTH_EXCEEDS_LIMIT,
                    GitWireError.Phase.CONTROL_HEADER,
                    packetIndex,
                    byteOffset,
                    "Pkt-line length exceeds Git pkt-line limit");
        }
        return packetLength;
    }
}
