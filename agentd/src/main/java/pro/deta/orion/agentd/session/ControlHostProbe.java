package pro.deta.orion.agentd.session;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Uses native STATUS as liveness evidence. The manifest PID is only a correlation
 * coordinate because protocol v1 has no host-incarnation identifier.
 */
public final class ControlHostProbe implements HostProbe {
    private final StatusRequester requester;
    private final DeadlineStatusRequester deadlineRequester;

    public ControlHostProbe(SessionControlClient client) {
        this(client::send, client::send);
    }

    ControlHostProbe(StatusRequester requester) {
        this(requester, (endpoint, command, deadline) -> requester.status(endpoint, command));
    }

    ControlHostProbe(StatusRequester requester, DeadlineStatusRequester deadlineRequester) {
        this.requester = Objects.requireNonNull(requester, "requester");
        this.deadlineRequester = Objects.requireNonNull(deadlineRequester, "deadlineRequester");
    }

    @Override
    public HostObservation probe(Path sessionDirectory, SessionManifest manifest) throws IOException {
        Objects.requireNonNull(sessionDirectory, "sessionDirectory");
        Objects.requireNonNull(manifest, "manifest");
        return observation(manifest, requester.status(manifest.control(), new ControlCommand.Status()));
    }

    public HostObservation probe(
            Path sessionDirectory,
            SessionManifest manifest,
            OperationDeadline deadline
    ) throws IOException {
        Objects.requireNonNull(sessionDirectory, "sessionDirectory");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(deadline, "deadline");
        return observation(
                manifest,
                deadlineRequester.status(manifest.control(), new ControlCommand.Status(), deadline));
    }

    private static HostObservation observation(SessionManifest manifest, ControlResult result)
            throws IOException {
        if (result instanceof ControlResult.Status response) {
            return observation(manifest, response.status());
        }
        if (result instanceof ControlResult.Failed failed && isUnreachable(failed.kind())) {
            return HostObservation.unreachable();
        }
        String detail = switch (result) {
            case ControlResult.Failed failed -> failed.detail();
            case ControlResult.Rejected rejected -> rejected.detail();
            default -> "native host returned a non-STATUS response";
        };
        throw new IOException("Native session host STATUS failed: " + detail);
    }

    private static HostObservation observation(SessionManifest manifest, HostStatus status) throws IOException {
        if (status.journalFormatVersion() != manifest.journalFormatVersion()
                || status.controlProtocolVersion() != manifest.controlProtocolVersion()) {
            throw new IOException("Native session host STATUS version does not match its manifest");
        }
        if (!status.hostLive() || status.hostPid() != manifest.hostPid()) {
            return HostObservation.unreachable();
        }
        ChildState childState;
        if (status.childLive()) {
            childState = ChildState.LIVE;
        } else if (status.childPid().isPresent()) {
            childState = ChildState.EXITED;
        } else {
            childState = ChildState.UNKNOWN;
        }
        return HostObservation.live(childState);
    }

    private static boolean isUnreachable(ControlResult.FailureKind kind) {
        return kind == ControlResult.FailureKind.CONNECTION
                || kind == ControlResult.FailureKind.TIMEOUT
                || kind == ControlResult.FailureKind.AMBIGUOUS_DELIVERY;
    }

    @FunctionalInterface
    interface StatusRequester {
        ControlResult status(ControlEndpoint endpoint, ControlCommand command);
    }

    @FunctionalInterface
    interface DeadlineStatusRequester {
        ControlResult status(
                ControlEndpoint endpoint,
                ControlCommand command,
                OperationDeadline deadline);
    }
}
