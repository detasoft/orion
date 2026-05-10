package pro.deta.orion.transport.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import static jakarta.servlet.http.HttpServletResponse.SC_BAD_REQUEST;

@Singleton
public class OrionAdminServlet implements MapToUrlServlet {
    private final OrionHttpRouteDispatcher routes;

    @Inject
    public OrionAdminServlet(OrionHttpRouteDispatcher routes) {
        this.routes = routes;
    }

    @Override
    public String servletPath() {
        return "/api/admin/*";
    }

    @Override
    public void service(HttpServletRequest req, HttpServletResponse resp) throws IOException, ServletException {
        try {
            routes.handle(req, resp);
        } catch (JsonProcessingException e) {
            resp.sendError(SC_BAD_REQUEST, "Invalid JSON request");
        } catch (IllegalArgumentException | IllegalStateException e) {
            resp.sendError(SC_BAD_REQUEST, e.getMessage());
        }
    }
}
