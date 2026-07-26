package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static pro.deta.orion.git.parser.wire.control.ControlState.PKT_LINE_HEADER_SIZE;

public final class GitProtocolV2SectionParser {
    private static final String COMMAND_PREFIX = "command=";
    private static final String ERROR_PREFIX = "ERR ";

    private GitProtocolV2SectionParser() {
    }

    public static GitProtocolV2Request read(ByteBuf input) {
        Objects.requireNonNull(input, "input");
        ParserState state = new ParserState();
        while (input.isReadable()) {
            Packet packet = readPacket(input);
            switch (packet.kind()) {
                case FLUSH -> {
                    return state.toRequest(GitProtocolV2Request.Terminal.FLUSH, Optional.empty());
                }
                case DELIMITER -> state.seeDelimiter();
                case RESPONSE_END -> {
                    return state.toRequest(GitProtocolV2Request.Terminal.RESPONSE_END, Optional.empty());
                }
                case DATA -> {
                    Optional<GitProtocolV2Request> terminalRequest = state.acceptData(packet.payload());
                    if (terminalRequest.isPresent()) {
                        return terminalRequest.get();
                    }
                }
            }
        }
        throw new IllegalArgumentException("Protocol v2 request ended before a terminal packet");
    }

    private static Packet readPacket(ByteBuf input) {
        if (input.readableBytes() < PKT_LINE_HEADER_SIZE) {
            throw new IllegalArgumentException("Truncated protocol v2 packet header");
        }
        int headerIndex = input.readerIndex();
        int packetLength = GitNativeUtils.packetLength(input, headerIndex);
        return switch (packetLength) {
            case 0 -> {
                input.skipBytes(PKT_LINE_HEADER_SIZE);
                yield new Packet(PacketKind.FLUSH, "");
            }
            case 1 -> {
                input.skipBytes(PKT_LINE_HEADER_SIZE);
                yield new Packet(PacketKind.DELIMITER, "");
            }
            case 2 -> {
                input.skipBytes(PKT_LINE_HEADER_SIZE);
                yield new Packet(PacketKind.RESPONSE_END, "");
            }
            case 3 -> throw new IllegalArgumentException("Reserved protocol v2 pkt-line length");
            default -> readDataPacket(input, packetLength);
        };
    }

    private static Packet readDataPacket(ByteBuf input, int packetLength) {
        if (packetLength < PKT_LINE_HEADER_SIZE) {
            throw new IllegalArgumentException("Protocol v2 data packet length is invalid");
        }
        if (packetLength > GitFixedControlFrameReader.MAX_PKT_LINE_LENGTH) {
            throw new IllegalArgumentException("Protocol v2 packet exceeds Git pkt-line limit");
        }
        if (input.readableBytes() < packetLength) {
            throw new IllegalArgumentException("Truncated protocol v2 packet payload");
        }
        int payloadLength = packetLength - PKT_LINE_HEADER_SIZE;
        input.skipBytes(PKT_LINE_HEADER_SIZE);
        String payload = input.readCharSequence(payloadLength, StandardCharsets.UTF_8).toString();
        return new Packet(PacketKind.DATA, stripLineEnding(payload));
    }

    private static String stripLineEnding(String payload) {
        if (!payload.endsWith("\n")) {
            return payload;
        }
        String stripped = payload.substring(0, payload.length() - 1);
        if (stripped.endsWith("\r")) {
            return stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    private enum PacketKind {
        DATA,
        FLUSH,
        DELIMITER,
        RESPONSE_END
    }

    private record Packet(PacketKind kind, String payload) {
    }

    private static final class ParserState {
        private final List<GitProtocolV2Line> capabilities = new ArrayList<>();
        private final List<GitProtocolV2Line> arguments = new ArrayList<>();
        private String command = "";
        private boolean delimiterSeen;

        private Optional<GitProtocolV2Request> acceptData(String line) {
            if (line.startsWith(ERROR_PREFIX)) {
                return Optional.of(toRequest(
                        GitProtocolV2Request.Terminal.ERROR,
                        Optional.of(line.substring(ERROR_PREFIX.length()))));
            }
            if (command.isEmpty()) {
                command = parseCommand(line);
                return Optional.empty();
            }
            if (line.startsWith(COMMAND_PREFIX)) {
                throw new IllegalArgumentException("Protocol v2 request must contain only one command packet");
            }
            if (!delimiterSeen) {
                if (containsWhitespace(line)) {
                    throw new IllegalArgumentException("Protocol v2 arguments must follow a delimiter packet");
                }
                capabilities.add(new GitProtocolV2Line(line));
                return Optional.empty();
            }
            arguments.add(new GitProtocolV2Line(line));
            return Optional.empty();
        }

        private void seeDelimiter() {
            if (command.isEmpty()) {
                throw new IllegalArgumentException("Protocol v2 delimiter cannot appear before command packet");
            }
            if (delimiterSeen) {
                throw new IllegalArgumentException("Protocol v2 request must contain only one delimiter packet");
            }
            delimiterSeen = true;
        }

        private GitProtocolV2Request toRequest(
                GitProtocolV2Request.Terminal terminal,
                Optional<String> protocolError) {
            return new GitProtocolV2Request(command, capabilities, arguments, terminal, protocolError);
        }

        private static String parseCommand(String line) {
            if (!line.startsWith(COMMAND_PREFIX)) {
                throw new IllegalArgumentException("Protocol v2 request must start with command packet");
            }
            String parsedCommand = line.substring(COMMAND_PREFIX.length());
            if (parsedCommand.isEmpty() || containsWhitespace(parsedCommand)) {
                throw new IllegalArgumentException("Protocol v2 command name is invalid");
            }
            return parsedCommand;
        }

        private static boolean containsWhitespace(String value) {
            for (int index = 0; index < value.length(); index++) {
                if (Character.isWhitespace(value.charAt(index))) {
                    return true;
                }
            }
            return false;
        }
    }
}
