package pro.deta.orion.git.parser.wire.protocolv2.response;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record GitLsRef(
        Optional<String> objectId,
        String name,
        Optional<String> symrefTarget,
        Optional<String> peeledObjectId,
        List<GitLsRefAttribute> unknownAttributes,
        boolean unborn) {

    public GitLsRef {
        Objects.requireNonNull(objectId, "objectId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(symrefTarget, "symrefTarget");
        Objects.requireNonNull(peeledObjectId, "peeledObjectId");
        Objects.requireNonNull(unknownAttributes, "unknownAttributes");
        unknownAttributes = List.copyOf(unknownAttributes);
        if (unborn == objectId.isPresent()) {
            throw new IllegalArgumentException("Ls-refs row must contain either an object id or unborn");
        }
        if (unborn && peeledObjectId.isPresent()) {
            throw new IllegalArgumentException("Unborn ls-refs row cannot be peeled");
        }
    }
}
