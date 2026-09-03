package pro.deta.orion.agentd.runtime;

import pro.deta.orion.agent.protocol.WorkspaceId;

import java.nio.file.Path;
import java.util.Objects;

public sealed interface WorkspaceReference {
    record ExistingDirectory(Path directory) implements WorkspaceReference {
        public ExistingDirectory {
            Objects.requireNonNull(directory, "directory");
        }
    }

    record Managed(WorkspaceId workspaceId, Path workingDirectory) implements WorkspaceReference {
        public Managed {
            Objects.requireNonNull(workspaceId, "workspaceId");
            Objects.requireNonNull(workingDirectory, "workingDirectory");
        }
    }
}
