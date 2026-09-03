package pro.deta.orion.git.sync;

import java.util.Objects;
import java.util.Optional;

public record GitBranchPlan(
        String refName,
        Optional<String> localObjectId,
        Optional<String> upstreamObjectId,
        GitBranchAction action,
        Optional<String> mergeBase) {
    public GitBranchPlan {
        requireHead(refName);
        localObjectId = validatedObjectId(localObjectId, "localObjectId");
        upstreamObjectId = validatedObjectId(upstreamObjectId, "upstreamObjectId");
        Objects.requireNonNull(action, "action");
        mergeBase = validatedObjectId(mergeBase, "mergeBase");
        if (action != GitBranchAction.DIVERGED && mergeBase.isPresent()) {
            throw new IllegalArgumentException("mergeBase is valid only for a diverged branch");
        }
    }

    private static Optional<String> validatedObjectId(
            Optional<String> value,
            String name) {
        Optional<String> checked = Objects.requireNonNull(value, name);
        return checked.map(item -> requireObjectId(item, name));
    }

    static String requireObjectId(String value, String name) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.length() != 40) {
            throw invalidObjectId(name);
        }
        for (int index = 0; index < checked.length(); index++) {
            char character = checked.charAt(index);
            if (!(character >= '0' && character <= '9')
                    && !(character >= 'a' && character <= 'f')) {
                throw invalidObjectId(name);
            }
        }
        return checked;
    }

    private static IllegalArgumentException invalidObjectId(String name) {
        return new IllegalArgumentException(
                name + " must contain 40 lowercase hexadecimal digits");
    }

    static String requireHead(String value) {
        Objects.requireNonNull(value, "refName");
        if (!value.startsWith("refs/heads/")
                || value.length() == "refs/heads/".length()) {
            throw new IllegalArgumentException("refName must be under refs/heads/");
        }
        return value;
    }
}
