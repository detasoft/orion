package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

@Singleton
public final class OrionHttpResponseWriter {
    private final ObjectMapper objectMapper;

    @Inject
    public OrionHttpResponseWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse resp, OrionHttpResponse response) throws IOException {
        write(resp, response, false);
    }

    public void write(
            HttpServletResponse resp,
            OrionHttpResponse response,
            boolean headersOnly) throws IOException {
        resp.setStatus(response.status());
        for (Map.Entry<String, String> header : response.headers().entrySet()) {
            resp.setHeader(header.getKey(), header.getValue());
        }
        Object body = response.body();
        if (body == null) {
            return;
        }
        String contentType = response.contentType();
        if (body instanceof byte[] bytes) {
            if (contentType != null) {
                resp.setContentType(contentType);
            }
            resp.setContentLength(bytes.length);
            if (!headersOnly) {
                resp.getOutputStream().write(bytes);
            }
            return;
        }
        if (contentType == null) {
            resp.setContentType(OrionHttpResponse.JSON_CONTENT_TYPE);
            if (!headersOnly) {
                objectMapper.writeValue(resp.getWriter(), body);
            }
            return;
        }
        resp.setContentType(contentType);
        if (isJsonContentType(contentType)) {
            if (!headersOnly) {
                objectMapper.writeValue(resp.getWriter(), body);
            }
            return;
        }
        if (!headersOnly) {
            resp.getWriter().write(String.valueOf(body));
        }
    }

    private static boolean isJsonContentType(String contentType) {
        return OrionHttpResponse.JSON_CONTENT_TYPE.equals(contentType)
                || contentType.endsWith("+json");
    }
}
