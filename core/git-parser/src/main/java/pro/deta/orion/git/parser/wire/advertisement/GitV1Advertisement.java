package pro.deta.orion.git.parser.wire.advertisement;

import pro.deta.orion.git.parser.wire.capability.GitCapabilitySet;

import java.util.List;
import java.util.Objects;

public record GitV1Advertisement(
        GitCapabilitySet capabilities,
        List<GitAdvertisedRef> refs,
        boolean emptyRepository) {

    public GitV1Advertisement {
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(refs, "refs");
        refs = List.copyOf(refs);
        if (emptyRepository && !refs.isEmpty()) {
            throw new IllegalArgumentException("Empty repository advertisement must not contain refs");
        }
    }
}
