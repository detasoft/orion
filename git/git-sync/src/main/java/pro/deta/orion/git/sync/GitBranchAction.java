package pro.deta.orion.git.sync;

public enum GitBranchAction {
    CREATE_LOCAL,
    FAST_FORWARD_LOCAL,
    PUSH_UPSTREAM,
    NO_OP,
    DIVERGED
}
