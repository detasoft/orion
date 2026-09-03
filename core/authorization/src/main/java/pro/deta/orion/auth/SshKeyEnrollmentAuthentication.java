package pro.deta.orion.auth;

import java.util.Optional;

/**
 * Result of password authentication performed specifically for SSH key enrollment.
 */
public sealed interface SshKeyEnrollmentAuthentication permits
        SshKeyEnrollmentAuthentication.Success, SshKeyEnrollmentAuthentication.Failure {
    record Success(UserIdentity userIdentity, Optional<String> rootRecoveryGeneration)
            implements SshKeyEnrollmentAuthentication {
        public Success {
            rootRecoveryGeneration = rootRecoveryGeneration == null
                    ? Optional.empty()
                    : rootRecoveryGeneration;
        }
    }

    record Failure(String reason, Throwable throwable) implements SshKeyEnrollmentAuthentication {
        public Failure(String reason) {
            this(reason, null);
        }
    }

    static SshKeyEnrollmentAuthentication success(UserIdentity identity, String rootRecoveryGeneration) {
        return new Success(identity, Optional.ofNullable(rootRecoveryGeneration));
    }

    static SshKeyEnrollmentAuthentication failure(String reason) {
        return new Failure(reason);
    }

    static SshKeyEnrollmentAuthentication failure(String reason, Throwable throwable) {
        return new Failure(reason, throwable);
    }
}
