package pro.deta.orion.git.client.repository;

public enum GitRefUpdateOutcome {
    UPDATED,
    STALE,
    MISSING_COMMIT,
    REJECTED
}
