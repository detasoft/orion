package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import pro.deta.orion.git.parser.wire.control.ControlState;

import java.util.Objects;

public final class GitFixedControlFrameReader {
    static final int MAX_PKT_LINE_LENGTH = 65_520;

    private static final int HEADER_SIZE = 4;
    private final ByteBufAllocator allocator;
    private ControlState controlState = ControlState.ControlEmpty.INSTANCE;

    public GitFixedControlFrameReader(ByteBufAllocator allocator) {
        this.allocator = Objects.requireNonNull(allocator, "allocator");
    }

    public ControlState controlState() {
        return controlState;
    }

    public ControlState accept(ByteBuf input) {
        Objects.requireNonNull(input, "input");
        controlState = switch (controlState) {
            case ControlState.ControlSuccess _ignored ->
                    throw new IllegalStateException("Control frame is already complete");

            case ControlState.ControlEmpty _ignored -> readEmpty(input);

            case ControlState.MoreDataNeeded prevData -> readMore(prevData.fragment(), input);
        };
        return controlState;
    }

    private ControlState readEmpty(ByteBuf input) {
        if (!input.isReadable()) {
            return controlState;
        }
        if (input.readableBytes() >= HEADER_SIZE) {
            ControlState state = buildControlState(input);
            input.skipBytes(HEADER_SIZE);
            return state;
        }
        return new ControlState.MoreDataNeeded(CachingByteBuf.start(allocator, input, HEADER_SIZE, CachingByteBuf.Mode.BUFFERED));
    }

    private ControlState readMore(ByteBuf previousFragment, ByteBuf input) {
        CachingByteBuf.append(previousFragment, input, HEADER_SIZE, CachingByteBuf.Mode.BUFFERED);
        if (!CachingByteBuf.isComplete(previousFragment, HEADER_SIZE)) {
            return new ControlState.MoreDataNeeded(previousFragment);
        }
        try {
            return buildControlState(previousFragment);
        } catch (RuntimeException | Error e) {
            controlState = ControlState.ControlEmpty.INSTANCE;
            throw e;
        } finally {
            previousFragment.release();
        }
    }

    private ControlState buildControlState(ByteBuf input) {
        int packetLength = packetLength(input, input.readerIndex());
        int length = resolveWireLength(packetLength);
        ControlState.ControlType controlType = controlType(packetLength);
        return new ControlState.ControlSuccess(controlType, length);
    }

    private int packetLength(ByteBuf input, int headerIndex) {
        int packetLength = 0;
        for (int i = 0; i < HEADER_SIZE; i++) {
            packetLength = (packetLength << 4) | hexValue(input.getByte(headerIndex + i));
        }
        return packetLength;
    }

    private ControlState.ControlType controlType(int packetLength) {
        return switch (packetLength) {
            case 0 -> ControlState.ControlType.FLUSH;
            case 1 -> ControlState.ControlType.DELIMITER;
            case 2 -> ControlState.ControlType.RESPONSE_END;
            default -> ControlState.ControlType.DATA;
        };
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
}
