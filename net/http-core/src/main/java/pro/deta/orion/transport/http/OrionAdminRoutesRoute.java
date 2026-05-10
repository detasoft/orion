package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;

import javax.inject.Provider;
import java.util.List;

public class OrionAdminRoutesRoute extends BaseAdminRoute {
    private final Provider<OrionHttpRouteRegistry> routeRegistry;

    @Inject
    public OrionAdminRoutesRoute(Provider<OrionHttpRouteRegistry> routeRegistry) {
        super(OrionAdminPaths.ROUTES, "GET");
        this.routeRegistry = routeRegistry;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest req) {
        return OrionHttpResponse.ok(new AdminRoutesResponse(routeRegistry.get().routeTable()));
    }

    public record AdminRoutesResponse(List<OrionHttpRouteRegistry.RouteDescriptor> routes) {
    }
}
