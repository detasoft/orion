package pro.deta.orion.git.parser.wire.continuation.v2;

import io.netty.buffer.ByteBuf;
import pro.deta.orion.continuation.Continuation;
import pro.deta.orion.continuation.ContinuationFlow;

final class LsRefsArgumentPayloadContinuation
        implements Continuation<ByteBuf> {
    private static final String REF_PREFIX = "ref-prefix ";

    private final LsRefsContinuation lsRefs;
    private final StringBuilder payload;
    private int remainingBytes;
    private boolean invalid;

    LsRefsArgumentPayloadContinuation(
            LsRefsContinuation lsRefs,
            int payloadLength) {
        this.lsRefs = lsRefs;
        this.remainingBytes = payloadLength;
        this.payload = new StringBuilder(payloadLength);
    }

    @Override
    public ContinuationFlow<ByteBuf> process(ByteBuf input) {
        while (remainingBytes > 0 && input.isReadable()) {
            int value = input.readUnsignedByte();
            remainingBytes--;
            boolean last = remainingBytes == 0;
            if (last && value == '\n') {
                continue;
            }
            if (value < 0x20 || value > 0x7e) {
                invalid = true;
            } else {
                payload.append((char) value);
            }
        }
        if (remainingBytes > 0) {
            return ContinuationFlow.await();
        }
        LsRefsContinuation.Argument argument = completeArgument();
        if (invalid || argument == null) {
            return ContinuationFlow.transition(
                    LsRefsContinuation.failed());
        }
        if (!lsRefs.accept(argument)) {
            return ContinuationFlow.transition(
                    LsRefsContinuation.failed());
        }
        return ContinuationFlow.transition(lsRefs);
    }

    private LsRefsContinuation.Argument completeArgument() {
        if (invalid) {
            return null;
        }
        String value = payload.toString();
        if (value.isEmpty()) {
            return null;
        }
        return switch (value) {
            case "peel" -> LsRefsContinuation.SimpleArgument.PEEL;
            case "symrefs" -> LsRefsContinuation.SimpleArgument.SYMREFS;
            case "unborn" -> LsRefsContinuation.SimpleArgument.UNBORN;
            default -> {
                if (value.equals("ref-prefix")) {
                    yield null;
                }
                if (value.startsWith(REF_PREFIX)) {
                    String prefix = value.substring(REF_PREFIX.length());
                    yield prefix.isEmpty()
                            ? null
                            : new LsRefsContinuation.RefPrefix(prefix);
                }
                if (isMalformedKnownFlag(value)) {
                    yield null;
                }
                yield LsRefsContinuation.Unknown.VALUE;
            }
        };
    }

    private static boolean isMalformedKnownFlag(String value) {
        return startsWithFlagAndExtra(value, "peel")
                || startsWithFlagAndExtra(value, "symrefs")
                || startsWithFlagAndExtra(value, "unborn");
    }

    private static boolean startsWithFlagAndExtra(
            String value,
            String flag) {
        return value.length() > flag.length()
                && value.startsWith(flag)
                && value.charAt(flag.length()) == ' ';
    }
}
