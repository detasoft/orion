package pro.deta.orion.git.parser.wire.protocolv2.response;

import java.util.List;
import java.util.Objects;

public record GitLsRefsResponse(List<GitLsRef> refs) {
    public GitLsRefsResponse {
        Objects.requireNonNull(refs, "refs");
        refs = List.copyOf(refs);
    }
}
