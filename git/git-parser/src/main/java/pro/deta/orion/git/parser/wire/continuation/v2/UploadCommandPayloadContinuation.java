package pro.deta.orion.git.parser.wire.continuation.v2;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.parser.wire.continuation.ControlHeaderContinuation;

final class UploadCommandPayloadContinuation
        implements Continuation<ByteBuf> {
    private final UploadCommandContinuation request;
    private final UploadCommandParser parser;

    UploadCommandPayloadContinuation(
            UploadCommandContinuation request,
            int payloadLength) {
        this.request = request;
        this.parser = new UploadCommandParser(payloadLength);
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        while (parser.notDone()) {
            if (!input.isReadable()) {
                return ContinuationFlow.await();
            }
            if (!parser.nextByte(input)) {
                return ContinuationFlow.transition(
                        UploadCommandContinuation.failed());
            }
        }
        UploadCommandParser.Header header = parser.completeHeader();
        if (header == null || !accept(header)) {
            return ContinuationFlow.transition(
                    UploadCommandContinuation.failed());
        }
        return ContinuationFlow.transition(
                new ControlHeaderContinuation(request::next));
    }

    private boolean accept(UploadCommandParser.Header header) {
        if (header instanceof UploadCommandParser.CommandHeader command) {
            return request.acceptCommand(command.command());
        }
        return request.acceptServerOption();
    }

    private static final class UploadCommandParser {
        private static final byte[] COMMAND_PREFIX =
                ascii("command=");
        private static final byte[] SERVER_OPTION_PREFIX =
                ascii("server-option=");
        private static final byte[] LS_REFS =
                ascii("ls-refs");
        private static final byte[] FETCH =
                ascii("fetch");

        private int remainingBytes;
        private int tokenIndex;
        private Phase phase = Phase.PREFIX;
        private HeaderKind headerKind;
        private byte[] expectedCommand;
        private UploadCommandContinuation.Command command;
        private int serverOptionLength;

        private UploadCommandParser(int payloadLength) {
            this.remainingBytes = payloadLength;
        }

        private boolean nextByte(ByteBuf input) {
            int value = input.readUnsignedByte();
            remainingBytes--;
            boolean last = remainingBytes == 0;
            if (last && value == '\n') {
                phase = Phase.COMPLETE;
                return headerComplete();
            }
            if (phase == Phase.COMPLETE
                    || value < 0x20
                    || value > 0x7e) {
                return false;
            }
            boolean accepted = switch (phase) {
                case PREFIX -> acceptPrefix(value);
                case COMMAND_NAME -> acceptCommandByte(value);
                case SERVER_OPTION -> acceptServerOptionByte();
                case COMPLETE -> false;
            };
            if (!accepted) {
                return false;
            }
            if (!last) {
                return true;
            }
            if (headerKind == HeaderKind.COMMAND
                    && headerComplete()) {
                phase = Phase.COMPLETE;
                return true;
            }
            return false;
        }

        private boolean notDone() {
            return remainingBytes > 0;
        }

        private Header completeHeader() {
            if (phase != Phase.COMPLETE || !headerComplete()) {
                return null;
            }
            return switch (headerKind) {
                case COMMAND -> new CommandHeader(command);
                case SERVER_OPTION -> ServerOptionHeader.VALUE;
            };
        }

        private boolean acceptPrefix(int value) {
            byte[] prefix;
            if (headerKind == null) {
                if (value == COMMAND_PREFIX[0]) {
                    headerKind = HeaderKind.COMMAND;
                    prefix = COMMAND_PREFIX;
                } else if (value == SERVER_OPTION_PREFIX[0]) {
                    headerKind = HeaderKind.SERVER_OPTION;
                    prefix = SERVER_OPTION_PREFIX;
                } else {
                    return false;
                }
            } else {
                prefix = headerKind == HeaderKind.COMMAND
                        ? COMMAND_PREFIX
                        : SERVER_OPTION_PREFIX;
            }
            if (!matches(prefix, value)) {
                return false;
            }
            tokenIndex++;
            if (tokenIndex == prefix.length) {
                tokenIndex = 0;
                phase = headerKind == HeaderKind.COMMAND
                        ? Phase.COMMAND_NAME
                        : Phase.SERVER_OPTION;
            }
            return true;
        }

        private boolean acceptCommandByte(int value) {
            if (expectedCommand == null) {
                if (value == LS_REFS[0]) {
                    expectedCommand = LS_REFS;
                    command =
                            UploadCommandContinuation.Command.LS_REFS;
                } else if (value == FETCH[0]) {
                    expectedCommand = FETCH;
                    command =
                            UploadCommandContinuation.Command.FETCH;
                } else {
                    return false;
                }
            }
            if (!matches(expectedCommand, value)) {
                return false;
            }
            tokenIndex++;
            return true;
        }

        private boolean acceptServerOptionByte() {
            serverOptionLength++;
            return true;
        }

        private boolean matches(byte[] expected, int value) {
            return tokenIndex < expected.length
                    && expected[tokenIndex] == (byte) value;
        }

        private boolean headerComplete() {
            if (headerKind == null) {
                return false;
            }
            return switch (headerKind) {
                case COMMAND -> expectedCommand != null
                        && tokenIndex == expectedCommand.length;
                case SERVER_OPTION -> serverOptionLength > 0;
            };
        }

        private static byte[] ascii(String value) {
            return value.getBytes(
                    java.nio.charset.StandardCharsets.US_ASCII);
        }

        private enum Phase {
            PREFIX,
            COMMAND_NAME,
            SERVER_OPTION,
            COMPLETE
        }

        private enum HeaderKind {
            COMMAND,
            SERVER_OPTION
        }

        private sealed interface Header
                permits CommandHeader, ServerOptionHeader {
        }

        private record CommandHeader(
                UploadCommandContinuation.Command command)
                implements Header {
        }

        private enum ServerOptionHeader implements Header {
            VALUE
        }
    }
}
