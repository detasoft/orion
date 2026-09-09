package pro.deta.orion.transport.http;

import java.util.LinkedHashMap;
import java.util.Map;

import static jakarta.servlet.http.HttpServletResponse.SC_CREATED;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

public final class OrionHttpResponse {
    public static final String JSON_CONTENT_TYPE = "application/json";
    public static final String JSON_SCHEMA_CONTENT_TYPE = "application/schema+json";
    public static final String TEXT_CONTENT_TYPE = "text/plain";
    public static final String XML_CONTENT_TYPE = "application/xml";
    public static final String PEM_CONTENT_TYPE = "application/x-pem-file";
    public static final String HTML_CONTENT_TYPE = "text/html; charset=utf-8";

    private final int status;
    private final Object body;
    private final String contentType;
    private final Map<String, String> headers;
    private final Long contentLength;

    private OrionHttpResponse(
            int status,
            Object body,
            String contentType,
            Map<String, String> headers,
            Long contentLength) {
        this.status = status;
        this.body = body;
        this.contentType = contentType;
        this.headers = Map.copyOf(headers);
        this.contentLength = contentLength;
    }

    public static OrionHttpResponse ok(Object body) {
        return json(SC_OK, body);
    }

    public static OrionHttpResponse created(Object body) {
        return json(SC_CREATED, body);
    }

    public static OrionHttpResponse empty(int status) {
        return new OrionHttpResponse(status, null, null, Map.of(), null);
    }

    public static OrionHttpResponse json(int status, Object body) {
        return new OrionHttpResponse(status, body, null, Map.of(), null);
    }

    public static OrionHttpResponse jsonSchema(int status, Object body) {
        return new OrionHttpResponse(status, body, JSON_SCHEMA_CONTENT_TYPE, Map.of(), null);
    }

    public static OrionHttpResponse text(int status, String body) {
        return new OrionHttpResponse(status, body, TEXT_CONTENT_TYPE, Map.of(), null);
    }

    public static OrionHttpResponse xml(int status, String body) {
        return new OrionHttpResponse(status, body, XML_CONTENT_TYPE, Map.of(), null);
    }

    public static OrionHttpResponse pem(int status, String body) {
        return new OrionHttpResponse(status, body, PEM_CONTENT_TYPE, Map.of(), null);
    }

    public static OrionHttpResponse resource(int status, byte[] body, String contentType) {
        return new OrionHttpResponse(status, body, contentType, Map.of(), null);
    }

    public static OrionHttpResponse html(int status, String body) {
        return new OrionHttpResponse(status, body, HTML_CONTENT_TYPE, Map.of(), null);
    }

    public static OrionHttpResponse stream(int status, String contentType) {
        return new OrionHttpResponse(status, null, contentType, Map.of(), null);
    }

    public OrionHttpResponse withHeader(String name, String value) {
        Map<String, String> nextHeaders = new LinkedHashMap<>(headers);
        nextHeaders.put(name, value);
        return new OrionHttpResponse(status, body, contentType, nextHeaders, contentLength);
    }

    public OrionHttpResponse withContentLength(long length) {
        if (length < 0) {
            throw new IllegalArgumentException("content length must not be negative");
        }
        return new OrionHttpResponse(status, body, contentType, headers, length);
    }

    public int status() {
        return status;
    }

    public Object body() {
        return body;
    }

    public String contentType() {
        return contentType;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public Long contentLength() {
        return contentLength;
    }
}
