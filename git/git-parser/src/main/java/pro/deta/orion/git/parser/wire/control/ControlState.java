package pro.deta.orion.git.parser.wire.control;

import pro.deta.orion.git.parser.wire.GitPktLineFormatException;
import pro.deta.orion.git.parser.wire.error.GitGeneralException;
import pro.deta.orion.net.io.BufferedByteInput;
import pro.deta.orion.util.Result;

import java.io.IOException;

import static pro.deta.orion.git.parser.wire.GitNativeUtils.HEX_VALUES;
import static pro.deta.orion.git.parser.wire.error.GitWireError.Kind.*;

public record ControlState(ControlType type, int length) {
    public static final int PKT_LINE_HEADER_SIZE = 4;
    public static final int MAX_PKT_LINE_LENGTH = 65_520;

    public enum ControlType {
        DATA,
        FLUSH,
        DELIMITER,
        RESPONSE_END
    }

    public int payloadLength() {
        return length - PKT_LINE_HEADER_SIZE;
    }

    public static ControlState readFrom(BufferedByteInput input) throws IOException {
        int header = 0;
        for (int i = 0; i < ControlState.PKT_LINE_HEADER_SIZE; i++) {
            header = (header << 8) | input.readUnsignedByte();
        }
        return controlStateValueOf(header);
    }

    public static ControlState controlStateValueOf(int value) throws GitPktLineFormatException {
        switch (readControlType(value)) {
            case Result.Failure<ControlState> v -> {
                throw new GitPktLineFormatException("Invalid pkt-line header: " + v.getMessage(), v.throwable());
            }
            case Result.Success<ControlState> v -> {
                return v.value();
            }
        }
    }

    public static Result<ControlState> readControlType(int headerValue) {
        int h0 = HEX_VALUES[(headerValue >>> 24) & 0xff];
        int h1 = HEX_VALUES[(headerValue >>> 16) & 0xff];
        int h2 = HEX_VALUES[(headerValue >>> 8) & 0xff];
        int h3 = HEX_VALUES[headerValue & 0xff];
        if ((h0 | h1 | h2 | h3) < 0) {
            return Result.Failure.generalFailure(new GitGeneralException(INVALID_HEX_HEADER));
        }
        final int packetLength = (h0 << 12) | (h1 << 8) | (h2 << 4) | h3;

        ControlState.ControlType type = switch (packetLength) {
            case 0 -> ControlState.ControlType.FLUSH;
            case 1 -> ControlState.ControlType.DELIMITER;
            case 2 -> ControlState.ControlType.RESPONSE_END;
            default -> ControlState.ControlType.DATA;
        };
        if (packetLength == 3) {
            return Result.Failure.generalFailure(new GitGeneralException(RESERVED_LENGTH));
        }

        int length = packetLength < PKT_LINE_HEADER_SIZE
                ? PKT_LINE_HEADER_SIZE
                : packetLength;

        if (packetLength > MAX_PKT_LINE_LENGTH) {
            return Result.Failure.generalFailure(new GitGeneralException(LENGTH_EXCEEDS_LIMIT));
        }

        ControlState control = new ControlState(type, length);
        return Result.of(control);
    }
}
