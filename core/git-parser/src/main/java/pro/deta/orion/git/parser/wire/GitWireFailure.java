package pro.deta.orion.git.parser.wire;

import java.util.Objects;

public record GitWireFailure(GitWireError error) {
    public GitWireFailure {
        Objects.requireNonNull(error, "error");
    }
}
