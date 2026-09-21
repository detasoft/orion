package pro.deta.orion.transport.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import jakarta.inject.Singleton;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;
import static jakarta.servlet.http.HttpServletResponse.SC_NOT_FOUND;

@Slf4j
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
            OrionHttpExchange exchange = new OrionHttpExchange(req, resp, responseWriter);
            OrionHttpRoute route = routeRegistry.routeFor(exchange.path());
            if (route == null) {
                resp.sendError(SC_NOT_FOUND);
                return;
            }
            route.service(exchange);
        } catch (JsonProcessingException e) {
            resp.sendError(SC_BAD_REQUEST, "Invalid JSON request");
        } catch (IllegalArgumentException e) {
            resp.sendError(SC_BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            log.error("Unexpected HTTP handler state failure", e);
            if (resp.isCommitted()) {
                throw new ServletException("HTTP handler failed after response commitment", e);
            }
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Internal server error");
        }
    }

}
