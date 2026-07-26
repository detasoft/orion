package pro.deta.orion.git.parser.wire;

import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class GitProtocolV2SectionParser {
    private static final String COMMAND_PREFIX = "command=";
    private static final String ERROR_PREFIX = "ERR ";

    private GitProtocolV2SectionParser() {
    }

    public static GitProtocolV2Request read(ByteBuf input) {
        Objects.requireNonNull(input, "input");
        ParserState state = new ParserState();
        long packetIndex = 0;
        while (input.isReadable()) {
            GitPktLineReader.Packet packet = GitPktLineReader.read(input, packetIndex);
            packetIndex++;
            switch (packet.kind()) {
                case FLUSH -> {
                    return state.toRequest(GitProtocolV2Request.Terminal.FLUSH, Optional.empty());
                }
                case DELIMITER -> state.seeDelimiter(packet.packetIndex(), packet.byteOffset());
                case RESPONSE_END -> {
                    return state.toRequest(GitProtocolV2Request.Terminal.RESPONSE_END, Optional.empty());
                }
                case DATA -> {
                    Optional<GitProtocolV2Request> terminalRequest = state.acceptData(
                            packet.payload(),
                            packet.packetIndex(),
                            packet.byteOffset());
                    if (terminalRequest.isPresent()) {
                        return terminalRequest.get();
                    }
                }
            }
        }
        throw semanticError(
                GitWireError.UNKNOWN_INDEX,
                input.readerIndex(),
                "Protocol v2 request ended before a terminal packet");
    }

    private static GitWireException semanticError(
            long packetIndex,
            long byteOffset,
            String message) {
        return GitWireException.of(
                GitWireError.Kind.INVALID_PROTOCOL_V2_REQUEST,
                GitWireError.Phase.STRUCTURED_PAYLOAD,
                packetIndex,
                byteOffset,
                message);
    }

    private static final class ParserState {
        private final List<GitProtocolV2Line> capabilities = new ArrayList<>();
        private final List<GitProtocolV2Line> arguments = new ArrayList<>();
        private String command = "";
        private boolean delimiterSeen;

        private Optional<GitProtocolV2Request> acceptData(String line, long packetIndex, long byteOffset) {
            validateLine(line, packetIndex, byteOffset);
            if (line.startsWith(ERROR_PREFIX)) {
                return Optional.of(toRequest(
                        GitProtocolV2Request.Terminal.ERROR,
                        Optional.of(line.substring(ERROR_PREFIX.length()))));
            }
            if (command.isEmpty()) {
                command = parseCommand(line, packetIndex, byteOffset);
                return Optional.empty();
            }
            if (line.startsWith(COMMAND_PREFIX)) {
                throw semanticError(
                        packetIndex,
                        byteOffset,
                        "Protocol v2 request must contain only one command packet");
            }
            if (!delimiterSeen) {
                if (containsWhitespace(line)) {
                    throw semanticError(
                            packetIndex,
                            byteOffset,
                            "Protocol v2 arguments must follow a delimiter packet");
                }
                capabilities.add(new GitProtocolV2Line(line));
                return Optional.empty();
            }
            arguments.add(new GitProtocolV2Line(line));
            return Optional.empty();
        }

        private void seeDelimiter(long packetIndex, long byteOffset) {
            if (command.isEmpty()) {
                throw semanticError(
                        packetIndex,
                        byteOffset,
                        "Protocol v2 delimiter cannot appear before command packet");
            }
            if (delimiterSeen) {
                throw semanticError(
                        packetIndex,
                        byteOffset,
                        "Protocol v2 request must contain only one delimiter packet");
            }
            delimiterSeen = true;
        }

        private GitProtocolV2Request toRequest(
                GitProtocolV2Request.Terminal terminal,
                Optional<String> protocolError) {
            return new GitProtocolV2Request(command, capabilities, arguments, terminal, protocolError);
        }

        private static String parseCommand(String line, long packetIndex, long byteOffset) {
            if (!line.startsWith(COMMAND_PREFIX)) {
                throw semanticError(
                        packetIndex,
                        byteOffset,
                        "Protocol v2 request must start with command packet");
            }
            String parsedCommand = line.substring(COMMAND_PREFIX.length());
            if (parsedCommand.isEmpty() || containsWhitespace(parsedCommand)) {
                throw semanticError(
                        packetIndex,
                        byteOffset,
                        "Protocol v2 command name is invalid");
            }
            return parsedCommand;
        }

        private static void validateLine(String line, long packetIndex, long byteOffset) {
            if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
                throw semanticError(
                        packetIndex,
                        byteOffset,
                        "Protocol v2 line must not contain line endings");
            }
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
