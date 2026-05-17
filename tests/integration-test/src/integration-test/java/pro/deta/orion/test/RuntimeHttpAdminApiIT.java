package pro.deta.orion.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.HttpURLConnection;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeHttpAdminApiIT {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void runtimeAdminApiEnforcesBearerAuthorizationAndServesRouteContract() throws Exception {
        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(
                RuntimeHttpTestSupport.httpOnlyConfiguration(tempDir.resolve("orion")))) {
            String token = TestBearerTokens.issueRootToken(
                    orion.accessControlService(),
                    orion.httpUrl("/api/admin/token"),
                    600);

            RuntimeHttpTestSupport.HttpResponse aclWithoutToken =
                    RuntimeHttpTestSupport.request("GET", orion.httpUrl("/api/admin/acl"), null);
            assertThat(aclWithoutToken.status()).isEqualTo(HttpURLConnection.HTTP_FORBIDDEN);

            RuntimeHttpTestSupport.HttpResponse aclWithToken = RuntimeHttpTestSupport.request(
                    "GET",
                    orion.httpUrl("/api/admin/acl"),
                    TestBearerTokens.bearer(token));
            assertThat(aclWithToken.status()).isEqualTo(HttpURLConnection.HTTP_OK);
            assertThat(aclWithToken.contentType()).startsWith("application/xml");
            assertThat(aclWithToken.body()).contains("AccessControl");

            RuntimeHttpTestSupport.HttpResponse routes = RuntimeHttpTestSupport.request(
                    "GET",
                    orion.httpUrl("/api/admin/routes"),
                    TestBearerTokens.bearer(token));
            assertThat(routes.status()).isEqualTo(HttpURLConnection.HTTP_OK);
            assertThat(routes.contentType()).startsWith("application/json");
            JsonNode routeTable = OBJECT_MAPPER.readTree(routes.body()).get("routes");
            assertThat(routeWithPattern(routeTable, "/api/admin/acl").get("methods").toString())
                    .isEqualTo("[\"GET\",\"POST\"]");
            assertThat(routeWithPattern(routeTable, "/api/admin/routes").get("authorization").asText())
                    .isEqualTo("application-admin");

            RuntimeHttpTestSupport.HttpResponse wrongMethod = RuntimeHttpTestSupport.request(
                    "PUT",
                    orion.httpUrl("/api/admin/acl"),
                    TestBearerTokens.bearer(token));
            assertThat(wrongMethod.status()).isEqualTo(HttpURLConnection.HTTP_BAD_METHOD);
            assertThat(wrongMethod.allow()).isEqualTo("GET, POST");

            RuntimeHttpTestSupport.HttpResponse unknownPath = RuntimeHttpTestSupport.request(
                    "GET",
                    orion.httpUrl("/api/admin/missing"),
                    TestBearerTokens.bearer(token));
            assertThat(unknownPath.status()).isEqualTo(HttpURLConnection.HTTP_NOT_FOUND);

            RuntimeHttpTestSupport.HttpResponse invalidToken = RuntimeHttpTestSupport.request(
                    "GET",
                    orion.httpUrl("/api/admin/acl"),
                    TestBearerTokens.bearer("invalid-token"));
            assertThat(invalidToken.status()).isEqualTo(HttpURLConnection.HTTP_FORBIDDEN);
        }
    }

    private static JsonNode routeWithPattern(JsonNode routes, String pattern) {
        for (JsonNode route : routes) {
            if (pattern.equals(route.get("urlPattern").asText())) {
                return route;
            }
        }
        throw new AssertionError("Route not found: " + pattern);
    }
}
