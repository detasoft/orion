package pro.deta.orion.git.parser.wire;

import java.util.Objects;

public final class GitWireException extends RuntimeException {
    private final GitWireError error;

    public GitWireException(GitWireError error) {
        super(Objects.requireNonNull(error, "error").message());
        this.error = error;
    }

    public GitWireError error() {
        return error;
    }

    static GitWireException of(
            GitWireError.Kind kind,
            GitWireError.Phase phase,
            long packetIndex,
            long byteOffset,
            String message) {
        return new GitWireException(new GitWireError(kind, phase, packetIndex, byteOffset, message));
    }
}
