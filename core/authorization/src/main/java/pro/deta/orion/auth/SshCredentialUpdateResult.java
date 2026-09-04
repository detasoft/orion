package pro.deta.orion.auth;

import java.util.List;
import java.util.Objects;

public sealed interface SshCredentialUpdateResult
        permits SshCredentialUpdateResult.Success, SshCredentialUpdateResult.Failure {
    record Success(List<SshCredential> credentials, boolean changed) implements SshCredentialUpdateResult {
        public Success {
            Objects.requireNonNull(credentials, "credentials");
            credentials = List.copyOf(credentials);
        }
    }

    record Failure(
            SshCredentialFailureCode code,
            String reason,
            List<String> candidates,
            Throwable throwable) implements SshCredentialUpdateResult {
        public Failure {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(candidates, "candidates");
            candidates = List.copyOf(candidates);
        }

        public Failure(SshCredentialFailureCode code, String reason) {
            this(code, reason, List.of(), null);
        }
    }

    static SshCredentialUpdateResult success(List<SshCredential> credentials, boolean changed) {
        return new Success(credentials, changed);
    }

    static SshCredentialUpdateResult failure(SshCredentialFailureCode code, String reason) {
        return new Failure(code, reason);
    }

    static SshCredentialUpdateResult failure(
            SshCredentialFailureCode code,
            String reason,
            List<String> candidates,
            Throwable throwable) {
        return new Failure(code, reason, candidates, throwable);
    }
}
