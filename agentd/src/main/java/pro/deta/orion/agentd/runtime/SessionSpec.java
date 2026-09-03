package pro.deta.orion.agentd.runtime;

import pro.deta.orion.agent.protocol.CommandId;
import pro.deta.orion.agent.protocol.SessionId;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record SessionSpec(
        SessionId sessionId,
        CommandId startCommandId,
        List<String> command,
        WorkspaceReference workspace,
        Map<String, String> environment,
        int columns,
        int rows,
        String terminalType,
        Optional<String> colorTerminal,
        Sandbox sandbox
) {
    public SessionSpec {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(startCommandId, "startCommandId");
        command = List.copyOf(command);
        Objects.requireNonNull(workspace, "workspace");
        environment = Map.copyOf(environment);
        terminalType = Objects.requireNonNull(terminalType, "terminalType");
        colorTerminal = Objects.requireNonNull(colorTerminal, "colorTerminal");
        Objects.requireNonNull(sandbox, "sandbox");
    }

    public record Sandbox(Optional<Path> policy, Unavailable unavailable) {
        public Sandbox {
            policy = Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(unavailable, "unavailable");
        }

        public static Sandbox none() {
            return new Sandbox(Optional.empty(), Unavailable.FAIL);
        }
    }

    public enum Unavailable {
        FAIL,
        RUN_UNSANDBOXED
    }
}
