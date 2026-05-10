package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import pro.deta.orion.auth.check.OrionSecurityException;

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

class OrionHttpRouteDispatcherTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void matchesRouteByUrlPattern() throws Exception {
        OrionHttpRouteDispatcher dispatcher = new OrionHttpRouteDispatcher(
                new OrionHttpRouteRegistry(Set.of(new TestRoute("/api/items/*", "pattern"))),
                OBJECT_MAPPER);
        ResponseRecorder response = new ResponseRecorder();

        dispatcher.handle(request("GET", "/api/items/42"), response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType).isEqualTo("application/json");
        assertThat(response.body.toString()).isEqualTo("{\"route\":\"pattern\"}");
    }

    @Test
    void prefersExactRouteOverUrlPattern() throws Exception {
        OrionHttpRouteDispatcher dispatcher = new OrionHttpRouteDispatcher(
                new OrionHttpRouteRegistry(Set.of(
                        new TestRoute("/api/items/*", "pattern"),
                        new TestRoute("/api/items/42", "exact"))),
                OBJECT_MAPPER);
        ResponseRecorder response = new ResponseRecorder();

        dispatcher.handle(request("GET", "/api/items/42"), response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.body.toString()).isEqualTo("{\"route\":\"exact\"}");
    }

    @Test
    void prefersMoreSpecificUrlPattern() throws Exception {
        OrionHttpRouteDispatcher dispatcher = new OrionHttpRouteDispatcher(
                new OrionHttpRouteRegistry(Set.of(
                        new TestRoute("/api/items/*", "base"),
                        new TestRoute("/api/items/*/details", "details"))),
                OBJECT_MAPPER);
        ResponseRecorder response = new ResponseRecorder();

        dispatcher.handle(request("GET", "/api/items/42/details"), response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.body.toString()).isEqualTo("{\"route\":\"details\"}");
    }

    @Test
    void returnsMethodNotAllowedFromRouteService() throws Exception {
        OrionHttpRouteDispatcher dispatcher = new OrionHttpRouteDispatcher(
                new OrionHttpRouteRegistry(Set.of(new TestRoute("/api/items/*", "pattern"))),
                OBJECT_MAPPER);
        ResponseRecorder response = new ResponseRecorder();

        dispatcher.handle(request("POST", "/api/items/42"), response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        assertThat(response.headers).containsEntry("Allow", "GET");
        assertThat(response.body.toString()).isEmpty();
    }

    @Test
    void letsRouteRejectRequestUsingItsOwnAuthorizationLogic() throws Exception {
        OrionHttpRouteDispatcher dispatcher = new OrionHttpRouteDispatcher(
                new OrionHttpRouteRegistry(Set.of(new DeniedRoute("/api/items/*"))),
                OBJECT_MAPPER);
        ResponseRecorder response = new ResponseRecorder();

        dispatcher.handle(request("GET", "/api/items/42"), response.proxy());

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.body.toString()).isEmpty();
    }

    private static HttpServletRequest request(String method, String pathInfo) {
        return stub(HttpServletRequest.class, (proxy, invokedMethod, args) -> switch (invokedMethod.getName()) {
            case "getMethod" -> method;
            case "getPathInfo" -> pathInfo;
            case "getInputStream" -> new ByteArrayServletInputStream(new byte[0]);
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
            super(urlPattern, "GET");
            this.name = name;
        }

        @Override
        protected OrionHttpResponse doGet(HttpServletRequest req) {
            return OrionHttpResponse.ok(Map.of("route", name));
        }
    }

    private static final class DeniedRoute extends AbstractOrionHttpRoute {
        private DeniedRoute(String urlPattern) {
            super(urlPattern, "GET");
        }

        @Override
        protected void authorize(HttpServletRequest req) throws OrionSecurityException {
            throw new OrionSecurityException("denied by route");
        }

        @Override
        protected OrionHttpResponse doGet(HttpServletRequest req) {
            return OrionHttpResponse.ok(Map.of("route", "denied"));
        }
    }

    private static final class ResponseRecorder {
        private int status;
        private String contentType;
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
