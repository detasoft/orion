package pro.deta.orion.transport.http;

import static pro.deta.orion.transport.http.OrionHttpRouteDefinition.Authorization.APPLICATION_ADMIN;

public abstract class BaseAdminRoute extends AbstractOrionHttpRoute {
    protected BaseAdminRoute(
            String urlPattern,
            OrionHttpRouteDefinition.Method... allowedMethods) {
        super(urlPattern, APPLICATION_ADMIN, allowedMethods);
    }
}
