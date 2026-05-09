package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.auth.AuthenticationResult;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.OrionSecurityException;
import pro.deta.orion.auth.check.resource.ApplicationAdminResource;
import pro.deta.orion.auth.check.rule.ApplicationAccessRules;
import pro.deta.orion.auth.check.rule.SubjectAccessRules;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN;
import static pro.deta.orion.auth.check.AccessEnforcer.accessEnforcer;

@Singleton
public class OrionAdminAuthorizationFilter implements Filter {
    public static final String SECURITY_CONTEXT_ATTRIBUTE = OrionAdminAuthorizationFilter.class.getName() + ".securityContext";
    private static final String ADMIN_PATH = "/api/admin";
    private static final String TOKEN_PATH = "/api/admin/token";
    private static final String BEARER_PREFIX = "Bearer ";

    private final OrionAccessControlService accessControlService;

    @Inject
    public OrionAdminAuthorizationFilter(OrionAccessControlService accessControlService) {
        this.accessControlService = accessControlService;
    }

    public String filterPath() {
        return ADMIN_PATH + "/*";
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        if (request instanceof HttpServletRequest httpRequest && response instanceof HttpServletResponse httpResponse) {
            if (!requiresAuthorization(httpRequest)) {
                chain.doFilter(request, response);
                return;
            }

            SecurityContext securityContext = securityContextFor(httpRequest);
            try {
                accessEnforcer().require(securityContext, SubjectAccessRules.authenticated());
                accessEnforcer().require(securityContext, ApplicationAdminResource.applicationAdmin(), ApplicationAccessRules.admin());
            } catch (OrionSecurityException e) {
                httpResponse.sendError(SC_FORBIDDEN);
                return;
            }
            httpRequest.setAttribute(SECURITY_CONTEXT_ATTRIBUTE, securityContext);
        }
        chain.doFilter(request, response);
    }

    private boolean requiresAuthorization(HttpServletRequest request) {
        String path = requestPath(request);
        return path != null
                && (ADMIN_PATH.equals(path) || path.startsWith(ADMIN_PATH + "/"))
                && !TOKEN_PATH.equals(path);
    }

    private String requestPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        if (requestUri != null && !requestUri.isBlank()) {
            return requestUri;
        }
        return request.getPathInfo();
    }

    private SecurityContext securityContextFor(HttpServletRequest req) {
        SecurityContext securityContext = SecurityContext.createContext().withRequestId(req.toString());
        String bearerToken = bearerTokenFrom(req);
        if (bearerToken == null) {
            return securityContext;
        }

        AuthenticationResult authentication = accessControlService.authenticateToken(bearerToken.getBytes(StandardCharsets.UTF_8));
        if (authentication instanceof AuthenticationResult.Success(var userIdentity)) {
            securityContext.withUserIdentity(userIdentity);
        }
        return securityContext;
    }

    private String bearerTokenFrom(HttpServletRequest req) {
        String authorization = req.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = authorization.substring(BEARER_PREFIX.length());
        if (token.isBlank()) {
            return null;
        }
        return token;
    }
}
