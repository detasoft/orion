package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import pro.deta.orion.auth.InternalUserImpl;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlDraft;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Authorization.ANONYMOUS;
import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Authorization.APPLICATION_ADMIN;
import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Authorization.AUTHENTICATED;
import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Method.GET;
import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Method.HEAD;
import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Method.POST;

class OrionHttpUnifiedRouteTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void sendsBufferedResponseThroughTheUnifiedExchange() throws Exception {
        UnifiedRoute route = route(
                new OrionHttpRouteDefinition("/buffered", ANONYMOUS, GET),
                exchange -> exchange.send(OrionHttpResponse.ok(Map.of("value", "buffered"))));

        ResponseRecorder response = service(route, request("GET", "/buffered", null));

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType).isEqualTo(OrionHttpResponse.JSON_CONTENT_TYPE);
        assertThat(response.bodyAsString()).isEqualTo("{\"value\":\"buffered\"}");
    }

    @Test
    void suppressesHeadBodyWhilePreservingBufferedMetadata() throws Exception {
        byte[] body = "resource-body".getBytes(StandardCharsets.UTF_8);
        UnifiedRoute route = route(
                new OrionHttpRouteDefinition("/resource", ANONYMOUS, GET, HEAD),
                exchange -> exchange.send(OrionHttpResponse
                        .resource(HttpServletResponse.SC_OK, body, "application/test")
                        .withHeader("ETag", "resource-tag")));

        ResponseRecorder response = service(route, request("HEAD", "/resource", null));

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType).isEqualTo("application/test");
        assertThat(response.contentLength).isEqualTo(body.length);
        assertThat(response.headers).containsEntry("ETag", "resource-tag");
        assertThat(response.body.size()).isZero();
    }

    @Test
    void streamsDirectlyThroughTheUnifiedExchange() throws Exception {
        AtomicBoolean opened = new AtomicBoolean();
        UnifiedRoute route = route(
                new OrionHttpRouteDefinition("/stream", ANONYMOUS, GET),
                exchange -> {
                    OutputStream output = exchange.openResponseBody(OrionHttpResponse
                            .stream(HttpServletResponse.SC_OK, "application/octet-stream"));
                    opened.set(true);
                    output.write("first".getBytes(StandardCharsets.UTF_8));
                    output.write("-second".getBytes(StandardCharsets.UTF_8));
                });

        ResponseRecorder response = service(route, request("GET", "/stream", null));

        assertThat(opened).isTrue();
        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.bodyAsString()).isEqualTo("first-second");
    }

    @Test
    void rejectsTheMethodFromTheDefinitionWithTheExactAllowHeader() throws Exception {
        AtomicBoolean invoked = new AtomicBoolean();
        OrionHttpRouteDefinition definition = new OrionHttpRouteDefinition(
                "/git/*",
                AUTHENTICATED,
                List.of(GET, HEAD, POST),
                request -> request.getPathInfo().endsWith("/info/refs")
                        ? List.of(GET, HEAD)
                        : List.of(POST),
                Map.of());
        UnifiedRoute route = route(definition, exchange -> invoked.set(true));

        ResponseRecorder response = service(
                route,
                request("POST", "/git/project/info/refs", authenticatedContext()));

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        assertThat(response.headers).containsEntry("Allow", "GET, HEAD");
        assertThat(invoked).isFalse();
    }

    @Test
    void enforcesAnonymousAuthenticatedAndApplicationAdminPolicies() throws Exception {
        assertAllowed(ANONYMOUS, null);
        assertDenied(AUTHENTICATED, null);
        assertAllowed(AUTHENTICATED, authenticatedContext());
        assertDenied(APPLICATION_ADMIN, authenticatedContext());
        assertAllowed(APPLICATION_ADMIN, adminContext());
    }

    @Test
    void performsAnAfterFlushActionOnlyAfterTheResponseIsFlushed() throws Exception {
        AtomicBoolean actionObservedFlush = new AtomicBoolean();
        UnifiedRoute route = route(
                new OrionHttpRouteDefinition("/shutdown", APPLICATION_ADMIN, POST),
                exchange -> exchange.sendAfterFlush(
                        OrionHttpResponse.json(
                                HttpServletResponse.SC_ACCEPTED,
                                Map.of("status", "shutdown-requested")),
                        () -> actionObservedFlush.set(exchange.servletResponse().isCommitted())));

        ResponseRecorder response = service(route, request("POST", "/shutdown", adminContext()));

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_ACCEPTED);
        assertThat(response.flushed).isTrue();
        assertThat(actionObservedFlush).isTrue();
    }

    @Test
    void derivesRouteTableMetadataFromTheExecutedDefinition() {
        OrionHttpRouteDefinition definition = new OrionHttpRouteDefinition(
                "/defined",
                APPLICATION_ADMIN,
                GET,
                POST);
        UnifiedRoute route = route(definition, exchange -> {
        });

        OrionHttpRouteRegistry.RouteDescriptor descriptor =
                new OrionHttpRouteRegistry(Set.of(route)).routeTable().getFirst();

        assertThat(descriptor.urlPattern()).isEqualTo("/defined");
        assertThat(descriptor.authorization()).isEqualTo("application-admin");
        assertThat(descriptor.methods()).containsExactly("GET", "POST");
    }

    private static void assertAllowed(
            OrionHttpRouteDefinition.Authorization authorization,
            SecurityContext context) throws Exception {
        AtomicBoolean invoked = new AtomicBoolean();
        UnifiedRoute route = route(
                new OrionHttpRouteDefinition("/policy", authorization, GET),
                exchange -> {
                    invoked.set(true);
                    exchange.send(OrionHttpResponse.empty(HttpServletResponse.SC_NO_CONTENT));
                });

        ResponseRecorder response = service(route, request("GET", "/policy", context));

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_NO_CONTENT);
        assertThat(invoked).isTrue();
    }

    private static void assertDenied(
            OrionHttpRouteDefinition.Authorization authorization,
            SecurityContext context) throws Exception {
        AtomicBoolean invoked = new AtomicBoolean();
        UnifiedRoute route = route(
                new OrionHttpRouteDefinition("/policy", authorization, GET),
                exchange -> invoked.set(true));

        ResponseRecorder response = service(route, request("GET", "/policy", context));

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(invoked).isFalse();
    }

    private static UnifiedRoute route(
            OrionHttpRouteDefinition definition,
            ExchangeHandler handler) {
        return new UnifiedRoute(definition, handler);
    }

    private static ResponseRecorder service(
            OrionHttpRoute route,
            HttpServletRequest request) throws Exception {
        ResponseRecorder response = new ResponseRecorder();
        OrionHttpRouteServlet servlet = new OrionHttpRouteServlet(
                new OrionHttpRouteRegistry(Set.of(route)),
                new OrionHttpResponseWriter(OBJECT_MAPPER));
        servlet.service(request, response.proxy());
        return response;
    }

    private static HttpServletRequest request(
            String method,
            String path,
            SecurityContext context) {
        return stub(HttpServletRequest.class, (proxy, invokedMethod, args) ->
                switch (invokedMethod.getName()) {
                    case "getMethod" -> method;
                    case "getPathInfo", "getRequestURI" -> path;
                    case "getAttribute" -> OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE.equals(args[0])
                            ? context
                            : null;
                    case "toString" -> "HttpServletRequest[pathInfo=" + path + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(invokedMethod.toString());
                });
    }

    private static SecurityContext authenticatedContext() {
        return SecurityContext.createContext()
                .withUserIdentity(new InternalUserImpl("user", List.of()));
    }

    private static SecurityContext adminContext() {
        AccessControl.Grant grant = new AccessControlDraft.Grant("admin", new ArrayList<>())
                .addKey(AccessControl.GrantKey.ADMIN, AccessControl.TRUE_STRING)
                .toAccessControl();
        return SecurityContext.createContext()
                .withUserIdentity(new InternalUserImpl("admin", List.of(grant)));
    }

    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                handler));
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(OrionHttpExchange exchange) throws IOException, ServletException;
    }

    private static final class UnifiedRoute implements OrionHttpRoute {
        private final OrionHttpRouteDefinition definition;
        private final ExchangeHandler handler;

        private UnifiedRoute(
                OrionHttpRouteDefinition definition,
                ExchangeHandler handler) {
            this.definition = definition;
            this.handler = handler;
        }

        @Override
        public OrionHttpRouteDefinition definition() {
            return definition;
        }

        @Override
        public void handle(OrionHttpExchange exchange) throws IOException, ServletException {
            handler.handle(exchange);
        }

    }

    private static final class ResponseRecorder {
        private int status;
        private int contentLength;
        private String contentType;
        private boolean flushed;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        private HttpServletResponse proxy() {
            return stub(HttpServletResponse.class, (proxy, method, args) ->
                    switch (method.getName()) {
                        case "setStatus", "sendError" -> {
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
                        case "setContentLength" -> {
                            contentLength = (int) args[0];
                            yield null;
                        }
                        case "setContentLengthLong" -> {
                            contentLength = Math.toIntExact((long) args[0]);
                            yield null;
                        }
                        case "getOutputStream" -> new RecordingServletOutputStream(body);
                        case "getWriter" -> new PrintWriter(
                                new OutputStreamWriter(body, StandardCharsets.UTF_8),
                                true);
                        case "flushBuffer" -> {
                            flushed = true;
                            yield null;
                        }
                        case "isCommitted" -> flushed;
                        case "toString" -> "HttpServletResponseRecorder";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.toString());
                    });
        }

        private String bodyAsString() {
            return body.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class RecordingServletOutputStream extends ServletOutputStream {
        private final ByteArrayOutputStream output;

        private RecordingServletOutputStream(ByteArrayOutputStream output) {
            this.output = output;
        }

        @Override
        public void write(int value) {
            output.write(value);
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
        }
    }
}
