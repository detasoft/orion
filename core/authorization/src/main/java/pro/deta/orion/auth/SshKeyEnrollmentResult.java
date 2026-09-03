package pro.deta.orion.auth;

/**
 * Result of atomically replacing a root recovery password with SSH public keys.
 */
public sealed interface SshKeyEnrollmentResult permits SshKeyEnrollmentResult.Success, SshKeyEnrollmentResult.Failure {
    record Success() implements SshKeyEnrollmentResult {
    }

    record Failure(String reason, Throwable throwable) implements SshKeyEnrollmentResult {
        public Failure(String reason) {
            this(reason, null);
        }
    }

    static SshKeyEnrollmentResult success() {
        return new Success();
    }

    static SshKeyEnrollmentResult failure(String reason) {
        return new Failure(reason);
    }

    static SshKeyEnrollmentResult failure(String reason, Throwable throwable) {
        return new Failure(reason, throwable);
    }
}
