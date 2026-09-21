package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.config.OrionDesiredState;
import pro.deta.orion.keymaterial.AcmeKeyMaterialCapability;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.ACLUtil;
import pro.deta.orion.schema.config.OrionConfiguration;
import pro.deta.orion.schema.orion.OrionDocument;
import java.util.List;
import java.util.Optional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Authorization.AUTHENTICATED;
import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Method.GET;

class OrionHttpRouteServletRoutingTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void matchesRouteByUrlPattern() throws Exception {
        OrionHttpRouteServlet servlet = servlet(new TestRoute("/api/items/*", "pattern"));
        ResponseRecorder response = new ResponseRecorder();

        servlet.service(request("GET", "/api/items/42"), response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType).isEqualTo("application/json");
        assertThat(response.body.toString()).isEqualTo("{\"route\":\"pattern\"}");
    }

    @Test
    void prefersExactRouteOverUrlPattern() throws Exception {
        OrionHttpRouteServlet servlet = servlet(
                new TestRoute("/api/items/*", "pattern"),
                new TestRoute("/api/items/42", "exact"));
        ResponseRecorder response = new ResponseRecorder();

        servlet.service(request("GET", "/api/items/42"), response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.body.toString()).isEqualTo("{\"route\":\"exact\"}");
    }

    @Test
    void prefersMoreSpecificUrlPattern() throws Exception {
        OrionHttpRouteServlet servlet = servlet(
                new TestRoute("/api/items/**", "base"),
                new TestRoute("/api/items/*/details", "details"));
        ResponseRecorder response = new ResponseRecorder();

        servlet.service(request("GET", "/api/items/42/details"), response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.body.toString()).isEqualTo("{\"route\":\"details\"}");
    }

    @Test
    void distinguishesNamesChildrenAndDescendants() throws Exception {
        OrionHttpRouteServlet servlet = servlet(
                new TestRoute("/team*", "name"),
                new TestRoute("/team/*", "child"),
                new TestRoute("/team/**", "descendant"));
        for (String[] example : new String[][]{
                {"/teamone", "name"},
                {"/team/one", "child"},
                {"/team/one/api", "descendant"}}) {
            ResponseRecorder response = new ResponseRecorder();
            servlet.service(request("GET", example[0]), response.proxy());
            assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
            assertThat(response.body.toString()).isEqualTo("{\"route\":\"" + example[1] + "\"}");
        }
    }

    @Test
    void downloadPrefixDoesNotCaptureSimilarlyNamedFrontendPaths() {
        SessionHostDownloadRoute download = new SessionHostDownloadRoute();
        OrionFrontendRoute frontend = new OrionFrontendRoute();
        OrionHttpRouteRegistry registry = new OrionHttpRouteRegistry(Set.of(download, frontend));
        assertThat(registry.routeFor("/session-host")).isSameAs(download);
        assertThat(registry.routeFor("/session-host/")).isSameAs(download);
        assertThat(registry.routeFor("/session-host/x86_64-unknown-linux-gnu")).isSameAs(download);
        assertThat(registry.routeFor("/session-hostile")).isSameAs(frontend);
        assertThat(registry.routeFor("/assets/nested/app.js")).isSameAs(frontend);
    }

    @Test
    void returnsMethodNotAllowedFromRouteService() throws Exception {
        OrionHttpRouteServlet servlet = servlet(new TestRoute("/api/items/*", "pattern"));
        ResponseRecorder response = new ResponseRecorder();

        servlet.service(request("POST", "/api/items/42"), response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        assertThat(response.headers).containsEntry("Allow", "GET");
        assertThat(response.body.toString()).isEmpty();
    }

    @Test
    void returnsNotFoundForUnknownRoute() throws Exception {
        OrionHttpRouteServlet servlet = servlet(new TestRoute("/api/items/*", "pattern"));
        ResponseRecorder response = new ResponseRecorder();

        servlet.service(request("GET", "/api/unknown"), response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
        assertThat(response.body.toString()).isEmpty();
    }

    @Test
    void rejectsRequestUsingTheRouteDefinitionAuthorizationPolicy() throws Exception {
        OrionHttpRouteServlet servlet = servlet(new DeniedRoute("/api/items/*"));
        ResponseRecorder response = new ResponseRecorder();

        servlet.service(request("GET", "/api/items/42"), response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.body.toString()).isEmpty();
    }

    @Test
    void hidesUnexpectedStateFailureBehindServerError() throws Exception {
        ResponseRecorder response = new ResponseRecorder();
        servlet(failingRoute(new IllegalStateException("private backend details")))
                .service(request("GET", "/failure"), response.proxy());
        assertThat(response.status).isEqualTo(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        assertThat(response.errorMessage).isEqualTo("Internal server error");
        assertThat(response.body.toString()).doesNotContain("private backend details");
    }

    @Test
    void preservesClientValidationErrors() throws Exception {
        ResponseRecorder response = new ResponseRecorder();
        servlet(failingRoute(new IllegalArgumentException("Missing repository name")))
                .service(request("GET", "/failure"), response.proxy());
        assertThat(response.status).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        assertThat(response.errorMessage).isEqualTo("Missing repository name");
    }

    @Test
    void doesNotReplaceAlreadyCommittedResponseAfterStateFailure() {
        ResponseRecorder response = new ResponseRecorder();
        response.status = HttpServletResponse.SC_OK;
        response.committed = true;
        IllegalStateException failure = new IllegalStateException("private backend details");
        assertThatThrownBy(() -> servlet(failingRoute(failure))
                .service(request("GET", "/failure"), response.proxy()))
                .isInstanceOf(ServletException.class).hasCause(failure);
        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.errorMessage).isNull();
    }

    @Test
    void missingAcmeConfigurationRemainsClientErrorForReadAndIssue() throws Exception {
        OrionDesiredState desired = new OrionDesiredState();
        desired.publish(OrionDocument.withAccessControl(new AccessControl()), Optional.empty());
        OrionHttpRoute route = acmeRoute(desired);
        for (String method : List.of("GET", "POST")) {
            ResponseRecorder response = new ResponseRecorder();
            servlet(route).service(request(method, OrionAdminPaths.ACME_CERTIFICATE, admin()), response.proxy());
            assertThat(response.status).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
            assertThat(response.errorMessage).isEqualTo("HTTPS desired state is not configured");
        }
    }

    @Test
    void unpublishedServerStateIsNotMisreportedAsMissingAcmeConfiguration() throws Exception {
        OrionHttpRoute route = acmeRoute(new OrionDesiredState());
        for (String method : List.of("GET", "POST")) {
            ResponseRecorder response = new ResponseRecorder();
            servlet(route).service(request(method, OrionAdminPaths.ACME_CERTIFICATE, admin()), response.proxy());
            assertThat(response.status).isEqualTo(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            assertThat(response.errorMessage).isEqualTo("Internal server error");
        }
    }

    private static OrionHttpRoute acmeRoute(OrionDesiredState desired) {
        return new OrionAdminAcmeCertificateRoute(new AcmeCertificateService(new OrionConfiguration(), desired,
                AcmeKeyMaterialCapability.unavailable(), new AcmeCertificateIssuer(null)), OBJECT_MAPPER);
    }

    private static SecurityContext admin() {
        return SecurityContext.createContext().withUserIdentity(new InternalUserImpl("admin",
                ACLUtil.generateDefaultAccessControl("unused-test-hash").getGrants()));
    }

    private static OrionHttpRoute failingRoute(RuntimeException failure) {
        return new AbstractOrionHttpRoute("/failure", GET) {
            @Override
            protected OrionHttpResponse doGet(HttpServletRequest request) {
                throw failure;
            }
        };
    }

    private static OrionHttpRouteServlet servlet(OrionHttpRoute... routes) {
        return new OrionHttpRouteServlet(
                new OrionHttpRouteRegistry(Set.of(routes)),
                new OrionHttpResponseWriter(OBJECT_MAPPER));
    }

    private static HttpServletRequest request(String method, String pathInfo) {
        return request(method, pathInfo, null);
    }

    private static HttpServletRequest request(String method, String pathInfo, SecurityContext context) {
        return stub(HttpServletRequest.class, (proxy, invokedMethod, args) -> switch (invokedMethod.getName()) {
            case "getMethod" -> method;
            case "getPathInfo" -> pathInfo;
            case "getInputStream" -> new ByteArrayServletInputStream(new byte[0]);
            case "getAttribute" -> OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE.equals(args[0]) ? context : null;
            case "toString" -> "HttpServletRequest[pathInfo=" + pathInfo + "]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(invokedMethod.toString());
        });
    }

    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static final class TestRoute extends AbstractOrionHttpRoute {
        private final String name;

        private TestRoute(String urlPattern, String name) {
            super(urlPattern, GET);
            this.name = name;
        }

        @Override
        protected OrionHttpResponse doGet(HttpServletRequest req) {
            return OrionHttpResponse.ok(Map.of("route", name));
        }
    }

    private static final class DeniedRoute extends AbstractOrionHttpRoute {
        private DeniedRoute(String urlPattern) {
            super(urlPattern, AUTHENTICATED, GET);
        }

        @Override
        protected OrionHttpResponse doGet(HttpServletRequest req) {
            return OrionHttpResponse.ok(Map.of("route", "denied"));
        }
    }

    private static final class ResponseRecorder {
        private int status;
        private String errorMessage;
        private boolean committed;
        private String contentType;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final StringWriter body = new StringWriter();

        private HttpServletResponse proxy() {
            return stub(HttpServletResponse.class, (proxy, method, args) -> switch (method.getName()) {
                case "setStatus" -> {
                    status = (int) args[0];
                    yield null;
                }
                case "isCommitted" -> committed;
                case "sendError" -> {
                    assertThat(committed).isFalse();
                    errorMessage = args.length > 1 ? (String) args[1] : null;
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
