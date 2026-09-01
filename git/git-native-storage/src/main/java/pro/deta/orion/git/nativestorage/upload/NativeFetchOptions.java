package pro.deta.orion.git.nativestorage.upload;

import pro.deta.orion.git.nativestorage.GitObjectId;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record NativeFetchOptions(
        boolean thinPack,
        boolean ofsDelta,
        boolean includeTag,
        boolean waitForDone,
        int depth,
        NativeObjectFilter objectFilter,
        Set<String> packfileUriProtocols,
        Set<GitObjectId> clientShallowCommits,
        boolean deepenRelative,
        long deepenSince,
        Set<String> deepenNotRefs) {

    public static final NativeFetchOptions DEFAULT = new NativeFetchOptions(
            false,
            false,
            false,
            false,
            0,
            NativeObjectFilter.NONE,
            Set.of(),
            Set.of(),
            false,
            -1,
            Set.of());

    public NativeFetchOptions(
            boolean thinPack,
            boolean ofsDelta,
            boolean includeTag,
            boolean waitForDone,
            NativeObjectFilter objectFilter,
            Set<String> packfileUriProtocols) {
        this(
                thinPack,
                ofsDelta,
                includeTag,
                waitForDone,
                0,
                objectFilter,
                packfileUriProtocols,
                Set.of(),
                false,
                -1,
                Set.of());
    }

    public NativeFetchOptions {
        Objects.requireNonNull(objectFilter, "objectFilter");
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
        Set<String> normalizedPackfileUriProtocols =
                new LinkedHashSet<>();
        for (String protocol : packfileUriProtocols) {
            validatePackfileUriProtocol(protocol);
            normalizedPackfileUriProtocols.add(
                    protocol.toLowerCase(Locale.ROOT));
        }
        for (String deepenNotRef : deepenNotRefs) {
            validateDeepenNotRef(deepenNotRef);
        }
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
