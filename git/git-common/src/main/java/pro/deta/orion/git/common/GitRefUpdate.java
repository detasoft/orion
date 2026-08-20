package pro.deta.orion.git.common;

import java.util.Objects;

public record GitRefUpdate(
        String refName,
        GitObjectId oldId,
        GitObjectId newId,
        GitRefUpdateType type,
        GitRefUpdateResult result) {

    public GitRefUpdate {
        Objects.requireNonNull(refName, "refName");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(result, "result");
    }
}
