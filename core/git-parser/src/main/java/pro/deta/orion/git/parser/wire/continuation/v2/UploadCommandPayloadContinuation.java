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
        UploadCommandContinuation.Command command =
                parser.completeCommand();
        if (command == null || !request.acceptCommand(command)) {
            return ContinuationFlow.transition(
                    UploadCommandContinuation.failed());
        }
        return ContinuationFlow.transition(
                new ControlHeaderContinuation(request::next));
    }

    private static final class UploadCommandParser {
        private static final byte[] PREFIX =
                ascii("command=");
        private static final byte[] LS_REFS =
                ascii("ls-refs");
        private static final byte[] FETCH =
                ascii("fetch");

        private int remainingBytes;
        private int tokenIndex;
        private Phase phase = Phase.PREFIX;
        private byte[] expectedCommand;
        private UploadCommandContinuation.Command command;

        private UploadCommandParser(int payloadLength) {
            this.remainingBytes = payloadLength;
        }

        private boolean nextByte(ByteBuf input) {
            int value = input.readUnsignedByte();
            remainingBytes--;
            boolean last = remainingBytes == 0;
            if (last && value == '\n') {
                phase = Phase.COMPLETE;
                return commandComplete();
            }
            if (phase == Phase.COMPLETE
                    || value > 0x7f
                    || value == 0) {
                return false;
            }
            boolean accepted = switch (phase) {
                case PREFIX -> acceptPrefix(value);
                case COMMAND_NAME -> acceptCommandByte(value);
                case COMPLETE -> false;
            };
            if (!accepted) {
                return false;
            }
            if (last) {
                phase = Phase.COMPLETE;
                return commandComplete();
            }
            return true;
        }

        private boolean notDone() {
            return remainingBytes > 0;
        }

        private UploadCommandContinuation.Command completeCommand() {
            return phase == Phase.COMPLETE && commandComplete()
                    ? command
                    : null;
        }

        private boolean acceptPrefix(int value) {
            if (!matches(PREFIX, value)) {
                return false;
            }
            tokenIndex++;
            if (tokenIndex == PREFIX.length) {
                tokenIndex = 0;
                phase = Phase.COMMAND_NAME;
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

        private boolean matches(byte[] expected, int value) {
            return tokenIndex < expected.length
                    && expected[tokenIndex] == (byte) value;
        }

        private boolean commandComplete() {
            return expectedCommand != null
                    && tokenIndex == expectedCommand.length;
        }

        private static byte[] ascii(String value) {
            return value.getBytes(
                    java.nio.charset.StandardCharsets.US_ASCII);
        }

        private enum Phase {
            PREFIX,
            COMMAND_NAME,
            COMPLETE
        }
    }
}
