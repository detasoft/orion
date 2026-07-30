package pro.deta.orion.git.parser.wire.continuation.exchange;

import pro.deta.orion.git.common.GitObjectId;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public record LegacyUploadNegotiation(
        LegacyUploadRequest request,
        Set<GitObjectId> haves) {

    public LegacyUploadNegotiation {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(haves, "haves");
        haves = Collections.unmodifiableSet(new LinkedHashSet<>(haves));
    }
}
