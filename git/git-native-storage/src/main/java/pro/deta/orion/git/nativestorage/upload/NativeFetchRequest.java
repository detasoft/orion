package pro.deta.orion.git.nativestorage.upload;

import pro.deta.orion.git.nativestorage.GitObjectId;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
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
        Set<String> wantRefs,
        Set<String> packfileUriProtocols,
        Set<GitObjectId> clientShallowCommits,
        boolean deepenRelative,
        long deepenSince,
        Set<String> deepenNotRefs) {

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
                Set.of(),
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
                Set.of(),
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
                Set.of(),
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
                Set.of(),
                Set.of(),
                Set.of(),
                false,
                -1,
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
                Set.of(),
                Set.of(),
                Set.of(),
                false,
                -1,
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
            NativeObjectFilter objectFilter,
            Set<String> wantRefs) {
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
                wantRefs,
                Set.of(),
                Set.of(),
                false,
                -1,
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
            NativeObjectFilter objectFilter,
            Set<String> wantRefs,
            Set<String> packfileUriProtocols) {
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
                wantRefs,
                packfileUriProtocols,
                Set.of(),
                false,
                -1,
                Set.of());
    }

    public NativeFetchRequest {
        Objects.requireNonNull(wants, "wants");
        Objects.requireNonNull(haves, "haves");
        Objects.requireNonNull(objectFilter, "objectFilter");
        Objects.requireNonNull(wantRefs, "wantRefs");
        Objects.requireNonNull(
                packfileUriProtocols,
                "packfileUriProtocols");
        Objects.requireNonNull(
                clientShallowCommits,
                "clientShallowCommits");
        Objects.requireNonNull(deepenNotRefs, "deepenNotRefs");
        if (depth < 0) {
            throw new IllegalArgumentException(
                    "Fetch depth must not be negative");
        }
        if (deepenSince < -1) {
            throw new IllegalArgumentException(
                    "deepenSince must be absent or non-negative");
        }
        for (String wantRef : wantRefs) {
            validateWantRef(wantRef);
        }
        for (String deepenNotRef : deepenNotRefs) {
            validateDeepenNotRef(deepenNotRef);
        }
        Set<String> normalizedPackfileUriProtocols =
                new LinkedHashSet<>();
        for (String protocol : packfileUriProtocols) {
            validatePackfileUriProtocol(protocol);
            normalizedPackfileUriProtocols.add(
                    protocol.toLowerCase(Locale.ROOT));
        }
        wants = Collections.unmodifiableSet(new LinkedHashSet<>(wants));
        haves = Collections.unmodifiableSet(new LinkedHashSet<>(haves));
        wantRefs = Collections.unmodifiableSet(
                new LinkedHashSet<>(wantRefs));
        packfileUriProtocols = Collections.unmodifiableSet(
                normalizedPackfileUriProtocols);
        clientShallowCommits = Collections.unmodifiableSet(
                new LinkedHashSet<>(clientShallowCommits));
        deepenNotRefs = Collections.unmodifiableSet(
                new LinkedHashSet<>(deepenNotRefs));
    }

    public boolean shallow() {
        return depth > 0
                || !clientShallowCommits.isEmpty()
                || deepenRelative
                || deepenSince >= 0
                || !deepenNotRefs.isEmpty();
    }

    private static void validateWantRef(String refName) {
        Objects.requireNonNull(refName, "wantRef");
        if ("HEAD".equals(refName)) {
            return;
        }
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

    private static void validatePackfileUriProtocol(String protocol) {
        Objects.requireNonNull(protocol, "packfileUriProtocol");
        if (!NativePackfileUri.validProtocol(protocol)) {
            throw new IllegalArgumentException(
                    "packfileUriProtocol must be a valid URI scheme");
        }
    }

    private static void validateDeepenNotRef(String value) {
        Objects.requireNonNull(value, "deepenNotRef");
        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    "deepenNotRef must not be empty");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character <= 0x20 || character >= 0x7f) {
                throw new IllegalArgumentException(
                        "deepenNotRef must be printable ASCII");
            }
        }
    }
}
