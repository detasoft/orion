package pro.deta.orion.agentd.terminal;

import java.io.ByteArrayOutputStream;

final class TerminalInputParser {
    private static final int ESCAPE = 0x1d;
    private boolean pendingEscape;
    private boolean detached;

    Result accept(byte[] input) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(input.length);
        for (byte value : input) {
            if (detached) {
                break;
            }
            int unsigned = value & 0xff;
            if (!pendingEscape) {
                if (unsigned == ESCAPE) {
                    pendingEscape = true;
                } else {
                    output.write(unsigned);
                }
                continue;
            }
            pendingEscape = false;
            if (unsigned == 'd') {
                detached = true;
            } else if (unsigned == ESCAPE) {
                output.write(ESCAPE);
            } else {
                output.write(ESCAPE);
                output.write(unsigned);
            }
        }
        return new Result(output.toByteArray(), detached);
    }

    Result finish() {
        if (!pendingEscape || detached) {
            return new Result(new byte[0], detached);
        }
        pendingEscape = false;
        return new Result(new byte[]{ESCAPE}, false);
    }

    record Result(byte[] bytes, boolean detached) {
    }
}
