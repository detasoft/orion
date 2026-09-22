package pro.deta.orion.transport.http;

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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.decision.Decision;
import pro.deta.orion.decision.DecisionRegistry;
import pro.deta.orion.decision.PendingDecision;
import pro.deta.orion.schema.orion.ConfigurationScope;
import pro.deta.orion.schema.orion.PrincipalAddress;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class OrionAdminDecisionsRouteTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final PrincipalAddress ACTOR = PrincipalAddress.parse("system/reviewer");
    private static final Map<String, String> ACTIONS = Map.of("replace", "Replace key", "reject", "Reject");

    @Test
    void listsSystemOrganizationTeamAndRepositoryRequestsAsJson() throws Exception {
        try (DecisionRegistry registry = new DecisionRegistry(8, (actor, scope) -> true);
             Peer peer = new Peer(registry)) {
            for (String scope : new String[]{null, "acme", "acme/platform", "acme/platform/api"}) {
                register(registry, scope);
            }
            HttpResponse<String> response = peer.request("GET", null, "reviewer", null);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                    .startsWith("application/json");
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            JsonNode requests = JSON.readTree(response.body()).path("decisions");
            assertThat(requests.size()).isEqualTo(4);
            for (int index = 0; index < requests.size(); index++) {
                JsonNode request = requests.get(index);
                assertThat(request.path("scope").asText())
                        .isEqualTo(List.of("system", "acme", "acme/platform", "acme/platform/api").get(index));
                assertThat(request.path("title").asText()).isEqualTo("SSH host key changed");
                assertThat(request.path("description").asText()).isEqualTo("Review the new fingerprint");
                assertThat(request.path("actions")).isEqualTo(JSON.valueToTree(ACTIONS));
                assertThat(request.path("createdAt").asText()).isEqualTo(registry.find(
                        UUID.fromString(request.path("id").asText()), ACTOR).orElseThrow().createdAt().toString());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"replace", "reject"})
    void answersWithAuthenticatedActorAndRejectsRepeatedAnswer(String action) throws Exception {
        try (DecisionRegistry registry = new DecisionRegistry(8, (actor, scope) -> true);
             Peer peer = new Peer(registry)) {
            PendingDecision pending = register(registry, "acme/platform/api");
            String body = answer(pending, action);
            HttpResponse<String> response = peer.request("POST", body, "reviewer", "application/json");
            assertThat(response.statusCode()).isEqualTo(204);
            assertThat(response.body()).isEmpty();
            assertThat(pending.result().toCompletableFuture()).isCompletedWithValue(new Decision(action, ACTOR));
            assertThat(JSON.readTree(peer.request("GET", null, "reviewer", null).body()).path("decisions"))
                    .isEmpty();
            assertThat(peer.request("POST", body, "reviewer", "application/json").statusCode()).isEqualTo(404);
        }
    }

    @Test
    void rejectsInvalidJsonFieldsActionsAndActorSpoofingWithoutCompletingRequest() throws Exception {
        try (DecisionRegistry registry = new DecisionRegistry(8, (actor, scope) -> true);
             Peer peer = new Peer(registry)) {
            PendingDecision pending = register(registry, null);
            String valid = answer(pending, "replace");
            for (String body : List.of("", "{", "null", "[]", "{}", valid + " {}",
                    "{\"id\":1,\"action\":\"replace\"}",
                    "{\"id\":\"invalid\",\"action\":\"replace\"}",
                    valid.replace("\"replace\"", "false"), answer(pending, " "), answer(pending, "unknown"),
                    valid.substring(0, valid.length() - 1) + ",\"actor\":\"system/root\"}")) {
                assertThat(peer.request("POST", body, "reviewer", "application/json").statusCode())
                        .as("body: %s", body).isEqualTo(400);
                assertThat(pending.result().toCompletableFuture()).isNotDone();
            }
        }
    }

    @Test
    void requiresAnAuthenticatedAddressableIdentity() throws Exception {
        try (DecisionRegistry registry = new DecisionRegistry(8, (actor, scope) -> {
            throw new AssertionError("Invalid identity reached the registry");
        }); Peer peer = new Peer(registry)) {
            PendingDecision pending = register(registry, null);
            for (String identity : new String[]{null, "acme/reviewer", "Invalid"}) {
                assertThat(peer.request("GET", null, identity, null).statusCode()).isEqualTo(403);
                assertThat(peer.request("POST", answer(pending, "replace"), identity, "application/json")
                        .statusCode()).isEqualTo(403);
            }
            assertThat(pending.result().toCompletableFuture()).isNotDone();
        }
    }

    @Test
    void usesCurrentRegistryVisibilityAndHidesUnavailableRequests() throws Exception {
        AtomicBoolean allowed = new AtomicBoolean(true);
        try (DecisionRegistry registry = new DecisionRegistry(8,
                (actor, scope) -> actor.equals(ACTOR) && scope.isEmpty() && allowed.get());
             Peer peer = new Peer(registry)) {
            PendingDecision visible = register(registry, null);
            PendingDecision hidden = register(registry, "acme");
            JsonNode requests = JSON.readTree(peer.request("GET", null, "reviewer", null).body())
                    .path("decisions");
            assertThat(requests.size()).isEqualTo(1);
            assertThat(requests.get(0).path("id").asText()).isEqualTo(visible.request().id().toString());
            assertThat(peer.request("POST", answer(hidden, "replace"), "reviewer", "application/json")
                    .statusCode()).isEqualTo(404);
            allowed.set(false);
            assertThat(peer.request("POST", answer(visible, "replace"), "reviewer", "application/json")
                    .statusCode()).isEqualTo(404);
            assertThat(JSON.readTree(peer.request("GET", null, "reviewer", null).body()).path("decisions"))
                    .isEmpty();
            assertThat(visible.result().toCompletableFuture()).isNotDone();
            assertThat(hidden.result().toCompletableFuture()).isNotDone();
        }
    }

    @Test
    void boundsRequestBodyAndRejectsUnsupportedContentTypesAndMethods() throws Exception {
        try (DecisionRegistry registry = new DecisionRegistry(8, (actor, scope) -> true);
             Peer peer = new Peer(registry)) {
            PendingDecision pending = register(registry, null);
            String body = answer(pending, "replace");
            for (String contentType : new String[]{null, "text/plain"}) {
                assertThat(peer.request("POST", body, "reviewer", contentType).statusCode()).isEqualTo(415);
            }
            assertThat(peer.request("POST", "x".repeat(65537), "reviewer", "application/json")
                    .statusCode()).isEqualTo(413);
            HttpResponse<String> response = peer.request("DELETE", null, "reviewer", null);
            assertThat(response.statusCode()).isEqualTo(405);
            assertThat(response.headers().firstValue("Allow")).contains("GET, POST");
            assertThat(pending.result().toCompletableFuture()).isNotDone();
            assertThat(peer.request("POST", body, "reviewer", "application/json; charset=utf-8")
                    .statusCode()).isEqualTo(204);
        }
    }

    @Test
    void onlyOneConcurrentHttpAnswerCompletesTheDecision() throws Exception {
        try (DecisionRegistry registry = new DecisionRegistry(8, (actor, scope) -> true);
             Peer peer = new Peer(registry)) {
            PendingDecision pending = register(registry, null);
            CompletableFuture<HttpResponse<String>> replace = peer.client.sendAsync(
                    peer.message("POST", answer(pending, "replace"), "reviewer", "application/json"),
                    HttpResponse.BodyHandlers.ofString());
            CompletableFuture<HttpResponse<String>> reject = peer.client.sendAsync(
                    peer.message("POST", answer(pending, "reject"), "other", "application/json"),
                    HttpResponse.BodyHandlers.ofString());
            int replaceStatus = replace.get(5, TimeUnit.SECONDS).statusCode();
            int rejectStatus = reject.get(5, TimeUnit.SECONDS).statusCode();
            assertThat(List.of(replaceStatus, rejectStatus)).containsExactlyInAnyOrder(204, 404);
            assertThat(pending.result().toCompletableFuture()).isCompletedWithValue(replaceStatus == 204
                    ? new Decision("replace", ACTOR)
                    : new Decision("reject", PrincipalAddress.parse("system/other")));
        }
    }

    private static PendingDecision register(DecisionRegistry registry, String scope) {
        return registry.register(Optional.ofNullable(scope).map(ConfigurationScope::parse),
                "SSH host key changed", "Review the new fingerprint", ACTIONS).valueOrFailure("register decision");
    }

    private static String answer(PendingDecision pending, String action) throws IOException {
        return JSON.writeValueAsString(Map.of("id", pending.request().id().toString(), "action", action));
    }

    private static final class Peer implements AutoCloseable {
        private final Server http = new Server();
        private final ServerConnector connector = new ServerConnector(http);
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

        private Peer(DecisionRegistry registry) throws Exception {
            connector.setHost("127.0.0.1");
            connector.setPort(0);
            http.addConnector(connector);
            OrionHttpRouteServlet servlet = new OrionHttpRouteServlet(
                    new OrionHttpRouteRegistry(Set.of(OrionHttpModule.decisionsRoute(
                            new OrionAdminDecisionsRoute(registry, JSON)))),
                    new OrionHttpResponseWriter(JSON)) {
                @Override
                public void service(HttpServletRequest request, HttpServletResponse response)
                        throws IOException, ServletException {
                    String identity = request.getHeader("Test-Identity");
                    if (identity != null) {
                        request.setAttribute(OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE,
                                SecurityContext.createContext()
                                        .withUserIdentity(new InternalUserImpl(identity, List.of())));
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

        private HttpRequest message(String method, String body, String identity, String contentType) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                    + connector.getLocalPort() + "/api/admin/decisions")).timeout(Duration.ofSeconds(5));
            if (identity != null) {
                builder.header("Test-Identity", identity);
            }
            if (contentType != null) {
                builder.header("Content-Type", contentType);
            }
            return builder.method(method, body == null
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
        }

        private HttpResponse<String> request(String method, String body, String identity, String contentType)
                throws Exception {
            return client.send(message(method, body, identity, contentType), HttpResponse.BodyHandlers.ofString());
        }

        @Override
        public void close() throws Exception {
            try {
                client.close();
            } finally {
                http.stop();
            }
        }
    }
}
