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
        boolean includeTag,
        boolean waitForDone,
        int depth) {

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
                false,
                false,
                0);
    }

    public NativeFetchRequest(
            Set<GitObjectId> wants,
            Set<GitObjectId> haves,
            boolean done,
            boolean thinPack,
            boolean ofsDelta,
            boolean includeTag) {
        this(
                wants,
                haves,
                done,
                thinPack,
                ofsDelta,
                includeTag,
                false,
                0);
    }

    public NativeFetchRequest(
            Set<GitObjectId> wants,
            Set<GitObjectId> haves,
            boolean done,
            boolean thinPack,
            boolean ofsDelta,
            boolean includeTag,
            boolean waitForDone) {
        this(
                wants,
                haves,
                done,
                thinPack,
                ofsDelta,
                includeTag,
                waitForDone,
                0);
    }

    public NativeFetchRequest {
        Objects.requireNonNull(wants, "wants");
        Objects.requireNonNull(haves, "haves");
        if (depth < 0) {
            throw new IllegalArgumentException(
                    "Fetch depth must not be negative");
        }
        wants = Collections.unmodifiableSet(new LinkedHashSet<>(wants));
        haves = Collections.unmodifiableSet(new LinkedHashSet<>(haves));
    }

    public boolean shallow() {
        return depth > 0;
    }
}
