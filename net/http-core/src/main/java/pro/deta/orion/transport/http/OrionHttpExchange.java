package pro.deta.orion.transport.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.Objects;

public final class OrionHttpExchange {
    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final OrionHttpResponseWriter responseWriter;
    private final OrionHttpRouteDefinition.Method method;

    OrionHttpExchange(
            HttpServletRequest request,
            HttpServletResponse response,
            OrionHttpResponseWriter responseWriter,
            OrionHttpRouteDefinition.Method method) {
        this.request = request;
        this.response = response;
        this.responseWriter = responseWriter;
        this.method = method;
    }

    public HttpServletRequest request() {
        return request;
    }

    HttpServletResponse servletResponse() {
        return response;
    }

    public OrionHttpRouteDefinition.Method method() {
        return method;
    }

    public void send(OrionHttpResponse bufferedResponse) throws IOException {
        responseWriter.write(response, bufferedResponse, method == OrionHttpRouteDefinition.Method.HEAD);
    }

    public OutputStream openResponseBody(OrionHttpResponse responseMetadata) throws IOException {
        if (responseMetadata.body() != null) {
            throw new IllegalArgumentException("Streaming response metadata cannot have a body");
        }
        responseWriter.write(response, responseMetadata, true);
        if (method == OrionHttpRouteDefinition.Method.HEAD) {
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
