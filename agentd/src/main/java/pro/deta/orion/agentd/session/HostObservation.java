package pro.deta.orion.agentd.session;

import java.util.Objects;

public record HostObservation(Status status, ChildState childState) {
    public HostObservation {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(childState, "childState");
    }

    public static HostObservation live(ChildState childState) {
        return new HostObservation(Status.LIVE, childState);
    }

    public static HostObservation unreachable() {
        return new HostObservation(Status.UNREACHABLE, ChildState.UNKNOWN);
    }

    public enum Status {
        LIVE,
        UNREACHABLE
    }
}
