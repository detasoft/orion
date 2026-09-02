package pro.deta.orion.git.workflow;

import java.util.Set;

public enum GitCapability {
    INITIALIZE,
    CLONE,
    COMMIT,
    PUSH,
    FETCH,
    FAST_FORWARD_PULL;

    public static Set<GitCapability> all() {
        return Set.of(values());
    }
}
