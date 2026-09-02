package pro.deta.orion.agentd.session;

import java.nio.file.Path;
import java.util.Objects;

public record DiscoveryIssue(Path directory, Kind kind, String detail) {
    public DiscoveryIssue {
        directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        Objects.requireNonNull(kind, "kind");
        detail = Objects.requireNonNull(detail, "detail");
    }

    public enum Kind {
        INCOMPLETE,
        DEGRADED
    }
}
