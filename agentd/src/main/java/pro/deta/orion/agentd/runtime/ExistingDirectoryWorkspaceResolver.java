package pro.deta.orion.agentd.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class ExistingDirectoryWorkspaceResolver implements WorkspaceResolver {
    @Override
    public Resolution resolve(WorkspaceReference reference) {
        Objects.requireNonNull(reference, "reference");
        if (reference instanceof WorkspaceReference.Managed) {
            return new Resolution.Failed(
                    SessionLaunchResult.FailureKind.UNSUPPORTED_WORKSPACE,
                    "managed workspaces are not supported by the native runtime");
        }
        Path directory = ((WorkspaceReference.ExistingDirectory) reference).directory()
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(directory)) {
            return new Resolution.Failed(
                    SessionLaunchResult.FailureKind.INVALID_WORKSPACE,
                    "workspace must be an existing directory: " + directory);
        }
        return new Resolution.Resolved(directory);
    }
}
