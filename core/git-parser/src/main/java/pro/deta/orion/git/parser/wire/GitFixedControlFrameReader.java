package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import pro.deta.orion.git.parser.wire.control.ControlState;

import java.util.Objects;

import static pro.deta.orion.git.parser.wire.control.ControlState.PKT_LINE_HEADER_SIZE;

public final class GitFixedControlFrameReader {
    static final int MAX_PKT_LINE_LENGTH = 65_520;
    private final ByteBufAllocator allocator;

    public GitFixedControlFrameReader(ByteBufAllocator allocator) {
        this.allocator = Objects.requireNonNull(allocator, "allocator");
    }

    public ControlState accept(ControlState controlState, ByteBuf input) {
        Objects.requireNonNull(input, "input");
        controlState = switch (controlState) {
            case ControlState.ControlSuccess _ignored ->
                    throw new IllegalStateException("Control frame is already complete");

            case ControlState.ControlEmpty _ignored -> readEmpty(controlState, input);

            case ControlState.MoreDataNeeded prevData -> readMore(prevData.fragment(), input);
        };
        return controlState;
    }

    private ControlState readEmpty(ControlState controlState, ByteBuf input) {
        if (!input.isReadable()) {
            return controlState;
        }
        if (input.readableBytes() >= PKT_LINE_HEADER_SIZE) {
            ControlState state = buildControlState(input);
            input.skipBytes(PKT_LINE_HEADER_SIZE);
            return state;
        }
        return new ControlState.MoreDataNeeded(new CachingByteBuf(allocator, input, PKT_LINE_HEADER_SIZE, CachingByteBuf.Mode.BUFFERED));
    }

    private ControlState readMore(CachingByteBuf previousFragment, ByteBuf input) {
        previousFragment.append(input);
        if (!previousFragment.isComplete()) {
            return new ControlState.MoreDataNeeded(previousFragment);
        }
        try {
            return buildControlState(previousFragment);
        } catch (RuntimeException | Error e) {
            throw e;
        } finally {
            previousFragment.release();
        }
    }

    private ControlState buildControlState(ByteBuf input) {
        int packetLength = GitNativeUtils.packetLength(input, input.readerIndex());
        int length = resolveWireLength(packetLength);
        ControlState.ControlType controlType = switch (packetLength) {
            case 0 -> ControlState.ControlType.FLUSH;
            case 1 -> ControlState.ControlType.DELIMITER;
            case 2 -> ControlState.ControlType.RESPONSE_END;
            default -> ControlState.ControlType.DATA;
        };
        return new ControlState.ControlSuccess(controlType, length);
    }

    private int resolveWireLength(int packetLength) {
        if (packetLength == 3) {
            throw new IllegalArgumentException("Pkt-line length 0003 is reserved");
        }
        if (packetLength < PKT_LINE_HEADER_SIZE) {
            return PKT_LINE_HEADER_SIZE;
        }
        if (packetLength > MAX_PKT_LINE_LENGTH) {
            throw new IllegalArgumentException("Pkt-line length exceeds Git pkt-line limit");
        }
        return packetLength;
    }
}
