package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentLabel;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.SessionDescriptor;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agent.server.AgentSessionServer;
import pro.deta.orion.agent.server.registry.FileSystemSessionRegistry;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class SessionListRouteTest {
    @TempDir
    Path root;

    @Test
    void listsDurableReportedSessionsAcrossAgentsInStableOrder() throws Exception {
        AgentLabel first = new AgentLabel("agent-a");
        AgentLabel second = new AgentLabel("agent-b");
        SessionId session = new SessionId("session-a");
        try (FileSystemSessionRegistry registry = new FileSystemSessionRegistry(root.resolve("sessions"))) {
            registry.reserveStart(first, new SessionId("session-z"));
            registry.reconcile(second, List.of(new SessionDescriptor(session, AgentMessage.SessionState.RUNNING,
                    Optional.empty(), Optional.empty(), "running")));
            registry.reconcile(second, List.of(new SessionDescriptor(session, AgentMessage.SessionState.EXITED,
                    Optional.empty(), Optional.empty(), "0")));
        }
        AgentSessionServer server = new AgentSessionServer(root);
        server.onStart();
        try {
            OrionHttpResponse response = new SessionListRoute(server).doGet(null);
            assertThat(response.status()).isEqualTo(200);
            assertThat(response.headers()).containsEntry("Cache-Control", "no-store");
            assertThat(response.body()).isEqualTo(Map.of("sessions", List.of(
                    Map.of("id", "session-a", "agent", "agent-b", "state", "EXITED"),
                    Map.of("id", "session-z", "agent", "agent-a", "state", "STARTING"))));
        } finally {
            server.onStop();
        }
    }

    @Test
    void distinguishesAnEmptyRegistryFromAnUnavailableServer() throws Exception {
        AgentSessionServer server = new AgentSessionServer(root);
        SessionListRoute route = new SessionListRoute(server);
        assertThat(route.doGet(null).status()).isEqualTo(503);
        server.onStart();
        try {
            assertThat(route.doGet(null).body()).isEqualTo(Map.of("sessions", List.of()));
        } finally {
            server.onStop();
        }
        assertThat(route.doGet(null).status()).isEqualTo(503);
    }

    @Test
    void rejectsAnonymousAndNonAdministratorRequestsBeforeReadingSessions() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        OrionHttpRouteServlet servlet = new OrionHttpRouteServlet(
                new OrionHttpRouteRegistry(Set.of(new SessionListRoute(null))), new OrionHttpResponseWriter(mapper));
        for (SecurityContext context : List.of(SecurityContext.createContext(),
                SecurityContext.createContext().withUserIdentity(new InternalUserImpl("reader", List.of())))) {
            AtomicInteger status = new AtomicInteger();
            HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
                    HttpServletResponse.class.getClassLoader(), new Class<?>[]{HttpServletResponse.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("sendError")) status.set((int) args[0]);
                        else throw new UnsupportedOperationException(method.toString());
                        return null;
                    });
            servlet.service(AcmeAdministrationTest.httpRequest("GET", "/api/admin/sessions", "", context), response);
            assertThat(status.get()).isEqualTo(403);
        }
    }
}
