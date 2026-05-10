package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

import static jakarta.servlet.http.HttpServletResponse.SC_ACCEPTED;

public class OrionAdminShutdownRoute extends BaseAdminRoute {
    private final OrionShutdownLifecycle shutdownLifecycle;

    @Inject
    public OrionAdminShutdownRoute(OrionShutdownLifecycle shutdownLifecycle) {
        super(OrionAdminPaths.SHUTDOWN, "POST");
        this.shutdownLifecycle = shutdownLifecycle;
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
            shutdownLifecycle.beginShutdownLifecycle();
        }
    }

    @Override
    protected OrionHttpResponse doPost(HttpServletRequest req) {
        return OrionHttpResponse.json(SC_ACCEPTED, new ShutdownResponse("shutdown-requested"));
    }

    public record ShutdownResponse(String status) {
    }
}
