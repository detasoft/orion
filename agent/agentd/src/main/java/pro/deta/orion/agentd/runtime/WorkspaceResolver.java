package pro.deta.orion.agentd.runtime;

import java.nio.file.Path;
import java.util.Objects;

@FunctionalInterface
public interface WorkspaceResolver {
    Resolution resolve(WorkspaceReference reference);

    sealed interface Resolution {
        record Resolved(Path workingDirectory) implements Resolution {
            public Resolved {
                workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory")
                        .toAbsolutePath().normalize();
            }
        }

        record Failed(SessionLaunchResult.FailureKind kind, String detail) implements Resolution {
            public Failed {
                Objects.requireNonNull(kind, "kind");
                Objects.requireNonNull(detail, "detail");
            }
        }
    }
}
