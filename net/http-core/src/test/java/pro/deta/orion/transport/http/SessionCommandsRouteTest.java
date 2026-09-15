package pro.deta.orion.transport.http;

import pro.deta.orion.agent.server.auth.AuthenticatedConnectionContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.agent.protocol.AgentAuthentication;
import pro.deta.orion.agent.protocol.AgentLabel;
import pro.deta.orion.agent.protocol.AgentInstanceId;
import pro.deta.orion.agent.protocol.AgentMessage;
import pro.deta.orion.agent.protocol.AgentProtocolCodec;
import pro.deta.orion.agent.protocol.AgentProtocolLimits;
import pro.deta.orion.agent.protocol.AgentProtocolVersion;
import pro.deta.orion.agent.protocol.CommandId;
import pro.deta.orion.agent.protocol.EventId;
import pro.deta.orion.agent.protocol.JournalFormatVersion;
import pro.deta.orion.agent.protocol.MachineInfo;
import pro.deta.orion.agent.protocol.ProtocolBytes;
import pro.deta.orion.agent.protocol.SessionCommandOutcome;
import pro.deta.orion.agent.protocol.SessionCommandSource;
import pro.deta.orion.agent.protocol.SessionDescriptor;
import pro.deta.orion.agent.protocol.SessionEventCodec;
import pro.deta.orion.agent.protocol.SessionEventPayload;
import pro.deta.orion.agent.protocol.SessionId;
import pro.deta.orion.agent.server.AgentSessionServer;
import pro.deta.orion.agent.server.connection.AgentControlHandler;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class SessionCommandsRouteTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SessionId SESSION = new SessionId("terminal-session");
    private static final AgentLabel AGENT = new AgentLabel("terminal-agent");
    private static final String INPUT = """
            {"commandId":"input-1","operation":"input","bytes":"AP8="}
            """;

    @TempDir
    Path root;

    @Test
    void routesBinaryInputAndResizeInOrderAndReadsDurableOutcome() throws Exception {
        try (Peer peer = new Peer(root)) {
            HttpResponse<String> response = peer.post(INPUT);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json(response).path("phase").asText()).isEqualTo("SENT");
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            AgentMessage.Input input = (AgentMessage.Input) peer.connection.sent.getLast();
            assertThat(input.sessionId()).isEqualTo(SESSION);
            assertThat(input.bytes().toByteArray()).containsExactly((byte) 0, (byte) 255);
            assertThat(input.operationSequence()).isEqualTo(1);

            assertThat(peer.post("""
                    {"commandId":"resize-1","operation":"resize","columns":120,"rows":40}
                    """).statusCode()).isEqualTo(200);
            AgentMessage.Resize resize = (AgentMessage.Resize) peer.connection.sent.getLast();
            assertThat(resize.columns()).isEqualTo(120);
            assertThat(resize.rows()).isEqualTo(40);
            assertThat(resize.operationSequence()).isEqualTo(2);

            SessionEventCodec events = new SessionEventCodec(AgentProtocolLimits.journalDefaults());
            byte[] envelope = new AgentProtocolCodec(AgentProtocolLimits.defaults()).encode(input);
            peer.sessions.replicationService().append(
                    peer.sessions.activeAgentContext(AGENT).orElseThrow(), SESSION, List.of(events.decode(events.encode(
                    new EventId(1), new SessionEventPayload.CommandResult(SessionCommandSource.SERVER, 1,
                            ProtocolBytes.copyOf(envelope), SessionCommandOutcome.SUCCEEDED, "")))));
            JsonNode confirmed = json(peer.request("GET", "?commandId=input-1", null, true));
            assertThat(confirmed.path("phase").asText()).isEqualTo("CONFIRMED");
            assertThat(confirmed.path("outcome").asText()).isEqualTo("SUCCEEDED");
            assertThat(confirmed.path("operationSequence").asText()).isEqualTo("1");
        }
    }

    @Test
    void exposesLateTransientFailuresWithoutClaimingExecutionCompletion() throws Exception {
        try (Peer peer = new Peer(root)) {
            peer.post(INPUT);
            peer.control.onMessage(new AgentMessage.CommandResult(new CommandId("input-1"),
                    Optional.of(SESSION), AgentMessage.CommandOutcome.FAILED, "host unavailable"));
            JsonNode status = json(peer.request("GET", "?commandId=input-1", null, true));
            assertThat(status.path("phase").asText()).isEqualTo("DELIVERY_FAILED");
            assertThat(status.path("outcome").asText()).isEmpty();
            assertThat(status.path("detail").asText()).isEqualTo("host unavailable");
        }
    }

    @Test
    void rejectsUnauthorizedMalformedDuplicateAndExitedSessionCommands() throws Exception {
        try (Peer peer = new Peer(root)) {
            assertThat(peer.request("POST", "", INPUT, false).statusCode()).isEqualTo(403);
            for (String invalid : List.of("null", "{}", "{", """
                    {"commandId":"bad","operation":"resize","columns":0,"rows":24}
                    """, """
                    {"commandId":"bad","operation":"resize","columns":80.5,"rows":24}
                    """, """
                    {"commandId":"bad","operation":"input","bytes":"!"}
                    """)) {
                assertThat(peer.post(invalid).statusCode()).isEqualTo(400);
            }
            assertThat(peer.post("x".repeat(65537)).statusCode()).isEqualTo(413);
            assertThat(peer.post(INPUT).statusCode()).isEqualTo(200);
            assertThat(peer.post(INPUT).statusCode()).isEqualTo(409);
            assertThat(peer.request("GET", "?commandId=missing", null, true).statusCode()).isEqualTo(404);
            assertThat(peer.request("GET", "", null, true).statusCode()).isEqualTo(400);
            peer.control.onMessage(new AgentMessage.SessionList(List.of(new SessionDescriptor(
                    SESSION, AgentMessage.SessionState.EXITED, Optional.empty(), Optional.empty(), "exited"))));
            assertThat(peer.post(INPUT.replace("input-1", "input-2")).statusCode()).isEqualTo(409);
        }
    }

    @Test
    void rejectsForeignSessionStatusAndReportsUnavailableServer() throws Exception {
        try (Peer peer = new Peer(root)) {
            peer.post(INPUT);
            peer.path = "/api/admin/sessions/other/commands";
            assertThat(peer.request("GET", "?commandId=input-1", null, true).statusCode()).isEqualTo(404);
            assertThat(peer.post(INPUT).statusCode()).isEqualTo(404);
            peer.sessions.onStop();
            assertThat(peer.post(INPUT).statusCode()).isEqualTo(503);
        }
    }

    private static JsonNode json(HttpResponse<String> response) throws IOException {
        return JSON.readTree(response.body());
    }

    private static final class Peer implements AutoCloseable {
        private final AgentSessionServer sessions;
        private final Server http = new Server();
        private final ServerConnector connector = new ServerConnector(http);
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        private final Connection connection = new Connection();
        private final AgentControlHandler.Session control;
        private String path = "/api/admin/sessions/terminal-session/commands";

        private Peer(Path root) throws Exception {
            sessions = new AgentSessionServer(root);
            sessions.onStart();
            sessions.registerAgent(AGENT, "terminal agent");
            control = sessions.open(connection);
            try (var attempt = sessions.provisioningControl(AGENT, URI.create("https://orion.example/agent/control"),
                    "/tmp/agent", 65536, "test").nextAttempt()) {
                control.onMessage(new AgentMessage.Hello(AgentProtocolVersion.CURRENT, JournalFormatVersion.CURRENT,
                        AGENT, new AgentInstanceId(UUID.randomUUID()), "test",
                        new MachineInfo("test", "linux", "aarch64"), Map.of(),
                        Optional.of(new AgentAuthentication(attempt.request().generation(),
                                attempt.request().launchId(), AgentAuthentication.Kind.LAUNCH_PERMIT,
                                ProtocolBytes.copyOf(Base64.getUrlDecoder().decode(attempt.permit().copyBytes()))))));
            }
            control.onMessage(new AgentMessage.SessionList(List.of(new SessionDescriptor(
                    SESSION, AgentMessage.SessionState.RUNNING, Optional.empty(), Optional.empty(), "running"))));
            connector.setHost("127.0.0.1");
            connector.setPort(0);
            http.addConnector(connector);
            OrionHttpRouteServlet servlet = new OrionHttpRouteServlet(
                    new OrionHttpRouteRegistry(Set.of(new SessionCommandsRoute(sessions, JSON))),
                    new OrionHttpResponseWriter(JSON)) {
                @Override
                public void service(HttpServletRequest request, HttpServletResponse response)
                        throws IOException, ServletException {
                    if ("admin".equals(request.getHeader("Authorization"))) {
                        AccessControl.Grant grant = new AccessControlDraft.Grant("admin", new ArrayList<>())
                                .addKey(AccessControl.GrantKey.ADMIN, AccessControl.TRUE_STRING).toAccessControl();
                        request.setAttribute(OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE,
                                SecurityContext.createContext()
                                        .withUserIdentity(new InternalUserImpl("admin", List.of(grant))));
                    }
                    super.service(request, response);
                }
            };
            ServletContextHandler context = new ServletContextHandler();
            context.setContextPath("/");
            context.addServlet(new ServletHolder(servlet), "/*");
            http.setHandler(context);
            http.start();
        }

        private HttpResponse<String> post(String body) throws Exception {
            return request("POST", "", body, true);
        }

        private HttpResponse<String> request(String method, String query, String body, boolean admin)
                throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + connector.getLocalPort()
                    + path + query)).timeout(Duration.ofSeconds(5));
            if (admin) {
                builder.header("Authorization", "admin");
            }
            builder.header("Content-Type", "application/json").method(method,
                    body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }

        @Override
        public void close() throws Exception {
            try {
                client.close();
                http.stop();
            } finally {
                sessions.onStop();
            }
        }
    }

    private static final class Connection implements AgentControlHandler.Connection {
        private final List<AgentMessage> sent = new CopyOnWriteArrayList<>();

        @Override
        public CompletionStage<Void> send(AgentMessage message) {
            sent.add(message);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void handshakeComplete(AuthenticatedConnectionContext context) {
        }

        @Override
        public void close() {
        }
    }
}
