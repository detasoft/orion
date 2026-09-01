package pro.deta.orion.git.nativestorage.upload;

import pro.deta.orion.git.nativestorage.GitObjectId;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

public record NativeFetchRequest(
        Set<GitObjectId> wants,
        Set<GitObjectId> haves,
        boolean done,
        Set<String> wantRefs,
        NativeFetchOptions options) {

    public NativeFetchRequest {
        Objects.requireNonNull(wants, "wants");
        Objects.requireNonNull(haves, "haves");
        Objects.requireNonNull(wantRefs, "wantRefs");
        Objects.requireNonNull(options, "options");
        for (String wantRef : wantRefs) {
            validateWantRef(wantRef);
        }
        wants = Collections.unmodifiableSet(new LinkedHashSet<>(wants));
        haves = Collections.unmodifiableSet(new LinkedHashSet<>(haves));
        wantRefs = Collections.unmodifiableSet(
                new LinkedHashSet<>(wantRefs));
    }

    public boolean thinPack() {
        return options.thinPack();
    }

    public boolean ofsDelta() {
        return options.ofsDelta();
    }

    public boolean includeTag() {
        return options.includeTag();
    }

    public boolean waitForDone() {
        return options.waitForDone();
    }

    public int depth() {
        return options.depth();
    }

    public NativeObjectFilter objectFilter() {
        return options.objectFilter();
    }

    public Set<String> packfileUriProtocols() {
        return options.packfileUriProtocols();
    }

    public Set<GitObjectId> clientShallowCommits() {
        return options.clientShallowCommits();
    }

    public boolean deepenRelative() {
        return options.deepenRelative();
    }

    public long deepenSince() {
        return options.deepenSince();
    }

    public Set<String> deepenNotRefs() {
        return options.deepenNotRefs();
    }

    public boolean shallow() {
        return options.shallow();
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

}
