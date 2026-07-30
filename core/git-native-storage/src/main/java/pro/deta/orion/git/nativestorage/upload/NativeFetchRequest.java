package pro.deta.orion.git.nativestorage.upload;

import pro.deta.orion.git.common.GitObjectId;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public record NativeFetchRequest(
        Set<GitObjectId> wants,
        Set<GitObjectId> haves,
        boolean done,
        boolean thinPack,
        boolean ofsDelta,
        boolean includeTag) {

    public NativeFetchRequest(
            Set<GitObjectId> wants,
            Set<GitObjectId> haves,
            boolean done,
            boolean thinPack,
            boolean ofsDelta) {
        this(
                wants,
                haves,
                done,
                thinPack,
                ofsDelta,
                false);
    }

    public NativeFetchRequest {
        Objects.requireNonNull(wants, "wants");
        Objects.requireNonNull(haves, "haves");
        wants = Collections.unmodifiableSet(new LinkedHashSet<>(wants));
        haves = Collections.unmodifiableSet(new LinkedHashSet<>(haves));
    }
}
