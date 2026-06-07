package pro.deta.orion.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import pro.deta.orion.auth.PlainRootTokenAccessForTests;

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
            assertThat(routeWithPattern(routeTable, "/api/admin/lifecycle/state").get("authorization").asText())
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

    @Test
    void adminRepositoryApiRejectsUnauthorizedAndInvalidCreateRequestsWithoutSideEffects() throws Exception {
        Path orionRoot = tempDir.resolve("orion-repository-api");
        Path repositoriesRoot = orionRoot.resolve("repos");
        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(
                RuntimeHttpTestSupport.httpOnlyConfiguration(orionRoot))) {
            String token = TestBearerTokens.issueRootToken(
                    orion.accessControlService(),
                    orion.httpUrl("/api/admin/token"),
                    600);

            RuntimeHttpTestSupport.HttpResponse withoutToken = RuntimeHttpTestSupport.request(
                    "POST",
                    orion.httpUrl("/api/admin/repositories"),
                    null,
                    "application/json",
                    OBJECT_MAPPER.writeValueAsBytes(Map.of("name", "denied-admin-project")));
            assertThat(withoutToken.status()).isEqualTo(HttpURLConnection.HTTP_FORBIDDEN);
            assertThat(repositoriesRoot.resolve("denied-admin-project")).doesNotExist();

            RuntimeHttpTestSupport.HttpResponse missingName = RuntimeHttpTestSupport.request(
                    "POST",
                    orion.httpUrl("/api/admin/repositories"),
                    TestBearerTokens.bearer(token),
                    "application/json",
                    "{}".getBytes(StandardCharsets.UTF_8));
            assertThat(missingName.status()).isEqualTo(HttpURLConnection.HTTP_BAD_REQUEST);

            RuntimeHttpTestSupport.HttpResponse blankName = RuntimeHttpTestSupport.request(
                    "POST",
                    orion.httpUrl("/api/admin/repositories"),
                    TestBearerTokens.bearer(token),
                    "application/json",
                    OBJECT_MAPPER.writeValueAsBytes(Map.of("name", "   ")));
            assertThat(blankName.status()).isEqualTo(HttpURLConnection.HTTP_BAD_REQUEST);
            assertThat(repositoriesRoot.resolve("   ")).doesNotExist();

            RuntimeHttpTestSupport.HttpResponse invalidJson = RuntimeHttpTestSupport.request(
                    "POST",
                    orion.httpUrl("/api/admin/repositories"),
                    TestBearerTokens.bearer(token),
                    "application/json",
                    "{\"name\":\"malformed-project\"".getBytes(StandardCharsets.UTF_8));
            assertThat(invalidJson.status()).isEqualTo(HttpURLConnection.HTTP_BAD_REQUEST);
            assertThat(repositoriesRoot.resolve("malformed-project")).doesNotExist();

            RuntimeHttpTestSupport.HttpResponse validCreate = RuntimeHttpTestSupport.request(
                    "POST",
                    orion.httpUrl("/api/admin/repositories"),
                    TestBearerTokens.bearer(token),
                    "application/json",
                    OBJECT_MAPPER.writeValueAsBytes(Map.of("name", "created-project")));
            assertThat(validCreate.status()).isEqualTo(HttpURLConnection.HTTP_CREATED);
            assertThat(repositoriesRoot.resolve("created-project").resolve("config")).exists();
        }
    }

    @Test
    void adminUserApiRejectsUnauthorizedAndInvalidRequestsWithoutAclChanges() throws Exception {
        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(
                RuntimeHttpTestSupport.httpOnlyConfiguration(tempDir.resolve("orion-user-api")))) {
            String token = TestBearerTokens.issueRootToken(
                    orion.accessControlService(),
                    orion.httpUrl("/api/admin/token"),
                    600);
            String baselineAcl = adminAcl(orion, token).body();

            RuntimeHttpTestSupport.HttpResponse withoutToken = RuntimeHttpTestSupport.request(
                    "POST",
                    orion.httpUrl("/api/admin/users"),
                    null,
                    "application/json",
                    OBJECT_MAPPER.writeValueAsBytes(Map.of("id", "denied-admin-user")));
            assertThat(withoutToken.status()).isEqualTo(HttpURLConnection.HTTP_FORBIDDEN);
            assertAclUnchanged(orion, token, baselineAcl);

            RuntimeHttpTestSupport.HttpResponse missingId = RuntimeHttpTestSupport.request(
                    "POST",
                    orion.httpUrl("/api/admin/users"),
                    TestBearerTokens.bearer(token),
                    "application/json",
                    "{}".getBytes(StandardCharsets.UTF_8));
            assertThat(missingId.status()).isEqualTo(HttpURLConnection.HTTP_BAD_REQUEST);
            assertAclUnchanged(orion, token, baselineAcl);

            RuntimeHttpTestSupport.HttpResponse blankId = RuntimeHttpTestSupport.request(
                    "POST",
                    orion.httpUrl("/api/admin/users"),
                    TestBearerTokens.bearer(token),
                    "application/json",
                    OBJECT_MAPPER.writeValueAsBytes(Map.of("id", "   ")));
            assertThat(blankId.status()).isEqualTo(HttpURLConnection.HTTP_BAD_REQUEST);
            assertAclUnchanged(orion, token, baselineAcl);

            RuntimeHttpTestSupport.HttpResponse blankRepositoryGrant = RuntimeHttpTestSupport.request(
                    "POST",
                    orion.httpUrl("/api/admin/users"),
                    TestBearerTokens.bearer(token),
                    "application/json",
                    OBJECT_MAPPER.writeValueAsBytes(Map.of(
                            "id", "broken-grant-user",
                            "repositories", List.of(Map.of(
                                    "repository", "   ",
                                    "read", true)))));
            assertThat(blankRepositoryGrant.status()).isEqualTo(HttpURLConnection.HTTP_BAD_REQUEST);
            assertAclUnchanged(orion, token, baselineAcl);

            RuntimeHttpTestSupport.HttpResponse invalidJson = RuntimeHttpTestSupport.request(
                    "POST",
                    orion.httpUrl("/api/admin/users"),
                    TestBearerTokens.bearer(token),
                    "application/json",
                    "{\"id\":\"malformed-user\"".getBytes(StandardCharsets.UTF_8));
            assertThat(invalidJson.status()).isEqualTo(HttpURLConnection.HTTP_BAD_REQUEST);
            assertAclUnchanged(orion, token, baselineAcl);

            RuntimeHttpTestSupport.HttpResponse validCreate = RuntimeHttpTestSupport.request(
                    "POST",
                    orion.httpUrl("/api/admin/users"),
                    TestBearerTokens.bearer(token),
                    "application/json",
                    OBJECT_MAPPER.writeValueAsBytes(Map.of("id", "created-admin-user")));
            assertThat(validCreate.status()).isEqualTo(HttpURLConnection.HTTP_CREATED);
            assertThat(adminAcl(orion, token).body()).contains("created-admin-user");
        }
    }

    @Test
    void bearerTokenExpiresAndFreshTokenRestoresAdminAccess() throws Exception {
        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(
                RuntimeHttpTestSupport.httpOnlyConfiguration(tempDir.resolve("orion-token-lifecycle")))) {
            String shortLivedToken = TestBearerTokens.issueRootToken(
                    orion.accessControlService(),
                    orion.httpUrl("/api/admin/token"),
                    1);

            RuntimeHttpTestSupport.HttpResponse authorized = adminAcl(orion, shortLivedToken);
            assertThat(authorized.status()).isEqualTo(HttpURLConnection.HTTP_OK);

            RuntimeHttpTestSupport.HttpResponse expired = waitForForbiddenAdminAcl(orion, shortLivedToken);
            assertThat(expired.status()).isEqualTo(HttpURLConnection.HTTP_FORBIDDEN);

            String freshToken = TestBearerTokens.issueRootToken(
                    orion.accessControlService(),
                    orion.httpUrl("/api/admin/token"),
                    600);
            RuntimeHttpTestSupport.HttpResponse restored = adminAcl(orion, freshToken);
            assertThat(restored.status()).isEqualTo(HttpURLConnection.HTTP_OK);
        }
    }

    @Test
    void tokenApiRejectsInvalidCredentialsAndExpirationBeforeIssuingFreshToken() throws Exception {
        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(
                RuntimeHttpTestSupport.httpOnlyConfiguration(tempDir.resolve("orion-token-api")))) {
            RuntimeHttpTestSupport.HttpResponse withoutBasic = RuntimeHttpTestSupport.request(
                    "POST",
                    orion.httpUrl("/api/admin/token"),
                    null,
                    "application/json",
                    "{}".getBytes(StandardCharsets.UTF_8));
            assertThat(withoutBasic.status()).isEqualTo(HttpURLConnection.HTTP_UNAUTHORIZED);

            RuntimeHttpTestSupport.HttpResponse malformedBasic = RuntimeHttpTestSupport.request(
                    "POST",
                    orion.httpUrl("/api/admin/token"),
                    "Basic not-base64",
                    "application/json",
                    "{}".getBytes(StandardCharsets.UTF_8));
            assertThat(malformedBasic.status()).isEqualTo(HttpURLConnection.HTTP_UNAUTHORIZED);

            RuntimeHttpTestSupport.HttpResponse wrongPassword = RuntimeHttpTestSupport.request(
                    "POST",
                    orion.httpUrl("/api/admin/token"),
                    basic("root", "wrong-password".toCharArray()),
                    "application/json",
                    "{}".getBytes(StandardCharsets.UTF_8));
            assertThat(wrongPassword.status()).isEqualTo(HttpURLConnection.HTTP_UNAUTHORIZED);

            RuntimeHttpTestSupport.HttpResponse invalidExpiration = RuntimeHttpTestSupport.request(
                    "POST",
                    orion.httpUrl("/api/admin/token"),
                    rootBasic(orion),
                    "application/json",
                    OBJECT_MAPPER.writeValueAsBytes(Map.of("expiresInSeconds", 0)));
            assertThat(invalidExpiration.status()).isEqualTo(HttpURLConnection.HTTP_BAD_REQUEST);

            String freshToken = TestBearerTokens.issueRootToken(
                    orion.accessControlService(),
                    orion.httpUrl("/api/admin/token"),
                    600);
            assertThat(adminAcl(orion, freshToken).status()).isEqualTo(HttpURLConnection.HTTP_OK);
        }
    }

    @Test
    void adminApiRemainsAccessibleAndReportsRuntimeLifecycleState() throws Exception {
        try (RuntimeHttpTestSupport.StartedOrion orion = RuntimeHttpTestSupport.start(
                RuntimeHttpTestSupport.httpOnlyConfiguration(tempDir.resolve("orion")))) {
            String token = TestBearerTokens.issueRootToken(
                    orion.accessControlService(),
                    orion.httpUrl("/api/admin/token"),
                    600);

            RuntimeHttpTestSupport.HttpResponse lifecycleState = RuntimeHttpTestSupport.request(
                    "GET",
                    orion.httpUrl("/api/admin/lifecycle/state"),
                    TestBearerTokens.bearer(token));
            assertThat(lifecycleState.status()).isEqualTo(HttpURLConnection.HTTP_OK);
            assertThat(lifecycleState.contentType()).startsWith("text/plain");
            assertThat(lifecycleState.body()).contains("orion: RUNNING");
            assertThat(lifecycleState.body()).contains("executor: RUNNING");
            assertThat(lifecycleState.body()).contains("jgit-runtime: RUNNING");
            assertThat(lifecycleState.body()).contains("event-manager: RUNNING");
            assertThat(lifecycleState.body()).contains("access-control: RUNNING");
            assertThat(lifecycleState.body()).contains("transports: RUNNING");
            assertThat(lifecycleState.body()).contains("git-native: DISABLED");
            assertThat(lifecycleState.body()).contains("git-ssh: DISABLED");
            assertThat(lifecycleState.body()).contains("http: RUNNING");

            RuntimeHttpTestSupport.HttpResponse acl = RuntimeHttpTestSupport.request(
                    "GET",
                    orion.httpUrl("/api/admin/acl"),
                    TestBearerTokens.bearer(token));
            assertThat(acl.status()).isEqualTo(HttpURLConnection.HTTP_OK);

            RuntimeHttpTestSupport.HttpResponse routes = RuntimeHttpTestSupport.request(
                    "GET",
                    orion.httpUrl("/api/admin/routes"),
                    TestBearerTokens.bearer(token));
            assertThat(routes.status()).isEqualTo(HttpURLConnection.HTTP_OK);
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

    private static RuntimeHttpTestSupport.HttpResponse adminAcl(RuntimeHttpTestSupport.StartedOrion orion, String token)
            throws Exception {
        return RuntimeHttpTestSupport.request(
                "GET",
                orion.httpUrl("/api/admin/acl"),
                TestBearerTokens.bearer(token));
    }

    private static String rootBasic(RuntimeHttpTestSupport.StartedOrion orion) {
        char[] rootPassword = orion.accessControlService().plainRootToken(PlainRootTokenAccessForTests.create());
        try {
            return basic("root", rootPassword);
        } finally {
            Arrays.fill(rootPassword, '\0');
        }
    }

    private static String basic(String username, char[] password) {
        byte[] credentials = (username + ":" + String.valueOf(password)).getBytes(StandardCharsets.UTF_8);
        try {
            return "Basic " + Base64.getEncoder().encodeToString(credentials);
        } finally {
            Arrays.fill(credentials, (byte) 0);
        }
    }

    private static void assertAclUnchanged(
            RuntimeHttpTestSupport.StartedOrion orion,
            String token,
            String expectedAcl) throws Exception {
        RuntimeHttpTestSupport.HttpResponse acl = adminAcl(orion, token);
        assertThat(acl.status()).isEqualTo(HttpURLConnection.HTTP_OK);
        assertThat(acl.body()).isEqualTo(expectedAcl);
    }

    private static RuntimeHttpTestSupport.HttpResponse waitForForbiddenAdminAcl(
            RuntimeHttpTestSupport.StartedOrion orion,
            String token) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        RuntimeHttpTestSupport.HttpResponse response;
        do {
            response = adminAcl(orion, token);
            if (response.status() == HttpURLConnection.HTTP_FORBIDDEN) {
                return response;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        return response;
    }
}
