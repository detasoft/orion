package pro.deta.orion.git.parser.wire.advertisement;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record GitLsRefsResponse(List<Ref> refs) {

    public GitLsRefsResponse {
        Objects.requireNonNull(refs, "refs");
        refs = List.copyOf(refs);
    }

    public sealed interface Ref
            permits DirectRef, UnbornRef {

        String name();
    }

    public record DirectRef(
            String objectId,
            String name,
            Optional<String> symrefTarget,
            Optional<String> peeledObjectId) implements Ref {

        public DirectRef {
            Objects.requireNonNull(objectId, "objectId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(symrefTarget, "symrefTarget");
            Objects.requireNonNull(peeledObjectId, "peeledObjectId");
        }
    }

    public record UnbornRef(
            String name,
            String symrefTarget) implements Ref {

        public UnbornRef {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(symrefTarget, "symrefTarget");
        }
    }
}
