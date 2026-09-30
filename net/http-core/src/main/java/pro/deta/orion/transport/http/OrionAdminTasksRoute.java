package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Map;

/** Current server-session status of recurring administration tasks. */
public final class OrionAdminTasksRoute extends BaseAdminRoute {
    private final GitPackCleanupTask cleanup;

    @Inject
    public OrionAdminTasksRoute(GitPackCleanupTask cleanup) {
        super("/api/admin/tasks", OrionHttpRouteDefinition.Method.GET);
        this.cleanup = cleanup;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest request) {
        return OrionHttpResponse.ok(Map.of("gitPackCleanup", cleanup.status()))
                .withHeader("Cache-Control", "no-store");
    }
}
