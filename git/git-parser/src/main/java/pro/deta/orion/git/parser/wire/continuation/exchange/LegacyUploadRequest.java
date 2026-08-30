package pro.deta.orion.git.parser.wire.continuation.exchange;

import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public record LegacyUploadRequest(
        InitialRequestData initialRequest,
        Set<GitObjectId> wants,
        Set<String> capabilities,
        GitV1Advertisement serverAdvertisement) {

    public LegacyUploadRequest {
        Objects.requireNonNull(initialRequest, "initialRequest");
        Objects.requireNonNull(wants, "wants");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(serverAdvertisement, "serverAdvertisement");
        wants = Collections.unmodifiableSet(new LinkedHashSet<>(wants));
        capabilities = Collections.unmodifiableSet(
                new LinkedHashSet<>(capabilities));
        if (wants.isEmpty()) {
            throw new IllegalArgumentException(
                    "Legacy upload request must contain a want");
        }
    }
}
