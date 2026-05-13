package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.event.type.ApplicationShutdownRequestedEvent;

import java.io.IOException;

import static jakarta.servlet.http.HttpServletResponse.SC_ACCEPTED;

public class OrionAdminShutdownRoute extends BaseAdminRoute {
    private static final String SOURCE = "http-admin";
    private final OrionEventManager eventManager;

    @Inject
    public OrionAdminShutdownRoute(OrionEventManager eventManager) {
        super(OrionAdminPaths.SHUTDOWN, "POST");
        this.eventManager = eventManager;
    }

    @Override
    public void handle(
            HttpServletRequest req,
            HttpServletResponse resp,
            OrionHttpResponseWriter responseWriter) throws IOException, ServletException {
        OrionHttpResponse response = service(req);
        responseWriter.write(resp, response);
        if (response.status() == SC_ACCEPTED) {
            resp.flushBuffer();
            eventManager.publish(new ApplicationShutdownRequestedEvent(SOURCE));
        }
    }

    @Override
    protected OrionHttpResponse doPost(HttpServletRequest req) {
        return OrionHttpResponse.json(SC_ACCEPTED, new ShutdownResponse("shutdown-requested"));
    }

    public record ShutdownResponse(String status) {
    }
}
