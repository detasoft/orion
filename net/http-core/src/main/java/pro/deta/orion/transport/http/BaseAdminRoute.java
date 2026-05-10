package pro.deta.orion.transport.http;

import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.auth.check.OrionSecurityException;

public abstract class BaseAdminRoute extends AbstractOrionHttpRoute {
    protected BaseAdminRoute(String urlPattern, String... allowedMethods) {
        super(urlPattern, allowedMethods);
    }

    @Override
    public String authorization() {
        return "application-admin";
    }

    @Override
    protected void authorize(HttpServletRequest req) throws OrionSecurityException {
        requireApplicationAdmin(req);
    }
}
