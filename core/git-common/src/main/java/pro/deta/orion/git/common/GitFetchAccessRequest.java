package pro.deta.orion.git.common;

import java.util.List;
import java.util.Objects;

public record GitFetchAccessRequest(
        String repositoryName,
        List<GitObjectId> wants,
        GitRefResolver refResolver) {

    public GitFetchAccessRequest {
        Objects.requireNonNull(repositoryName, "repositoryName");
        wants = List.copyOf(wants);
        Objects.requireNonNull(refResolver, "refResolver");
    }
}
