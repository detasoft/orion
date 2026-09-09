package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.event.OrionEventManager;
import pro.deta.orion.event.type.ApplicationShutdownRequestedEvent;

import java.io.IOException;

import static jakarta.servlet.http.HttpServletResponse.SC_ACCEPTED;

public class OrionAdminShutdownRoute extends BaseAdminRoute {
    private static final String SOURCE = "http-admin";
    private final OrionEventManager eventManager;

    @Inject
    public OrionAdminShutdownRoute(OrionEventManager eventManager) {
        super(OrionAdminPaths.SHUTDOWN, OrionHttpRouteDefinition.Method.POST);
        this.eventManager = eventManager;
    }

    @Override
    public void handle(OrionHttpExchange exchange) throws IOException {
        exchange.sendAfterFlush(
                doPost(exchange.request()),
                () -> eventManager.publish(new ApplicationShutdownRequestedEvent(SOURCE)));
    }

    @Override
    protected OrionHttpResponse doPost(HttpServletRequest req) {
        return OrionHttpResponse.json(SC_ACCEPTED, new ShutdownResponse("shutdown-requested"));
    }

    public record ShutdownResponse(String status) {
    }
}
