package pro.deta.orion.git.parser.wire.protocolv2.response;

import java.util.Objects;

public record GitFetchWantedRef(String objectId, String refName) {
    public GitFetchWantedRef {
        Objects.requireNonNull(objectId, "objectId");
        Objects.requireNonNull(refName, "refName");
    }
}
