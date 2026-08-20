package pro.deta.orion.git.parser.wire.continuation.exchange;

import pro.deta.orion.git.common.GitObjectId;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.parser.wire.capability.GitCapability;

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

    public NativeFetchRequest nativeFetchRequest() {
        return new NativeFetchRequest(
                request.wants(),
                haves,
                true,
                negotiated(GitCapability.THIN_PACK),
                negotiated(GitCapability.OFS_DELTA),
                negotiated(GitCapability.INCLUDE_TAG));
    }

    public boolean negotiated(GitCapability capability) {
        Objects.requireNonNull(capability, "capability");
        if (!request.capabilities().contains(capability.name())) {
            return false;
        }
        for (GitCapability advertised
                : request.serverAdvertisement().capabilities()) {
            if (advertised.name().equals(capability.name())) {
                return true;
            }
        }
        return false;
    }
}
