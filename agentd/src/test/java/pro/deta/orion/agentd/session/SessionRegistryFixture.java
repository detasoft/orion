package pro.deta.orion.agentd.session;

import java.util.Map;

public final class SessionRegistryFixture {
    private SessionRegistryFixture() {
    }

    public static void publish(SessionRegistry registry, Map<String, LocalSession> sessions) {
        registry.replace(new DiscoverySnapshot(sessions, Map.of()));
    }
}
