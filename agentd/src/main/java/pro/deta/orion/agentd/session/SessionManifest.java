package pro.deta.orion.agentd.session;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

public record SessionManifest(
        int metadataVersion,
        int journalFormatVersion,
        int controlProtocolVersion,
        String sessionId,
        long createdAtEpochMillis,
        long sessionStartEpochMillis,
        List<String> command,
        String workingDirectory,
        long hostPid,
        OptionalLong childPid,
        int initialColumns,
        int initialRows,
        int currentColumns,
        int currentRows,
        String terminalType,
        Sandbox sandbox,
        ControlEndpoint control
) {
    public SessionManifest {
        sessionId = Objects.requireNonNull(sessionId, "sessionId");
        command = List.copyOf(command);
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        childPid = Objects.requireNonNull(childPid, "childPid");
        terminalType = Objects.requireNonNull(terminalType, "terminalType");
        Objects.requireNonNull(sandbox, "sandbox");
        Objects.requireNonNull(control, "control");
    }

    public record Sandbox(
            boolean requested,
            String enforcement,
            String unavailablePolicy,
            List<String> readWritePaths,
            List<String> readOnlyPaths
    ) {
        public Sandbox {
            enforcement = Objects.requireNonNull(enforcement, "enforcement");
            unavailablePolicy = Objects.requireNonNull(unavailablePolicy, "unavailablePolicy");
            readWritePaths = List.copyOf(readWritePaths);
            readOnlyPaths = List.copyOf(readOnlyPaths);
        }
    }
}
