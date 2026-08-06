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
        int depth,
        NativeObjectFilter objectFilter,
        Set<String> wantRefs) {

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
                0,
                NativeObjectFilter.NONE,
                Set.of());
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
                0,
                NativeObjectFilter.NONE,
                Set.of());
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
                0,
                NativeObjectFilter.NONE,
                Set.of());
    }

    public NativeFetchRequest(
            Set<GitObjectId> wants,
            Set<GitObjectId> haves,
            boolean done,
            boolean thinPack,
            boolean ofsDelta,
            boolean includeTag,
            boolean waitForDone,
            int depth) {
        this(
                wants,
                haves,
                done,
                thinPack,
                ofsDelta,
                includeTag,
                waitForDone,
                depth,
                NativeObjectFilter.NONE,
                Set.of());
    }

    public NativeFetchRequest(
            Set<GitObjectId> wants,
            Set<GitObjectId> haves,
            boolean done,
            boolean thinPack,
            boolean ofsDelta,
            boolean includeTag,
            boolean waitForDone,
            int depth,
            NativeObjectFilter objectFilter) {
        this(
                wants,
                haves,
                done,
                thinPack,
                ofsDelta,
                includeTag,
                waitForDone,
                depth,
                objectFilter,
                Set.of());
    }

    public NativeFetchRequest {
        Objects.requireNonNull(wants, "wants");
        Objects.requireNonNull(haves, "haves");
        Objects.requireNonNull(objectFilter, "objectFilter");
        Objects.requireNonNull(wantRefs, "wantRefs");
        if (depth < 0) {
            throw new IllegalArgumentException(
                    "Fetch depth must not be negative");
        }
        for (String wantRef : wantRefs) {
            validateWantRef(wantRef);
        }
        wants = Collections.unmodifiableSet(new LinkedHashSet<>(wants));
        haves = Collections.unmodifiableSet(new LinkedHashSet<>(haves));
        wantRefs = Collections.unmodifiableSet(
                new LinkedHashSet<>(wantRefs));
    }

    public boolean shallow() {
        return depth > 0;
    }

    private static void validateWantRef(String refName) {
        Objects.requireNonNull(refName, "wantRef");
        if (!refName.startsWith("refs/")
                || refName.length() == "refs/".length()
                || refName.endsWith("/")
                || refName.contains("//")
                || refName.contains("..")
                || refName.contains("@{")) {
            throw new IllegalArgumentException(
                    "wantRef must be a full Git ref name");
        }
        for (int index = 0; index < refName.length(); index++) {
            char value = refName.charAt(index);
            if (value <= 0x20
                    || value >= 0x7f
                    || value == '~'
                    || value == '^'
                    || value == ':'
                    || value == '?'
                    || value == '*'
                    || value == '['
                    || value == '\\') {
                throw new IllegalArgumentException(
                        "wantRef must be a full Git ref name");
            }
        }
    }
}
