package pro.deta.orion.transport.http;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

class DispatcherServletTest {
    private DispatcherServlet dispatcherServlet;
    private HttpServletResponse response;
    private RecordingServlet exactMatchServlet;
    private RecordingServlet wildcardMatchServlet;

    @BeforeEach
    void setUp() {
        dispatcherServlet = new DispatcherServlet();
        response = stub(HttpServletResponse.class);
        exactMatchServlet = new RecordingServlet();
        wildcardMatchServlet = new RecordingServlet();
    }

    @Test
    void testExactMatch() throws ServletException, IOException {
        HttpServletRequest request = requestWithPath("/api/users");
        dispatcherServlet.addServlet("/api/users", exactMatchServlet);

        dispatcherServlet.service(request, response);

        assertThat(exactMatchServlet.calls).isEqualTo(1);
        assertThat(exactMatchServlet.lastRequest).isSameAs(request);
        assertThat(exactMatchServlet.lastResponse).isSameAs(response);
    }

    @Test
    void testWildcardMatch() throws ServletException, IOException {
        HttpServletRequest request = requestWithPath("/api/users/123");
        dispatcherServlet.addServlet("/api/users/*", wildcardMatchServlet);

        dispatcherServlet.service(request, response);

        assertThat(wildcardMatchServlet.calls).isEqualTo(1);
        assertThat(wildcardMatchServlet.lastRequest).isSameAs(request);
        assertThat(wildcardMatchServlet.lastResponse).isSameAs(response);
    }

    @Test
    void testExactMatchPrecedence() throws ServletException, IOException {
        HttpServletRequest request = requestWithPath("/api/users");
        dispatcherServlet.addServlet("/api/*", wildcardMatchServlet);
        dispatcherServlet.addServlet("/api/users", exactMatchServlet);

        dispatcherServlet.service(request, response);

        assertThat(exactMatchServlet.calls).isEqualTo(1);
        assertThat(wildcardMatchServlet.calls).isZero();
    }

    @Test
    void testMultipleWildcards() throws ServletException, IOException {
        HttpServletRequest request = requestWithPath("/api/users/123/details");
        dispatcherServlet.addServlet("/api/*/123/*", wildcardMatchServlet);

        dispatcherServlet.service(request, response);

        assertThat(wildcardMatchServlet.calls).isEqualTo(1);
        assertThat(wildcardMatchServlet.lastRequest).isSameAs(request);
        assertThat(wildcardMatchServlet.lastResponse).isSameAs(response);
    }

    private static HttpServletRequest requestWithPath(String pathInfo) {
        return stub(HttpServletRequest.class, (proxy, method, args) -> switch (method.getName()) {
            case "getPathInfo" -> pathInfo;
            case "toString" -> "HttpServletRequest[pathInfo=" + pathInfo + "]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.toString());
        });
    }

    private static <T> T stub(Class<T> type) {
        return stub(type, (proxy, method, args) -> switch (method.getName()) {
            case "toString" -> type.getSimpleName() + "Stub";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.toString());
        });
    }

    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static final class RecordingServlet implements OrionServlet {
        private int calls;
        private HttpServletRequest lastRequest;
        private HttpServletResponse lastResponse;

        @Override
        public void service(HttpServletRequest req, HttpServletResponse resp) {
            calls++;
            lastRequest = req;
            lastResponse = resp;
        }
    }
}
