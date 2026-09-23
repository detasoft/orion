package pro.deta.orion.transport.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;

public final class OrionHttpExchange {
    private final HttpServletRequest request;
    private final String path;
    private final HttpServletResponse response;
    private final OrionHttpResponseWriter responseWriter;

    OrionHttpExchange(
            HttpServletRequest request,
            HttpServletResponse response,
            OrionHttpResponseWriter responseWriter) {
        this.request = request;
        this.path = routePath(request);
        this.response = response;
        this.responseWriter = responseWriter;
    }

    public String path() {
        return path;
    }

    static String routePath(HttpServletRequest request) {
        String path = request.getPathInfo();
        if (path != null && !path.isBlank()) {
            return path;
        }
        path = request.getRequestURI();
        String context = request.getContextPath();
        if (path != null && context != null && !context.isEmpty()
                && (path.equals(context) || path.startsWith(context + "/"))) {
            path = path.substring(context.length());
        }
        return path == null || path.isBlank() ? "/" : path;
    }

    public HttpServletRequest request() {
        return request;
    }

    HttpServletResponse servletResponse() {
        return response;
    }

    public OrionHttpRouteDefinition.Method method() {
        return OrionHttpRouteDefinition.Method.from(request.getMethod()).orElseThrow();
    }

    boolean accepts(OrionHttpRouteDefinition definition) throws IOException {
        if (!definition.authorization().allows(request)) {
            sendError(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        Optional<OrionHttpRouteDefinition.Method> method =
                OrionHttpRouteDefinition.Method.from(request.getMethod());
        if (method.isPresent() && definition.methods().contains(method.get())) {
            return true;
        }
        response.setHeader("Allow", OrionHttpRouteDefinition.allowHeader(definition.methods()));
        for (Map.Entry<String, String> header : definition.methodRejectionHeaders().entrySet()) {
            response.setHeader(header.getKey(), header.getValue());
        }
        response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
        return false;
    }

    public void send(OrionHttpResponse bufferedResponse) throws IOException {
        responseWriter.write(response, bufferedResponse, "HEAD".equalsIgnoreCase(request.getMethod()));
    }

    public OutputStream openResponseBody(OrionHttpResponse responseMetadata) throws IOException {
        if (responseMetadata.body() != null) {
            throw new IllegalArgumentException("Streaming response metadata cannot have a body");
        }
        responseWriter.write(response, responseMetadata, true);
        if ("HEAD".equalsIgnoreCase(request.getMethod())) {
            return OutputStream.nullOutputStream();
        }
        return response.getOutputStream();
    }

    public void sendError(int status) throws IOException {
        response.sendError(status);
    }

    public void sendError(int status, String message) throws IOException {
        response.sendError(status, message);
    }

    void sendError(int status, Map<String, String> headers) throws IOException {
        for (Map.Entry<String, String> header : headers.entrySet()) {
            response.setHeader(header.getKey(), header.getValue());
        }
        response.sendError(status);
    }

    public void sendAfterFlush(OrionHttpResponse bufferedResponse, Runnable action) throws IOException {
        send(bufferedResponse);
        response.flushBuffer();
        Objects.requireNonNull(action, "action").run();
    }
}
