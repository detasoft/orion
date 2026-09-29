package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.util.LogInitializer;

public final class OrionAdminLogsRoute extends BaseAdminRoute {
    private final LogInitializer logging;

    @Inject
    public OrionAdminLogsRoute(LogInitializer logging) {
        super(OrionAdminPaths.LOGS, OrionHttpRouteDefinition.Method.GET);
        this.logging = logging;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest request) {
        try {
            return OrionHttpResponse.ok(logging.readLogs(request.getParameter("after")))
                    .withHeader("Cache-Control", "no-store");
        } catch (IllegalArgumentException failure) {
            return OrionHttpResponse.text(400, "Invalid log cursor").withHeader("Cache-Control", "no-store");
        }
    }
}
