package pro.deta.orion.transport.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;

@Singleton
public class OrionHttpRouteServlet extends HttpServlet {
    private final OrionHttpRouteRegistry routeRegistry;
    private final OrionHttpResponseWriter responseWriter;

    @Inject
    public OrionHttpRouteServlet(OrionHttpRouteRegistry routeRegistry, OrionHttpResponseWriter responseWriter) {
        this.routeRegistry = routeRegistry;
        this.responseWriter = responseWriter;
    }

    @Override
    public void service(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        try {
            OrionHttpRoute route = routeRegistry.routeFor(routePath(req));
            if (route == null) {
                resp.sendError(SC_NOT_FOUND);
                return;
            }
            route.handle(req, resp, responseWriter);
        } catch (JsonProcessingException e) {
            resp.sendError(SC_BAD_REQUEST, "Invalid JSON request");
        } catch (IllegalArgumentException | IllegalStateException e) {
            resp.sendError(SC_BAD_REQUEST, e.getMessage());
        }
    }

    private static String routePath(HttpServletRequest req) {
        String path = req.getPathInfo();
        if (path != null && !path.isBlank()) {
            return path;
        }
        path = req.getRequestURI();
        if (path != null && !path.isBlank()) {
            return path;
        }
        return "/";
    }
}
