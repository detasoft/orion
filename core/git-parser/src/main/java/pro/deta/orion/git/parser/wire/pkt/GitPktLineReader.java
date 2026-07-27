package pro.deta.orion.git.parser.wire.pkt;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.git.parser.wire.GitFixedControlFrameReader;
import pro.deta.orion.git.parser.wire.GitNativeUtils;
import pro.deta.orion.git.parser.wire.GitWireError;
import pro.deta.orion.git.parser.wire.GitWireException;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static pro.deta.orion.git.parser.wire.control.ControlState.PKT_LINE_HEADER_SIZE;

/**
 * Reads a single Git pkt-line from a ByteBuf that already holds the entire
 * packet. Callers that need to resume across partial input buffers should use
 * {@link GitFixedControlFrameReader} instead.
 */
public final class GitPktLineReader {
    private GitPktLineReader() {
    }

    public static Packet read(ByteBuf input, long packetIndex) {
        return read(input, packetIndex, 0);
    }

    public static Packet read(ByteBuf input, long packetIndex, int startReaderIndex) {
        Header header = readHeader(input, packetIndex, startReaderIndex);
        return switch (header.packetLength()) {
            case 0 -> {
                input.skipBytes(PKT_LINE_HEADER_SIZE);
                yield new Packet(Kind.FLUSH, "", header.packetIndex(), header.byteOffset());
            }
            case 1 -> {
                input.skipBytes(PKT_LINE_HEADER_SIZE);
                yield new Packet(Kind.DELIMITER, "", header.packetIndex(), header.byteOffset());
            }
            case 2 -> {
                input.skipBytes(PKT_LINE_HEADER_SIZE);
                yield new Packet(Kind.RESPONSE_END, "", header.packetIndex(), header.byteOffset());
            }
            case 3 -> throw GitWireException.of(
                    GitWireError.Kind.RESERVED_LENGTH,
                    GitWireError.Phase.CONTROL_HEADER,
                    header.packetIndex(),
                    header.byteOffset(),
                    "Pkt-line length 0003 is reserved");
            default -> readData(input, header);
        };
    }

    public static Header readHeader(ByteBuf input, long packetIndex) {
        return readHeader(input, packetIndex, 0);
    }

    public static Header readHeader(ByteBuf input, long packetIndex, int startReaderIndex) {
        Objects.requireNonNull(input, "input");
        int headerIndex = input.readerIndex();
        long headerOffset = headerIndex - (long) startReaderIndex;
        if (input.readableBytes() < PKT_LINE_HEADER_SIZE) {
            throw GitWireException.of(
                    GitWireError.Kind.INCOMPLETE_HEADER,
                    GitWireError.Phase.CONTROL_HEADER,
                    packetIndex,
                    headerOffset,
                    "Incomplete pkt-line header");
        }
        int packetLength = GitNativeUtils.packetLength(
                input,
                headerIndex,
                GitWireError.Phase.CONTROL_HEADER,
                packetIndex,
                headerOffset);
        return new Header(packetLength, packetIndex, headerIndex, headerOffset);
    }

    public static String stripLineEnding(String payload) {
        Objects.requireNonNull(payload, "payload");
        if (!payload.endsWith("\n")) {
            return payload;
        }
        String stripped = payload.substring(0, payload.length() - 1);
        if (stripped.endsWith("\r")) {
            return stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    private static Packet readData(ByteBuf input, Header header) {
        int packetLength = header.packetLength();
        if (packetLength < PKT_LINE_HEADER_SIZE) {
            throw GitWireException.of(
                    GitWireError.Kind.RESERVED_LENGTH,
                    GitWireError.Phase.CONTROL_HEADER,
                    header.packetIndex(),
                    header.byteOffset(),
                    "Pkt-line data packet length is invalid");
        }
        if (packetLength > GitFixedControlFrameReader.MAX_PKT_LINE_LENGTH) {
            throw GitWireException.of(
                    GitWireError.Kind.LENGTH_EXCEEDS_LIMIT,
                    GitWireError.Phase.CONTROL_HEADER,
                    header.packetIndex(),
                    header.byteOffset(),
                    "Pkt-line length exceeds Git pkt-line limit");
        }
        if (input.readableBytes() < packetLength) {
            throw GitWireException.of(
                    GitWireError.Kind.INCOMPLETE_PAYLOAD,
                    GitWireError.Phase.STRUCTURED_PAYLOAD,
                    header.packetIndex(),
                    header.byteOffset() + PKT_LINE_HEADER_SIZE,
                    "Incomplete pkt-line payload");
        }
        int payloadLength = packetLength - PKT_LINE_HEADER_SIZE;
        input.skipBytes(PKT_LINE_HEADER_SIZE);
        String payload = input.readCharSequence(payloadLength, StandardCharsets.UTF_8).toString();
        return new Packet(
                Kind.DATA,
                stripLineEnding(payload),
                header.packetIndex(),
                header.byteOffset() + PKT_LINE_HEADER_SIZE);
    }

    public enum Kind {
        DATA,
        FLUSH,
        DELIMITER,
        RESPONSE_END
    }

    public record Packet(Kind kind, String payload, long packetIndex, long byteOffset) {
        public Packet {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(payload, "payload");
        }
    }

    public record Header(int packetLength, long packetIndex, int headerIndex, long byteOffset) {
    }
}
