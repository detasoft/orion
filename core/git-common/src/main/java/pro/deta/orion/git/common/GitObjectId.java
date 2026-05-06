package pro.deta.orion.git.common;

import java.util.Objects;

public record GitObjectId(String value) {
    public GitObjectId {
        Objects.requireNonNull(value, "value");
    }

    public static GitObjectId of(String value) {
        return new GitObjectId(value);
    }
}
