package pro.deta.orion.git.parser.wire;

import java.io.IOException;

public final class GitPktLineFormatException extends IOException {
    public GitPktLineFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
