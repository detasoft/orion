package pro.deta.orion.agentd.runtime;

import pro.deta.orion.agent.protocol.SessionId;

import java.nio.file.Path;
import java.util.Objects;

public sealed interface SessionLaunchResult {
    static Failed failed(FailureKind kind, String detail) {
        return new Failed(kind, detail);
    }

    record Started(SessionId sessionId, Path directory) implements SessionLaunchResult {
        public Started {
            Objects.requireNonNull(sessionId, "sessionId");
            directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        }
    }

    record Failed(FailureKind kind, String detail) implements SessionLaunchResult {
        public Failed {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(detail, "detail");
        }
    }

    enum FailureKind {
        INVALID_SPEC,
        INVALID_WORKSPACE,
        UNSUPPORTED_WORKSPACE,
        UNSUPPORTED_ENVIRONMENT,
        SESSION_EXISTS,
        LAUNCH_FAILED,
        INITIALIZATION_FAILED,
        INITIALIZATION_TIMEOUT,
        CLEANUP_FAILED
    }
}
