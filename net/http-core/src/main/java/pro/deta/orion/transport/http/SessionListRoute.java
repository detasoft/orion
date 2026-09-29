package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.agent.server.AgentSessionServer;
import pro.deta.orion.agent.server.registry.SessionRecord;
import pro.deta.orion.agent.server.registry.SessionRegistryException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Method.GET;

/** Lists durable terminal sessions using the same administrator access as terminal events and commands. */
public final class SessionListRoute extends BaseAdminRoute {
    private final AgentSessionServer server;

    @Inject
    public SessionListRoute(AgentSessionServer server) {
        super(OrionAdminPaths.SESSIONS, GET);
        this.server = server;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest request) {
        try {
            List<Map<String, String>> sessions = new ArrayList<>();
            for (SessionRecord record : server.sessions()) {
                sessions.add(Map.of("id", record.descriptor().sessionId().value(),
                        "agent", record.agentLabel().value(), "state", record.descriptor().state().name()));
            }
            return OrionHttpResponse.ok(Map.of("sessions", sessions)).withHeader("Cache-Control", "no-store");
        } catch (SessionRegistryException | IllegalStateException failure) {
            return OrionHttpResponse.text(503, "Session list is unavailable");
        }
    }
}
