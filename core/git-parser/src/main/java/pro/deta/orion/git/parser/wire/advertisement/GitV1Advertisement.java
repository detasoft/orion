package pro.deta.orion.git.parser.wire.advertisement;

import pro.deta.orion.git.parser.wire.capability.GitCapability;

import java.util.List;
import java.util.Objects;

public record GitV1Advertisement(
        List<GitCapability> capabilities,
        List<GitAdvertisedRef> refs) {

    public GitV1Advertisement {
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(refs, "refs");
        capabilities = List.copyOf(capabilities);
        refs = List.copyOf(refs);
        if (refs.isEmpty()) {
            throw new IllegalArgumentException(
                    "Legacy advertisement must contain at least one ref");
        }
    }
}
