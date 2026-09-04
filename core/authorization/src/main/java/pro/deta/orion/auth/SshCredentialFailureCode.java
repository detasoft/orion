package pro.deta.orion.auth;

public enum SshCredentialFailureCode {
    USER_NOT_FOUND,
    INVALID_KEY,
    INVALID_STORED_KEY,
    MISSING_MATCH,
    AMBIGUOUS_MATCH,
    LAST_KEY_REQUIRES_FORCE,
    ROOT_LOCKED,
    CONCURRENT_UPDATE,
    PERSISTENCE_FAILED
}
