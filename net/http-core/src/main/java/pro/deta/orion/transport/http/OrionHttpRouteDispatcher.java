package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Map;

import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;

@Singleton
public final class OrionHttpRouteDispatcher {
    private final ObjectMapper objectMapper;
    private final OrionHttpRouteRegistry routeRegistry;

    @Inject
    public OrionHttpRouteDispatcher(OrionHttpRouteRegistry routeRegistry, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.routeRegistry = routeRegistry;
    }

    public void handle(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        OrionHttpRoute route = routeRegistry.routeFor(req.getPathInfo());
        if (route == null) {
            resp.sendError(SC_NOT_FOUND);
            return;
        }
        write(resp, route.service(req));
    }

    private void write(HttpServletResponse resp, OrionHttpResponse response) throws IOException {
        resp.setStatus(response.status());
        for (Map.Entry<String, String> header : response.headers().entrySet()) {
            resp.setHeader(header.getKey(), header.getValue());
        }
        Object body = response.body();
        if (body == null) {
            return;
        }
        String contentType = response.contentType();
        if (contentType == null) {
            resp.setContentType(OrionHttpResponse.JSON_CONTENT_TYPE);
            objectMapper.writeValue(resp.getWriter(), body);
            return;
        }
        resp.setContentType(contentType);
        resp.getWriter().write(String.valueOf(body));
    }
}
