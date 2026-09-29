package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.util.LogInitializer;
import pro.deta.orion.util.ScopedLogs;

import java.io.IOException;
import java.nio.file.NoSuchFileException;

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
            String scope = request.getParameter("scope");
            if (scope != null) {
                ScopedLogs logs = logging.getScopedLogs();
                if (logs == null) return OrionHttpResponse.text(503, "Scoped logs are not configured")
                        .withHeader("Cache-Control", "no-store");
                String id = request.getParameter("id");
                Object result = id == null ? logs.list(scope)
                        : logs.read(scope, id, request.getParameter("file"),
                                request.getParameter("offset") == null ? 0
                                        : Long.parseLong(request.getParameter("offset")),
                                request.getParameter("version"));
                return OrionHttpResponse.ok(result).withHeader("Cache-Control", "no-store");
            }
            return OrionHttpResponse.ok(logging.readLogs(request.getParameter("after")))
                    .withHeader("Cache-Control", "no-store");
        } catch (ScopedLogs.ChangedFileException changed) {
            return OrionHttpResponse.text(409, "Log file rotated. Refresh to read the current file.")
                    .withHeader("Cache-Control", "no-store");
        } catch (NoSuchFileException missing) {
            return OrionHttpResponse.text(404, "Log file not found").withHeader("Cache-Control", "no-store");
        } catch (IllegalArgumentException failure) {
            return OrionHttpResponse.text(400, "Invalid log selection or cursor")
                    .withHeader("Cache-Control", "no-store");
        } catch (IOException failure) {
            return OrionHttpResponse.text(503, "Could not read log files")
                    .withHeader("Cache-Control", "no-store");
        }
    }
}
