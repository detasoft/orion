package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionHostDownloadRouteTest {
    private final SessionHostDownloadRoute route = new SessionHostDownloadRoute(getClass().getClassLoader());

    @Test
    void listsAvailableSessionHostDownloads() throws Exception {
        ResponseRecorder response = handle("/session-host", null);

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType).isEqualTo(OrionHttpResponse.JSON_CONTENT_TYPE);
        assertThat(response.headers).containsEntry("Vary", "Accept");
        assertThat(response.bodyAsString()).contains(
                "aarch64-apple-darwin",
                "x86_64-unknown-linux-gnu",
                "/session-host/aarch64-apple-darwin?filename=session-host-aarch64-apple-darwin");
    }

    @Test
    void listsAvailableSessionHostDownloadsWithTrailingSlash() throws Exception {
        ResponseRecorder response = handle("/session-host/", null);

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.bodyAsString()).contains("x86_64-unknown-linux-gnu");
    }

    @Test
    void rendersDownloadLinksForBrowsers() throws Exception {
        ResponseRecorder response = handle("/session-host/", null, "text/html,application/xhtml+xml");

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType).isEqualTo("text/html; charset=utf-8");
        assertThat(response.headers).containsEntry("Vary", "Accept");
        assertThat(response.bodyAsString()).contains(
                "<h1>Available session hosts</h1>",
                "filename=session-host-x86_64-unknown-linux-gnu",
                "href=\"/session-host/x86_64-unknown-linux-gnu"
                        + "?filename=session-host-x86_64-unknown-linux-gnu\"");
    }

    @Test
    void doesNotRenderHtmlWhenClientRejectsIt() throws Exception {
        ResponseRecorder response = handle("/session-host/", null, "TEXT/HTML;q=0, application/json");

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType).isEqualTo(OrionHttpResponse.JSON_CONTENT_TYPE);
        assertThat(response.headers).containsEntry("Vary", "Accept");
    }

    @Test
    void selectsDownloadFromUnameSmOutput() throws Exception {
        ResponseRecorder response = handle("/session-host", "Linux x86_64");

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.contentType).isEqualTo(SessionHostDownloadRoute.CONTENT_TYPE);
        assertThat(response.headers).containsEntry(
                "Content-Disposition", "attachment; filename=\"session-host\"");
        assertThat(response.bodyAsString()).isEqualTo("linux session-host fixture\n");
    }

    @Test
    void downloadsExplicitAvailableTarget() throws Exception {
        ResponseRecorder response = handle("/session-host/aarch64-apple-darwin", null);

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.bodyAsString()).isEqualTo("macos session-host fixture\n");
        assertThat(response.headers).containsEntry(
                "Content-Disposition", "attachment; filename=\"session-host-aarch64-apple-darwin\"");
    }

    @Test
    void namesWindowsDownloadWithExeExtension() throws Exception {
        ResponseRecorder response = handle("/session-host/x86_64-pc-windows-msvc", null);

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.headers).containsEntry(
                "Content-Disposition", "attachment; filename=\"session-host-x86_64-pc-windows-msvc.exe\"");
    }

    @Test
    void usesRequestedDownloadFileName() throws Exception {
        ResponseRecorder response = handle(
                "/session-host/x86_64-unknown-linux-gnu",
                null,
                null,
                "my-session-host");

        assertThat(response.status).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.headers).containsEntry(
                "Content-Disposition", "attachment; filename=\"my-session-host\"");
    }

    @Test
    void rejectsUnsupportedUnameAndTarget() throws Exception {
        ResponseRecorder unknownUname = handle("/session-host", "FreeBSD host amd64");
        ResponseRecorder unknownTarget = handle("/session-host/../../secret", null);

        assertThat(unknownUname.status).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
        assertThat(unknownTarget.status).isEqualTo(HttpServletResponse.SC_NOT_FOUND);
    }

    private ResponseRecorder handle(String path, String uname) throws Exception {
        return handle(path, uname, null, null);
    }

    private ResponseRecorder handle(String path, String uname, String accept) throws Exception {
        return handle(path, uname, accept, null);
    }

    private ResponseRecorder handle(
            String path,
            String uname,
            String accept,
            String fileName) throws Exception {
        ResponseRecorder response = new ResponseRecorder();
        route.handle(
                request(path, uname, accept, fileName),
                response.proxy(),
                new OrionHttpResponseWriter(new ObjectMapper()));
        return response;
    }

    private static HttpServletRequest request(String path, String uname, String accept, String fileName) {
        return stub(HttpServletRequest.class, (proxy, method, args) -> switch (method.getName()) {
            case "getMethod" -> "GET";
            case "getPathInfo" -> path;
            case "getParameter" -> switch ((String) args[0]) {
                case "uname" -> uname;
                case "filename" -> fileName;
                default -> null;
            };
            case "getHeader" -> "Accept".equals(args[0]) ? accept : null;
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
        private String contentType;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();

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
                case "getOutputStream" -> new RecordingServletOutputStream(body);
                case "getWriter" -> new PrintWriter(new OutputStreamWriter(body, StandardCharsets.UTF_8), true);
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
