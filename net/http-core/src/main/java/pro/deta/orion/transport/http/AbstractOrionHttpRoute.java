package pro.deta.orion.transport.http;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.ServletException;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.OrionSecurityException;
import pro.deta.orion.auth.check.resource.ApplicationAdminResource;
import pro.deta.orion.auth.check.rule.ApplicationAccessRules;
import pro.deta.orion.auth.check.rule.SubjectAccessRules;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static jakarta.servlet.http.HttpServletResponse.SC_METHOD_NOT_ALLOWED;
import static pro.deta.orion.auth.check.AccessEnforcer.accessEnforcer;

public abstract class AbstractOrionHttpRoute implements OrionHttpRoute {
    private final String urlPattern;
    private final List<String> allowedMethods;

    protected AbstractOrionHttpRoute(String urlPattern, String... allowedMethods) {
        this.urlPattern = urlPattern;
        this.allowedMethods = normalizeMethods(allowedMethods);
    }

    @Override
    public String urlPattern() {
        return urlPattern;
    }

    @Override
    public String authorization() {
        return "anonymous";
    }

    @Override
    public List<String> allowedMethods() {
        return allowedMethods;
    }

    @Override
    public final OrionHttpResponse service(HttpServletRequest req) throws IOException, ServletException {
        OrionHttpResponse accessDenied = accessDenied(req);
        if (accessDenied != null) {
            return accessDenied;
        }
        String method = req.getMethod().toUpperCase(Locale.ROOT);
        if (!allowedMethods.contains(method)) {
            return methodNotAllowed();
        }
        return switch (method) {
            case "GET" -> doGet(req);
            case "POST" -> doPost(req);
            case "PUT" -> doPut(req);
            case "DELETE" -> doDelete(req);
            case "PATCH" -> doPatch(req);
            default -> methodNotAllowed();
        };
    }

    protected OrionHttpResponse doGet(HttpServletRequest req) throws IOException {
        return methodNotAllowed();
    }

    protected OrionHttpResponse doPost(HttpServletRequest req) throws IOException {
        return methodNotAllowed();
    }

    protected OrionHttpResponse doPut(HttpServletRequest req) throws IOException {
        return methodNotAllowed();
    }

    protected OrionHttpResponse doDelete(HttpServletRequest req) throws IOException {
        return methodNotAllowed();
    }

    protected OrionHttpResponse doPatch(HttpServletRequest req) throws IOException {
        return methodNotAllowed();
    }

    protected void authorize(HttpServletRequest req) throws OrionSecurityException {
    }

    protected final void requireAuthenticated(HttpServletRequest req) throws OrionSecurityException {
        accessEnforcer().require(securityContextFrom(req), SubjectAccessRules.authenticated());
    }

    protected final void requireApplicationAdmin(HttpServletRequest req) throws OrionSecurityException {
        SecurityContext securityContext = securityContextFrom(req);
        accessEnforcer().require(securityContext, SubjectAccessRules.authenticated());
        accessEnforcer().require(securityContext, ApplicationAdminResource.applicationAdmin(), ApplicationAccessRules.admin());
    }

    private OrionHttpResponse methodNotAllowed() {
        return OrionHttpResponse.empty(SC_METHOD_NOT_ALLOWED)
                .withHeader("Allow", String.join(", ", allowedMethods));
    }

    private OrionHttpResponse accessDenied(HttpServletRequest req) {
        try {
            authorize(req);
            return null;
        } catch (OrionSecurityException e) {
            return OrionHttpResponse.empty(SC_FORBIDDEN);
        }
    }

    private static SecurityContext securityContextFrom(HttpServletRequest req) {
        Object attribute = req.getAttribute(OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE);
        if (attribute instanceof SecurityContext securityContext) {
            return securityContext;
        }
        return SecurityContext.createContext().withRequestId(req.toString());
    }

    private static List<String> normalizeMethods(String... methods) {
        List<String> result = new ArrayList<>();
        for (String method : methods) {
            String normalized = method.toUpperCase(Locale.ROOT);
            if (!result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return List.copyOf(result);
    }
}
