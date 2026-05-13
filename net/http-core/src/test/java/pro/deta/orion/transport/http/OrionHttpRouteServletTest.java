package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import pro.deta.orion.GitRepositoryProvider;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.auth.AccessControlUserUpdate;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.TokenIssueResult;
import pro.deta.orion.auth.UserIdentity;
import pro.deta.orion.config.schema.OrionConfiguration;
import pro.deta.orion.git.common.GitRepository;
import pro.deta.orion.util.Result;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OrionHttpRouteServletTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String BASIC_USER = "root";
    private static final String BASIC_PASSWORD = "root-password";
    private static final String ISSUED_TOKEN = "issued-jwt";
    private static final long TOKEN_EXPIRES_AT = 1_800_000_000L;
    private static final String ACL_CONFIGURATION = "<access-control/>";
    private static final String ACME_CERTIFICATE_PEM = """
            -----BEGIN CERTIFICATE-----
            TEST
            -----END CERTIFICATE-----
            """;
    private static final String ACME_PRIVATE_KEY_PEM = """
            -----BEGIN RSA PRIVATE KEY-----
            TEST
            -----END RSA PRIVATE KEY-----
            """;

    @Test
    void createsManagedUser() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        RecordingGitRepositoryProvider gitRepositoryProvider = new RecordingGitRepositoryProvider();
        OrionHttpRouteServlet servlet = servlet(accessControlService, gitRepositoryProvider);

        ResponseRecorder response = new ResponseRecorder();
        servlet.service(
                request("POST", "/api/admin/users", null, """
                        {
                          "id": "client",
                          "email": "client@example.test",
                          "publicKey": "ssh-rsa AAAATEST client@example.test",
                          "repositories": [
                            {
                              "repository": "project",
                              "read": true,
                              "write": true,
                              "create": true,
                              "branch": "*"
                            }
                          ]
                        }
                        """),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_CREATED);
        assertThat(response.contentType).isEqualTo("application/json");
        assertThat(response.body.toString()).isEqualTo("{\"status\":\"ok\"}");

        AccessControlUserUpdate update = accessControlService.lastUpdate;
        assertThat(update.id()).isEqualTo("client");
        assertThat(update.email()).isEqualTo("client@example.test");
        assertThat(update.credentials()).hasSize(1);
        assertThat(update.credentials().getFirst().type()).isEqualTo(AccessControl.CredentialType.OPENSSH_PUBLIC_KEY);
        assertThat(update.credentials().getFirst().value()).isEqualTo("ssh-rsa AAAATEST client@example.test");
        assertThat(update.repositories()).hasSize(1);
        assertThat(update.repositories().getFirst().repository()).isEqualTo("project");
        assertThat(update.repositories().getFirst().read()).isTrue();
        assertThat(update.repositories().getFirst().write()).isTrue();
        assertThat(update.repositories().getFirst().create()).isTrue();
        assertThat(update.repositories().getFirst().branch()).isEqualTo("*");
    }

    @Test
    void createsRepository() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        RecordingGitRepositoryProvider gitRepositoryProvider = new RecordingGitRepositoryProvider();
        OrionHttpRouteServlet servlet = servlet(accessControlService, gitRepositoryProvider);

        ResponseRecorder response = new ResponseRecorder();
        servlet.service(
                request("POST", "/api/admin/repositories", null, """
                        {"name":"project"}
                        """),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_CREATED);
        assertThat(gitRepositoryProvider.lastCreatedRepository).isEqualTo("project");
        assertThat(accessControlService.lastUpdate).isNull();
    }

    @Test
    void issuesBearerTokenWithBasicCredentials() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        RecordingGitRepositoryProvider gitRepositoryProvider = new RecordingGitRepositoryProvider();
        OrionHttpRouteServlet servlet = servlet(accessControlService, gitRepositoryProvider);

        ResponseRecorder response = new ResponseRecorder();
        servlet.service(
                request("POST", "/api/admin/token", basicAuth(BASIC_USER, BASIC_PASSWORD), """
                        {"expiresInSeconds":600}
                        """),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType).isEqualTo("application/json");
        assertThat(response.body.toString())
                .isEqualTo("{\"token\":\"issued-jwt\",\"tokenType\":\"Bearer\",\"expiresInSeconds\":600,\"expiresAtEpochSecond\":1800000000}");
        assertThat(accessControlService.lastTokenUserName).isEqualTo(BASIC_USER);
        assertThat(new String(accessControlService.lastTokenCredential, StandardCharsets.UTF_8)).isEqualTo(BASIC_PASSWORD);
        assertThat(accessControlService.lastTokenExpiresInSeconds).isEqualTo(600);
    }

    @Test
    void returnsAccessControlConfigurationFile() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        RecordingGitRepositoryProvider gitRepositoryProvider = new RecordingGitRepositoryProvider();
        OrionHttpRouteServlet servlet = servlet(accessControlService, gitRepositoryProvider);

        ResponseRecorder response = new ResponseRecorder();
        servlet.service(
                request("GET", "/api/admin/acl", null, ""),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType).isEqualTo("application/xml");
        assertThat(response.body.toString()).isEqualTo(ACL_CONFIGURATION);
    }

    @Test
    void commitsAccessControlConfigurationFile() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        RecordingGitRepositoryProvider gitRepositoryProvider = new RecordingGitRepositoryProvider();
        OrionHttpRouteServlet servlet = servlet(accessControlService, gitRepositoryProvider);

        ResponseRecorder response = new ResponseRecorder();
        servlet.service(
                request("POST", "/api/admin/acl", null, ACL_CONFIGURATION),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_CREATED);
        assertThat(response.contentType).isEqualTo("application/json");
        assertThat(response.body.toString()).isEqualTo("{\"status\":\"ok\"}");
        assertThat(new String(accessControlService.lastAccessControlConfiguration, StandardCharsets.UTF_8))
                .isEqualTo(ACL_CONFIGURATION);
    }

    @Test
    void rejectsAccessControlConfigurationRequestWithWrongMethod() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        RecordingGitRepositoryProvider gitRepositoryProvider = new RecordingGitRepositoryProvider();
        OrionHttpRouteServlet servlet = servlet(accessControlService, gitRepositoryProvider);

        ResponseRecorder response = new ResponseRecorder();
        servlet.service(
                request("PUT", "/api/admin/acl", null, ""),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        assertThat(response.headers).containsEntry("Allow", "GET, POST");
    }

    @Test
    void rejectsTokenRequestWithoutBasicCredentials() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        RecordingGitRepositoryProvider gitRepositoryProvider = new RecordingGitRepositoryProvider();
        OrionHttpRouteServlet servlet = servlet(accessControlService, gitRepositoryProvider);

        ResponseRecorder response = new ResponseRecorder();
        servlet.service(
                request("POST", "/api/admin/token", null, ""),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.headers).containsEntry("WWW-Authenticate", "Basic realm=\"orion-admin\"");
        assertThat(accessControlService.lastTokenUserName).isNull();
    }

    @Test
    void servesAcmeHttpChallenge() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        RecordingGitRepositoryProvider gitRepositoryProvider = new RecordingGitRepositoryProvider();
        AcmeHttpChallengeService challengeService = new AcmeHttpChallengeService();
        challengeService.registerChallenge("test-token", "test-authorization");
        OrionHttpRouteServlet servlet = servlet(accessControlService, gitRepositoryProvider, challengeService);

        ResponseRecorder response = new ResponseRecorder();
        servlet.service(
                request("GET", AcmeHttpChallengeRoute.CHALLENGE_PREFIX + "test-token", null, "", SecurityContext.createContext()),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType).isEqualTo("text/plain");
        assertThat(response.body.toString()).isEqualTo("test-authorization");
    }

    @Test
    void returnsNotFoundForUnknownAcmeHttpChallenge() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        RecordingGitRepositoryProvider gitRepositoryProvider = new RecordingGitRepositoryProvider();
        OrionHttpRouteServlet servlet = servlet(accessControlService, gitRepositoryProvider);

        ResponseRecorder response = new ResponseRecorder();
        servlet.service(
                request("GET", AcmeHttpChallengeRoute.CHALLENGE_PREFIX + "missing-token", null, "", SecurityContext.createContext()),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
        assertThat(response.body.toString()).isEmpty();
    }

    @Test
    void issuesAcmeCertificateAsNginxPem() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        RecordingGitRepositoryProvider gitRepositoryProvider = new RecordingGitRepositoryProvider();
        RecordingAcmeCertificateService acmeCertificateService = new RecordingAcmeCertificateService();
        OrionHttpRouteServlet servlet = servlet(
                accessControlService,
                gitRepositoryProvider,
                new AcmeHttpChallengeService(),
                acmeCertificateService);

        ResponseRecorder response = new ResponseRecorder();
        servlet.service(
                request("POST", "/api/admin/acme/certificate", null, """
                        {"domains":["other.example.test"],"persist":false}
                        """),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType).isEqualTo("application/x-pem-file");
        assertThat(response.headers)
                .containsEntry("Content-Disposition", "attachment; filename=\"orion-acme-other.example.test-nginx.pem\"")
                .containsEntry("Cache-Control", "no-store");
        assertThat(response.body.toString()).isEqualTo(ACME_CERTIFICATE_PEM + ACME_PRIVATE_KEY_PEM);
        assertThat(acmeCertificateService.lastIssueRequest.domains()).containsExactly("other.example.test");
        assertThat(acmeCertificateService.lastIssueRequest.persist()).isFalse();
    }

    @Test
    void downloadsSavedAcmeCertificateAsNginxPem() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        RecordingGitRepositoryProvider gitRepositoryProvider = new RecordingGitRepositoryProvider();
        RecordingAcmeCertificateService acmeCertificateService = new RecordingAcmeCertificateService();
        acmeCertificateService.savedCertificate = ACME_CERTIFICATE_PEM + ACME_PRIVATE_KEY_PEM;
        OrionHttpRouteServlet servlet = servlet(
                accessControlService,
                gitRepositoryProvider,
                new AcmeHttpChallengeService(),
                acmeCertificateService);

        ResponseRecorder response = new ResponseRecorder();
        servlet.service(
                request("GET", "/api/admin/acme/certificate", null, ""),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType).isEqualTo("application/x-pem-file");
        assertThat(response.body.toString()).isEqualTo(ACME_CERTIFICATE_PEM + ACME_PRIVATE_KEY_PEM);
    }

    @Test
    void rejectsUnsupportedGitHttpMethodAtRouteLayer() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        RecordingGitRepositoryProvider gitRepositoryProvider = new RecordingGitRepositoryProvider();
        OrionHttpRouteServlet servlet = servlet(accessControlService, gitRepositoryProvider);

        ResponseRecorder response = new ResponseRecorder();
        servlet.service(
                request("DELETE", "/r/project/info/refs", null, "", SecurityContext.createContext()),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        assertThat(response.headers).containsEntry("Allow", "GET, POST");
        assertThat(gitRepositoryProvider.lastCreatedRepository).isNull();
    }

    @Test
    void returnsRuntimeRouteTable() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        RecordingGitRepositoryProvider gitRepositoryProvider = new RecordingGitRepositoryProvider();
        OrionHttpRouteServlet servlet = servlet(accessControlService, gitRepositoryProvider);

        ResponseRecorder response = new ResponseRecorder();
        servlet.service(
                request("GET", "/api/admin/routes", null, ""),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType).isEqualTo("application/json");
        JsonNode routes = OBJECT_MAPPER.readTree(response.body.toString()).get("routes");
        assertThat(routes).hasSize(11);
        assertThat(routeWithPattern(routes, AcmeHttpChallengeRoute.URL_PATTERN).get("authorization").asText()).isEqualTo("anonymous");
        assertThat(routeWithPattern(routes, OrionGitRoute.URL_PATTERN).get("authorization").asText()).isEqualTo("git");
        assertThat(routeWithPattern(routes, "/api/admin/acl").get("methods").toString()).isEqualTo("[\"GET\",\"POST\"]");
        assertThat(routeWithPattern(routes, "/api/admin/routes").get("methods").toString()).isEqualTo("[\"GET\"]");
        assertThat(routeWithPattern(routes, "/api/admin/routes").get("authorization").asText()).isEqualTo("application-admin");
        assertThat(routeWithPattern(routes, "/api/admin/shutdown").get("methods").toString()).isEqualTo("[\"POST\"]");
        assertThat(routeWithPattern(routes, "/api/admin/shutdown").get("authorization").asText()).isEqualTo("application-admin");
        assertThat(routeWithPattern(routes, OrionConfigurationSchemaRoute.URL_PATTERN).get("methods").toString()).isEqualTo("[\"GET\"]");
        assertThat(routeWithPattern(routes, OrionConfigurationSchemaRoute.URL_PATTERN).get("authorization").asText()).isEqualTo("anonymous");
        assertThat(routeWithPattern(routes, OrionAccessControlSchemaRoute.URL_PATTERN).get("methods").toString()).isEqualTo("[\"GET\"]");
        assertThat(routeWithPattern(routes, OrionAccessControlSchemaRoute.URL_PATTERN).get("authorization").asText()).isEqualTo("anonymous");
        assertThat(routeWithPattern(routes, "/api/admin/acme/certificate").get("methods").toString()).isEqualTo("[\"GET\",\"POST\"]");
        assertThat(routeWithPattern(routes, "/api/admin/acme/certificate").get("authorization").asText()).isEqualTo("application-admin");
        assertThat(routeWithPattern(routes, "/api/admin/token").get("authorization").asText()).isEqualTo("anonymous");
        assertThat(routeWithPattern(routes, "/api/admin/routes").get("handler").asText())
                .isEqualTo("OrionAdminRoutesRoute");
    }

    @Test
    void requestsServerShutdown() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        RecordingGitRepositoryProvider gitRepositoryProvider = new RecordingGitRepositoryProvider();
        RecordingShutdownLifecycle shutdownLifecycle = new RecordingShutdownLifecycle();
        OrionHttpRouteServlet servlet = servlet(accessControlService, gitRepositoryProvider, shutdownLifecycle);

        ResponseRecorder response = new ResponseRecorder();
        servlet.service(
                request("POST", "/api/admin/shutdown", null, ""),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_ACCEPTED);
        assertThat(response.contentType).isEqualTo("application/json");
        assertThat(response.body.toString()).isEqualTo("{\"status\":\"shutdown-requested\"}");
        assertThat(response.flushed).isTrue();
        assertThat(shutdownLifecycle.shutdownLifecycleStarted).isTrue();
    }

    @Test
    void returnsConfigurationSchemaForEditors() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        RecordingGitRepositoryProvider gitRepositoryProvider = new RecordingGitRepositoryProvider();
        OrionHttpRouteServlet servlet = servlet(accessControlService, gitRepositoryProvider);

        ResponseRecorder response = new ResponseRecorder();
        servlet.service(
                request("GET", OrionConfigurationSchemaRoute.URL_PATTERN, null, ""),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType).isEqualTo("application/schema+json");

        JsonNode schema = OBJECT_MAPPER.readTree(response.body.toString());
        assertThat(schema.get("$schema").asText()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
        assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
        assertThat(schemaAt(schema, "bootstrap", "baseDir").get("default").asText()).isEqualTo("orion");
        assertThat(schemaAt(schema, "bootstrap", "accessControl", "paths").get("type").asText()).isEqualTo("array");
        assertThat(schemaAt(schema, "bootstrap", "accessControl", "paths").get("default").get(0).asText())
                .isEqualTo("orion.xml");
        assertThat(schemaAt(schema, "storage", "auth").get("additionalProperties").get("type").asText())
                .isEqualTo("string");
        assertThat(schemaAt(schema, "transport", "http", "port").get("type").asText()).isEqualTo("integer");
        assertThat(schemaAt(schema, "transport", "https", "acme", "directoryUrl").get("default").asText())
                .isEqualTo("acme://letsencrypt.org/staging");
        assertThat(schemaAt(schema, "transport", "https", "ksystore", "type").get("enum").toString())
                .isEqualTo("[\"PEM\",\"JKS\",\"PKCS_12\"]");
    }

    @Test
    void rejectsRouteTableRequestWithoutAdminGrant() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        RecordingGitRepositoryProvider gitRepositoryProvider = new RecordingGitRepositoryProvider();
        OrionHttpRouteServlet servlet = servlet(accessControlService, gitRepositoryProvider);

        ResponseRecorder response = new ResponseRecorder();
        servlet.service(
                request("GET", "/api/admin/routes", null, "", regularSecurityContext()),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.body.toString()).isEmpty();
    }

    @Test
    void returnsConfigurationSchemaWithoutAdminGrant() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        RecordingGitRepositoryProvider gitRepositoryProvider = new RecordingGitRepositoryProvider();
        OrionHttpRouteServlet servlet = servlet(accessControlService, gitRepositoryProvider);

        ResponseRecorder response = new ResponseRecorder();
        servlet.service(
                request("GET", OrionConfigurationSchemaRoute.URL_PATTERN, null, "", regularSecurityContext()),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType).isEqualTo("application/schema+json");
    }

    @Test
    void returnsAccessControlSchemaForEditors() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        RecordingGitRepositoryProvider gitRepositoryProvider = new RecordingGitRepositoryProvider();
        OrionHttpRouteServlet servlet = servlet(accessControlService, gitRepositoryProvider);

        ResponseRecorder response = new ResponseRecorder();
        servlet.service(
                request("GET", OrionAccessControlSchemaRoute.URL_PATTERN, null, "", regularSecurityContext()),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType).isEqualTo("application/xml");
        String schema = response.body.toString();
        assertThat(schema).contains("name=\"AccessControl\"");
        assertThat(schema).contains("name=\"user\"");
        assertThat(schema).contains("name=\"grant\"");
    }

    @Test
    void rejectsAcmeCertificateRequestWithoutAdminGrant() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        RecordingGitRepositoryProvider gitRepositoryProvider = new RecordingGitRepositoryProvider();
        OrionHttpRouteServlet servlet = servlet(accessControlService, gitRepositoryProvider);

        ResponseRecorder response = new ResponseRecorder();
        servlet.service(
                request("POST", "/api/admin/acme/certificate", null, "", regularSecurityContext()),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.body.toString()).isEmpty();
    }

    @Test
    void rejectsShutdownRequestWithoutAdminGrant() throws Exception {
        RecordingAccessControlService accessControlService = new RecordingAccessControlService();
        RecordingGitRepositoryProvider gitRepositoryProvider = new RecordingGitRepositoryProvider();
        RecordingShutdownLifecycle shutdownLifecycle = new RecordingShutdownLifecycle();
        OrionHttpRouteServlet servlet = servlet(accessControlService, gitRepositoryProvider, shutdownLifecycle);

        ResponseRecorder response = new ResponseRecorder();
        servlet.service(
                request("POST", "/api/admin/shutdown", null, "", regularSecurityContext()),
                response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.body.toString()).isEmpty();
        assertThat(shutdownLifecycle.shutdownLifecycleStarted).isFalse();
    }

    private static OrionHttpRouteServlet servlet(
            RecordingAccessControlService accessControlService,
            RecordingGitRepositoryProvider gitRepositoryProvider) {
        return servlet(accessControlService, gitRepositoryProvider, new AcmeHttpChallengeService());
    }

    private static OrionHttpRouteServlet servlet(
            RecordingAccessControlService accessControlService,
            RecordingGitRepositoryProvider gitRepositoryProvider,
            OrionShutdownLifecycle shutdownLifecycle) {
        return servlet(
                accessControlService,
                gitRepositoryProvider,
                new AcmeHttpChallengeService(),
                new RecordingAcmeCertificateService(),
                shutdownLifecycle);
    }

    private static OrionHttpRouteServlet servlet(
            RecordingAccessControlService accessControlService,
            RecordingGitRepositoryProvider gitRepositoryProvider,
            AcmeHttpChallengeService challengeService) {
        return servlet(
                accessControlService,
                gitRepositoryProvider,
                challengeService,
                new RecordingAcmeCertificateService(),
                new RecordingShutdownLifecycle());
    }

    private static OrionHttpRouteServlet servlet(
            RecordingAccessControlService accessControlService,
            RecordingGitRepositoryProvider gitRepositoryProvider,
            AcmeHttpChallengeService challengeService,
            AcmeCertificateService acmeCertificateService) {
        return servlet(accessControlService, gitRepositoryProvider, challengeService, acmeCertificateService, new RecordingShutdownLifecycle());
    }

    private static OrionHttpRouteServlet servlet(
            RecordingAccessControlService accessControlService,
            RecordingGitRepositoryProvider gitRepositoryProvider,
            AcmeHttpChallengeService challengeService,
            AcmeCertificateService acmeCertificateService,
            OrionShutdownLifecycle shutdownLifecycle) {
        Set<OrionHttpRoute> routes = new LinkedHashSet<>();
        routes.add(new AcmeHttpChallengeRoute(challengeService));
        routes.add(new OrionGitRoute(gitRepositoryProvider));
        routes.add(new OrionAdminCreateOrUpdateUserRoute(accessControlService, OBJECT_MAPPER));
        routes.add(new OrionAdminCreateRepositoryRoute(gitRepositoryProvider, OBJECT_MAPPER));
        routes.add(new OrionAdminIssueTokenRoute(accessControlService, OBJECT_MAPPER));
        routes.add(new OrionAdminAccessControlRoute(accessControlService));
        routes.add(new OrionAdminRoutesRoute(() -> new OrionHttpRouteRegistry(routes)));
        routes.add(new OrionAdminShutdownRoute(shutdownLifecycle));
        routes.add(new OrionAdminAcmeCertificateRoute(acmeCertificateService, OBJECT_MAPPER));
        routes.add(new OrionConfigurationSchemaRoute(new OrionConfigurationJsonSchema()));
        routes.add(new OrionAccessControlSchemaRoute());
        return new OrionHttpRouteServlet(new OrionHttpRouteRegistry(routes), new OrionHttpResponseWriter(OBJECT_MAPPER));
    }

    private static JsonNode routeWithPattern(JsonNode routes, String pattern) {
        for (JsonNode route : routes) {
            if (pattern.equals(route.get("urlPattern").asText())) {
                return route;
            }
        }
        throw new AssertionError("Route not found: " + pattern);
    }

    private static JsonNode schemaAt(JsonNode schema, String... path) {
        JsonNode current = schema;
        for (String segment : path) {
            current = current.get("properties").get(segment);
        }
        return current;
    }

    private static String basicAuth(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private static HttpServletRequest request(String method, String pathInfo, String authorization, String body) {
        return request(method, pathInfo, authorization, body, adminSecurityContext());
    }

    private static HttpServletRequest request(
            String method,
            String pathInfo,
            String authorization,
            String body,
            SecurityContext securityContext) {
        return stub(HttpServletRequest.class, (proxy, invokedMethod, args) -> switch (invokedMethod.getName()) {
            case "getMethod" -> method;
            case "getPathInfo" -> pathInfo;
            case "getHeader" -> switch ((String) args[0]) {
                case "Authorization" -> authorization;
                default -> null;
            };
            case "getAttribute" -> {
                if (OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE.equals(args[0])) {
                    yield securityContext;
                }
                yield null;
            }
            case "getInputStream" -> new ByteArrayServletInputStream(body.getBytes(StandardCharsets.UTF_8));
            case "toString" -> "HttpServletRequest[pathInfo=" + pathInfo + "]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(invokedMethod.toString());
        });
    }

    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static SecurityContext adminSecurityContext() {
        AccessControl.Grant adminGrant = new AccessControl.Grant("admin", new ArrayList<>())
                .addKey(AccessControl.GrantKey.ADMIN, AccessControl.TRUE_STRING);
        return SecurityContext.createContext().withUserIdentity(new InternalUserImpl("admin", List.of(adminGrant)));
    }

    private static SecurityContext regularSecurityContext() {
        return SecurityContext.createContext().withUserIdentity(new InternalUserImpl("regular", List.of()));
    }

    private static final class RecordingAccessControlService implements OrionAccessControlService {
        private AccessControlUserUpdate lastUpdate;
        private String lastTokenUserName;
        private byte[] lastTokenCredential;
        private long lastTokenExpiresInSeconds;
        private byte[] lastAccessControlConfiguration;

        @Override
        public void addKeyToUser(String username, String publicKey) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void createOrUpdateUser(AccessControlUserUpdate userUpdate) {
            lastUpdate = userUpdate;
        }

        @Override
        public AuthenticationResult authenticateUser(String userName, byte[] credential) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AuthenticationResult authenticateToken(byte[] token) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TokenIssueResult authenticateUserAndIssueToken(String userName, byte[] credential, long expiresInSeconds) {
            lastTokenUserName = userName;
            lastTokenCredential = credential;
            lastTokenExpiresInSeconds = expiresInSeconds;
            if (!BASIC_USER.equals(userName)
                    || !BASIC_PASSWORD.equals(new String(credential, StandardCharsets.UTF_8))) {
                return TokenIssueResult.failure("authentication failed");
            }
            return TokenIssueResult.success(ISSUED_TOKEN, TOKEN_EXPIRES_AT);
        }

        @Override
        public TokenIssueResult issueTokenFor(UserIdentity userIdentity, long expiresInSeconds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] accessControlConfigurationFile() {
            return ACL_CONFIGURATION.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void saveAccessControlConfigurationFile(byte[] content) {
            lastAccessControlConfiguration = content;
        }
    }

    private static final class RecordingGitRepositoryProvider implements GitRepositoryProvider {
        private String lastCreatedRepository;

        @Override
        public boolean exists(String repositoryName) {
            return false;
        }

        @Override
        public Result<GitRepository> find(String repositoryName) {
            return new Result.Failure<>(Result.FailureCode.NOT_FOUND);
        }

        @Override
        public Result<GitRepository> findOrCreate(String repositoryName) {
            lastCreatedRepository = repositoryName;
            return new Result.Success<>(null);
        }
    }

    private static final class RecordingAcmeCertificateService extends AcmeCertificateService {
        private AcmeCertificateService.IssueRequest lastIssueRequest;
        private String savedCertificate;

        private RecordingAcmeCertificateService() {
            super(new OrionConfiguration(), new AcmeCertificateIssuer(new AcmeHttpChallengeService()));
        }

        @Override
        public IssuedAcmeCertificate issue(AcmeCertificateService.IssueRequest request) {
            lastIssueRequest = request;
            List<String> domains = request.domains() == null ? List.of("example.test") : request.domains();
            return new IssuedAcmeCertificate(domains, ACME_CERTIFICATE_PEM, ACME_PRIVATE_KEY_PEM);
        }

        @Override
        public java.util.Optional<String> savedNginxCertificate() {
            return java.util.Optional.ofNullable(savedCertificate);
        }
    }

    private static final class RecordingShutdownLifecycle implements OrionShutdownLifecycle {
        private boolean shutdownLifecycleStarted;

        @Override
        public void beginShutdownLifecycle() {
            shutdownLifecycleStarted = true;
        }
    }

    private static final class ResponseRecorder {
        private int status;
        private String contentType;
        private boolean flushed;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final StringWriter body = new StringWriter();

        private HttpServletResponse proxy() {
            return stub(HttpServletResponse.class, (proxy, method, args) -> switch (method.getName()) {
                case "setStatus" -> {
                    status = (int) args[0];
                    yield null;
                }
                case "sendError" -> {
                    status = (int) args[0];
                    yield null;
                }
                case "setHeader" -> {
                    headers.put((String) args[0], (String) args[1]);
                    yield null;
                }
                case "setContentType" -> {
                    contentType = (String) args[0];
                    yield null;
                }
                case "getWriter" -> new PrintWriter(body);
                case "flushBuffer" -> {
                    flushed = true;
                    yield null;
                }
                case "toString" -> "HttpServletResponseRecorder";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new UnsupportedOperationException(method.toString());
            });
        }
    }

    private static final class ByteArrayServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream input;

        private ByteArrayServletInputStream(byte[] data) {
            input = new ByteArrayInputStream(data);
        }

        @Override
        public int read() throws IOException {
            return input.read();
        }

        @Override
        public boolean isFinished() {
            return input.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
        }
    }
}
