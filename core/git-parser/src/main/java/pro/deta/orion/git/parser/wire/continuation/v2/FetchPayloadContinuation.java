package pro.deta.orion.git.parser.wire.continuation.v2;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;
import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.parser.wire.continuation.ControlHeaderContinuation;

final class FetchPayloadContinuation implements Continuation<ByteBuf> {
    private final FetchContinuation fetch;
    private final FetchPayloadParser parser;

    FetchPayloadContinuation(
            FetchContinuation fetch,
            int payloadLength) {
        this.fetch = fetch;
        this.parser = new FetchPayloadParser(payloadLength);
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        try {
            while (parser.notDone()) {
                if (!input.isReadable()) {
                    return ContinuationFlow.await();
                }
                if (!parser.nextByte(input)) {
                    return ContinuationFlow.transition(
                            FetchContinuation.failed());
                }
            }
            FetchContinuation.FetchArgument argument =
                    parser.completeArgument();
            if (argument == null) {
                return ContinuationFlow.transition(
                        FetchContinuation.failed());
            }
            fetch.accept(argument);
            return ContinuationFlow.transition(
                    new ControlHeaderContinuation(fetch::next));
        } catch (Throwable error) {
            return ContinuationFlow.transition(
                    FetchContinuation.failed());
        }
    }

    private static final class FetchPayloadParser {
        private static final int OBJECT_ID_LENGTH = 40;

        private final StringBuilder objectId =
                new StringBuilder(OBJECT_ID_LENGTH);
        private int remainingBytes;
        private int tokenIndex;
        private Phase phase = Phase.TOKEN;
        private Token token;

        private FetchPayloadParser(int payloadLength) {
            this.remainingBytes = payloadLength;
        }

        private boolean nextByte(ByteBuf input) {
            int value = input.readUnsignedByte();
            remainingBytes--;
            boolean last = remainingBytes == 0;
            if (last && value == '\n') {
                return phase == Phase.COMPLETE;
            }
            if (value > 0x7f || value == 0) {
                return false;
            }
            boolean accepted = switch (phase) {
                case TOKEN -> acceptTokenByte(value);
                case OBJECT_ID -> acceptObjectIdByte(value);
                case COMPLETE -> false;
            };
            return accepted && (!last || phase == Phase.COMPLETE);
        }

        private boolean acceptTokenByte(int value) {
            if (token == null) {
                token = Token.startingWith(value);
                if (token == null) {
                    return false;
                }
            }
            if (tokenIndex >= token.prefix.length()
                    || token.prefix.charAt(tokenIndex) != value) {
                return false;
            }
            tokenIndex++;
            if (tokenIndex == token.prefix.length()) {
                phase = token.objectKind == null
                        ? Phase.COMPLETE
                        : Phase.OBJECT_ID;
            }
            return true;
        }

        private boolean acceptObjectIdByte(int value) {
            if (!isHexadecimal(value)
                    || objectId.length() == OBJECT_ID_LENGTH) {
                return false;
            }
            objectId.append((char) value);
            if (objectId.length() == OBJECT_ID_LENGTH) {
                phase = Phase.COMPLETE;
            }
            return true;
        }

        private boolean notDone() {
            return remainingBytes > 0;
        }

        private FetchContinuation.FetchArgument completeArgument() {
            if (remainingBytes != 0
                    || phase != Phase.COMPLETE
                    || token == null) {
                return null;
            }
            if (token.objectKind != null) {
                return new FetchContinuation.ObjectArgument(
                        token.objectKind,
                        GitObjectId.of(objectId.toString()));
            }
            return token.simple;
        }

        private static boolean isHexadecimal(int value) {
            return value >= '0' && value <= '9'
                    || value >= 'a' && value <= 'f'
                    || value >= 'A' && value <= 'F';
        }

        private enum Phase {
            TOKEN,
            OBJECT_ID,
            COMPLETE
        }

        private enum Token {
            WANT(
                    "want ",
                    FetchContinuation.ObjectArgumentKind.WANT),
            HAVE(
                    "have ",
                    FetchContinuation.ObjectArgumentKind.HAVE),
            DONE("done", FetchContinuation.SimpleArgument.DONE),
            THIN_PACK(
                    "thin-pack",
                    FetchContinuation.SimpleArgument.THIN_PACK),
            OFS_DELTA(
                    "ofs-delta",
                    FetchContinuation.SimpleArgument.OFS_DELTA),
            NO_PROGRESS(
                    "no-progress",
                    FetchContinuation.SimpleArgument.NO_PROGRESS),
            INCLUDE_TAG(
                    "include-tag",
                    FetchContinuation.SimpleArgument.INCLUDE_TAG);

            private final String prefix;
            private final FetchContinuation.ObjectArgumentKind objectKind;
            private final FetchContinuation.SimpleArgument simple;

            Token(
                    String prefix,
                    FetchContinuation.ObjectArgumentKind objectKind) {
                this.prefix = prefix;
                this.objectKind = objectKind;
                this.simple = null;
            }

            Token(
                    String prefix,
                    FetchContinuation.SimpleArgument simple) {
                this.prefix = prefix;
                this.objectKind = null;
                this.simple = simple;
            }

            private static Token startingWith(int value) {
                return switch (value) {
                    case 'w' -> WANT;
                    case 'h' -> HAVE;
                    case 'd' -> DONE;
                    case 't' -> THIN_PACK;
                    case 'o' -> OFS_DELTA;
                    case 'n' -> NO_PROGRESS;
                    case 'i' -> INCLUDE_TAG;
                    default -> null;
                };
            }
        }
    }
}
