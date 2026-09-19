package pro.deta.orion.git.parser.wire.exchange;

import pro.deta.orion.git.nativestorage.GitObjectId;
import pro.deta.orion.git.parser.wire.advertisement.GitV1Advertisement;
import pro.deta.orion.git.parser.v2.capability.GitCapability;
import pro.deta.orion.git.parser.v2.capability.GitCapabilities;
import pro.deta.orion.git.parser.v2.capability.GitCapabilityValue;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public record LegacyUploadRequest(
        InitialRequestData initialRequest,
        Set<GitObjectId> wants,
        Set<GitObjectId> clientShallowCommits,
        int depth,
        boolean deepenRelative,
        long deepenSince,
        Set<String> deepenNotRefs,
        GitCapabilities capabilities,
        GitV1Advertisement serverAdvertisement) {

    public LegacyUploadRequest {
        Objects.requireNonNull(initialRequest, "initialRequest");
        Objects.requireNonNull(wants, "wants");
        Objects.requireNonNull(clientShallowCommits, "clientShallowCommits");
        Objects.requireNonNull(deepenNotRefs, "deepenNotRefs");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(serverAdvertisement, "serverAdvertisement");
        wants = Collections.unmodifiableSet(new LinkedHashSet<>(wants));
        clientShallowCommits = Collections.unmodifiableSet(
                new LinkedHashSet<>(clientShallowCommits));
        deepenNotRefs = Collections.unmodifiableSet(
                new LinkedHashSet<>(deepenNotRefs));
        capabilities = new GitCapabilities(capabilities);
        if (wants.isEmpty()) {
            throw new IllegalArgumentException(
                    "Legacy upload request must contain a want");
        }
        if (depth < 0 || deepenSince < -1) {
            throw new IllegalArgumentException(
                    "Legacy upload deepening values are invalid");
        }
    }

    public LegacyUploadRequest(
            InitialRequestData initialRequest,
            Set<GitObjectId> wants,
            GitCapabilities capabilities,
            GitV1Advertisement serverAdvertisement) {
        this(initialRequest, wants, Set.of(), 0, false, -1, Set.of(),
                capabilities, serverAdvertisement);
    }

    public boolean shallow() {
        return depth > 0
                || !clientShallowCommits.isEmpty()
                || deepenRelative
                || deepenSince >= 0
                || !deepenNotRefs.isEmpty();
    }

    public boolean negotiated(GitCapability capability) {
        Objects.requireNonNull(capability, "capability");
        return capabilities.contains(GitCapabilityValue.value(capability))
                && serverAdvertisement.capabilities().has(capability);
    }

    @Override
    public GitCapabilities capabilities() {
        return new GitCapabilities(capabilities);
    }
}
