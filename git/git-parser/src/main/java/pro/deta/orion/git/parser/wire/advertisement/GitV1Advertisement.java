package pro.deta.orion.git.parser.wire.advertisement;

import pro.deta.orion.git.parser.v2.capability.GitCapabilities;

import java.util.List;
import java.util.Objects;

public record GitV1Advertisement(
        GitCapabilities capabilities,
        List<GitAdvertisedRef> refs) {

    public GitV1Advertisement {
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(refs, "refs");
        capabilities = new GitCapabilities(capabilities);
        refs = List.copyOf(refs);
        if (refs.isEmpty()) {
            throw new IllegalArgumentException(
                    "Legacy advertisement must contain at least one ref");
        }
    }

    @Override
    public GitCapabilities capabilities() {
        return new GitCapabilities(capabilities);
    }
}
