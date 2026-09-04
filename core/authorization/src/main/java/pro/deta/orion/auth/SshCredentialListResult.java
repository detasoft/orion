package pro.deta.orion.auth;

import java.util.List;
import java.util.Objects;

public sealed interface SshCredentialListResult
        permits SshCredentialListResult.Success, SshCredentialListResult.Failure {
    record Success(List<SshCredential> credentials) implements SshCredentialListResult {
        public Success {
            Objects.requireNonNull(credentials, "credentials");
            credentials = List.copyOf(credentials);
        }
    }

    record Failure(
            SshCredentialFailureCode code,
            String reason,
            Throwable throwable) implements SshCredentialListResult {
        public Failure {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(reason, "reason");
        }

        public Failure(SshCredentialFailureCode code, String reason) {
            this(code, reason, null);
        }
    }

    static SshCredentialListResult success(List<SshCredential> credentials) {
        return new Success(credentials);
    }

    static SshCredentialListResult failure(SshCredentialFailureCode code, String reason) {
        return new Failure(code, reason);
    }

    static SshCredentialListResult failure(
            SshCredentialFailureCode code,
            String reason,
            Throwable throwable) {
        return new Failure(code, reason, throwable);
    }
}
