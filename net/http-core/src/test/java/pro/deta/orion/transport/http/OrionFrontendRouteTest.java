package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class OrionFrontendRouteTest {
    private static final Pattern SCRIPT_PATH = Pattern.compile("src=\"(/assets/[^\"]+\\.js)\"");
    private final OrionFrontendRoute route = new OrionFrontendRoute();

    @Test
    void servesPackagedFrontendAtUiRoot() throws Exception {
        ResponseRecorder response = handle("GET", "/");

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType).isEqualTo("text/html; charset=utf-8");
        assertThat(response.headers).containsEntry("Cache-Control", "no-cache");
        assertThat(response.bodyAsString()).contains("<div id=\"app\"></div>", "/assets/");
        assertThat(response.contentLength).isEqualTo(response.body.size());
    }

    @Test
    void servesBuiltAssetsWithImmutableCaching() throws Exception {
        ResponseRecorder index = handle("GET", "/");
        Matcher script = SCRIPT_PATH.matcher(index.bodyAsString());

        assertThat(script.find()).isTrue();
        ResponseRecorder response = handle("GET", script.group(1));

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType).isEqualTo("application/javascript; charset=utf-8");
        assertThat(response.headers)
                .containsEntry("Cache-Control", "public, max-age=31536000, immutable");
        assertThat(response.body.size()).isGreaterThan(1_000);
    }

    @Test
    void rejectsMissingAndUnsafeResources() throws Exception {
        ResponseRecorder missing = handle("GET", "/assets/missing.js");
        ResponseRecorder unsafe = handle("GET", "/../config.yml");

        assertThat(missing.status).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
        assertThat(unsafe.status).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
    }

    @Test
    void rejectsUnsupportedMethods() throws Exception {
        ResponseRecorder response = handle("POST", "/");

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        assertThat(response.headers).containsEntry("Allow", "GET, HEAD");
    }

    @Test
    void servesHeadForFrontendResources() throws Exception {
        ResponseRecorder response = handle("HEAD", "/");

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType).isEqualTo("text/html; charset=utf-8");
        assertThat(response.contentLength).isGreaterThan(0);
        assertThat(response.body.size()).isZero();
    }

    private ResponseRecorder handle(String method, String path) throws Exception {
        ResponseRecorder response = new ResponseRecorder();
        route.handle(
                request(method, path),
                response.proxy(),
                new OrionHttpResponseWriter(new ObjectMapper()));
        return response;
    }

    private static HttpServletRequest request(String methodName, String path) {
        return stub(HttpServletRequest.class, (proxy, method, args) -> switch (method.getName()) {
            case "getMethod" -> methodName;
            case "getPathInfo", "getRequestURI" -> path;
            case "toString" -> "HttpServletRequest[pathInfo=" + path + "]";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new UnsupportedOperationException(method.toString());
        });
    }

    private static <T> T stub(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static final class ResponseRecorder {
        private int status;
        private int contentLength;
        private String contentType;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

        private HttpServletResponse proxy() {
            return stub(HttpServletResponse.class, (proxy, method, args) -> switch (method.getName()) {
                case "setStatus" -> {
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
                case "getOutputStream" -> new RecordingServletOutputStream(body);
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
        public void write(int value) throws IOException {
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
