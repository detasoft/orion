package pro.deta.orion.agentd.session;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;

public record HostStatus(
        State state,
        boolean hostLive,
        boolean childLive,
        boolean sandboxed,
        int columns,
        int rows,
        long hostPid,
        OptionalLong childPid,
        OptionalInt exitCode,
        OptionalInt exitSignal,
        int journalFormatVersion,
        int controlProtocolVersion
) {
    public HostStatus {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(childPid, "childPid");
        Objects.requireNonNull(exitCode, "exitCode");
        Objects.requireNonNull(exitSignal, "exitSignal");
    }

    public enum State {
        STARTING,
        RUNNING,
        EXITED,
        FAILED
    }
}
