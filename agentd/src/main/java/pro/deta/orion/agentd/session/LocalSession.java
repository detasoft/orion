package pro.deta.orion.agentd.session;

import java.nio.file.Path;
import java.util.Objects;

import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.SessionDescriptor;
import pro.deta.orion.agent.protocol.SessionId;

public record LocalSession(
        Path directory,
        SessionManifest manifest,
        HostObservation host,
        JournalObservation journal,
        LocalSessionState state
) {
    public LocalSession {
        directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(host, "host");
        Objects.requireNonNull(journal, "journal");
        Objects.requireNonNull(state, "state");
    }

    public SessionDescriptor descriptor() {
        AgentMessage.SessionState reportedState;
        String detail;
        if (state == LocalSessionState.DEGRADED || !journal.readable()) {
            reportedState = AgentMessage.SessionState.DEGRADED;
            detail = "session discovery degraded";
        } else if (state == LocalSessionState.LOST) {
            reportedState = AgentMessage.SessionState.LOST;
            detail = "session host unreachable";
        } else if (host.childState() == ChildState.EXITED) {
            reportedState = AgentMessage.SessionState.EXITED;
            detail = "process exited";
        } else if (host.childState() == ChildState.LIVE) {
            reportedState = AgentMessage.SessionState.RUNNING;
            detail = "running";
        } else {
            reportedState = AgentMessage.SessionState.DEGRADED;
            detail = "child state unavailable";
        }
        return new SessionDescriptor(
                new SessionId(manifest.sessionId()),
                reportedState,
                journal.firstAvailableEventId(),
                journal.lastAvailableEventId(),
                detail);
    }
}
