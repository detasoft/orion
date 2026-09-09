package pro.deta.orion.transport.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_METHOD_NOT_ALLOWED;
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
            OrionHttpRouteDefinition definition = route.definition();
            if (!definition.authorization().allows(req)) {
                resp.sendError(SC_FORBIDDEN);
                return;
            }
            List<OrionHttpRouteDefinition.Method> allowedMethods = definition.allowedMethods(req);
            Optional<OrionHttpRouteDefinition.Method> method =
                    OrionHttpRouteDefinition.Method.from(req.getMethod());
            if (method.isEmpty() || !allowedMethods.contains(method.get())) {
                resp.setHeader(
                        "Allow",
                        OrionHttpRouteDefinition.allowHeader(allowedMethods));
                for (var header : definition.methodRejectionHeaders().entrySet()) {
                    resp.setHeader(header.getKey(), header.getValue());
                }
                resp.setStatus(SC_METHOD_NOT_ALLOWED);
                return;
            }
            route.handle(new OrionHttpExchange(req, resp, responseWriter, method.get()));
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
