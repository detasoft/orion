package pro.deta.orion.git.nativestorage.service;

import java.util.Objects;
import java.util.Set;

public record ReceivePackPolicy(
        boolean allowBranchDeletes,
        boolean allowTagCreates,
        boolean allowTagUpdates,
        boolean allowTagDeletes,
        boolean allowNonFastForwardUpdates,
        Set<String> protectedRefs) {

    public ReceivePackPolicy {
        Objects.requireNonNull(protectedRefs, "protectedRefs");
        protectedRefs = Set.copyOf(protectedRefs);
    }

    public static ReceivePackPolicy conservative() {
        return new ReceivePackPolicy(false, true, false, false, false, Set.of());
    }

    public ReceivePackPolicy withBranchDeletes(boolean allowed) {
        return new ReceivePackPolicy(
                allowed,
                allowTagCreates,
                allowTagUpdates,
                allowTagDeletes,
                allowNonFastForwardUpdates,
                protectedRefs);
    }

    public ReceivePackPolicy withNonFastForwardUpdates(boolean allowed) {
        return new ReceivePackPolicy(
                allowBranchDeletes,
                allowTagCreates,
                allowTagUpdates,
                allowTagDeletes,
                allowed,
                protectedRefs);
    }

    public ReceivePackPolicy withProtectedRefs(Set<String> refs) {
        return new ReceivePackPolicy(
                allowBranchDeletes,
                allowTagCreates,
                allowTagUpdates,
                allowTagDeletes,
                allowNonFastForwardUpdates,
                refs);
    }

    public boolean isProtected(String refName) {
        Objects.requireNonNull(refName, "refName");
        for (String protectedRef : protectedRefs) {
            if (protectedRef.endsWith("/*")) {
                String prefix = protectedRef.substring(0, protectedRef.length() - 1);
                if (refName.startsWith(prefix)) {
                    return true;
                }
            } else if (protectedRef.equals(refName)) {
                return true;
            }
        }
        return false;
    }
}
