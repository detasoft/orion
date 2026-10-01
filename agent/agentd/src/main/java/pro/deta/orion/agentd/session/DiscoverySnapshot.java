package pro.deta.orion.agentd.session;

import java.util.Map;
import java.util.Objects;

public record DiscoverySnapshot(Map<String, LocalSession> sessions, Map<String, DiscoveryIssue> issues) {
    public DiscoverySnapshot {
        sessions = Map.copyOf(Objects.requireNonNull(sessions, "sessions"));
        issues = Map.copyOf(Objects.requireNonNull(issues, "issues"));
    }

    public static DiscoverySnapshot empty() {
        return new DiscoverySnapshot(Map.of(), Map.of());
    }
}
