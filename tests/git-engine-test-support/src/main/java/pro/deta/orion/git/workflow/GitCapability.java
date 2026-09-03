package pro.deta.orion.git.workflow;

import java.util.Set;

public enum GitCapability {
    INITIALIZE,
    CLONE,
    COMMIT,
    PUSH,
    FETCH,
    FAST_FORWARD_PULL,
    CREATE_MISSING_REPOSITORY_ON_PUSH;

    public static Set<GitCapability> all() {
        return Set.of(values());
    }

    public static Set<GitCapability> symmetric() {
        return Set.of(INITIALIZE, CLONE, COMMIT, PUSH, FETCH, FAST_FORWARD_PULL);
    }
}
