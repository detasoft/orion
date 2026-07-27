package pro.deta.orion.git.parser.wire.protocolv2.response;

import java.util.List;
import java.util.Objects;

public record GitFetchShallowInfo(
        List<String> shallowObjectIds,
        List<String> unshallowObjectIds) {

    public GitFetchShallowInfo {
        Objects.requireNonNull(shallowObjectIds, "shallowObjectIds");
        Objects.requireNonNull(unshallowObjectIds, "unshallowObjectIds");
        shallowObjectIds = List.copyOf(shallowObjectIds);
        unshallowObjectIds = List.copyOf(unshallowObjectIds);
    }
}
