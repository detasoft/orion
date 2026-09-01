package pro.deta.orion.git.parser.wire.exchange;

import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.nativestorage.upload.NativeFetchOptions;
import pro.deta.orion.git.nativestorage.upload.NativeFetchRequest;
import pro.deta.orion.git.nativestorage.upload.NativeObjectFilter;
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
                Set.of(),
                new NativeFetchOptions(
                        negotiated(GitCapability.THIN_PACK),
                        negotiated(GitCapability.OFS_DELTA),
                        negotiated(GitCapability.INCLUDE_TAG),
                        false,
                        request.depth(),
                        NativeObjectFilter.NONE,
                        Set.of(),
                        request.clientShallowCommits(),
                        request.deepenRelative(),
                        request.deepenSince(),
                        request.deepenNotRefs()));
    }

    public boolean negotiated(GitCapability capability) {
        Objects.requireNonNull(capability, "capability");
        return request.negotiated(capability);
    }
}
