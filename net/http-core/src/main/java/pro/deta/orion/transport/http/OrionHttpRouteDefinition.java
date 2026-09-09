package pro.deta.orion.transport.http;

import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.auth.SecurityContext;
import pro.deta.orion.auth.check.OrionSecurityException;
import pro.deta.orion.auth.check.resource.ApplicationAdminResource;
import pro.deta.orion.auth.check.rule.ApplicationAccessRules;
import pro.deta.orion.auth.check.rule.SubjectAccessRules;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static pro.deta.orion.auth.check.AccessEnforcer.accessEnforcer;

public record OrionHttpRouteDefinition(
        String urlPattern,
        Authorization authorization,
        List<Method> methods,
        MethodPolicy methodPolicy,
        Map<String, String> methodRejectionHeaders) {
    public OrionHttpRouteDefinition(
            String urlPattern,
            Authorization authorization,
            Method... methods) {
        this(
                urlPattern,
                authorization,
                List.of(methods),
                fixedPolicy(methods),
                Map.of());
    }

    public OrionHttpRouteDefinition {
        Objects.requireNonNull(urlPattern, "urlPattern");
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(methodPolicy, "methodPolicy");
        methods = normalized(methods);
        methodRejectionHeaders = Map.copyOf(methodRejectionHeaders);
    }

    List<Method> allowedMethods(HttpServletRequest request) {
        List<Method> allowed = normalized(methodPolicy.allowedMethods(request));
        if (!methods.containsAll(allowed)) {
            throw new IllegalStateException("HTTP method policy exceeds route metadata");
        }
        return allowed;
    }

    public List<String> methodNames() {
        return methodNames(methods);
    }

    static String allowHeader(List<Method> methods) {
        return String.join(", ", methodNames(methods));
    }

    private static List<String> methodNames(List<Method> methods) {
        List<String> result = new ArrayList<>();
        for (Method method : methods) {
            result.add(method.name());
        }
        return List.copyOf(result);
    }

    private static List<Method> normalized(List<Method> methods) {
        Objects.requireNonNull(methods, "methods");
        List<Method> result = new ArrayList<>();
        for (Method method : methods) {
            Method required = Objects.requireNonNull(method, "method");
            if (!result.contains(required)) {
                result.add(required);
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("HTTP route must allow at least one method");
        }
        return List.copyOf(result);
    }

    private static MethodPolicy fixedPolicy(Method[] methods) {
        List<Method> fixedMethods = normalized(List.of(methods));
        return request -> fixedMethods;
    }

    public enum Method {
        GET,
        HEAD,
        POST,
        PUT,
        DELETE,
        PATCH;

        static Optional<Method> from(String value) {
            if (value == null) {
                return Optional.empty();
            }
            try {
                return Optional.of(valueOf(value.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                return Optional.empty();
            }
        }
    }

    public enum Authorization {
        ANONYMOUS("anonymous", false, false),
        AUTHENTICATED("authenticated", true, false),
        APPLICATION_ADMIN("application-admin", true, true),
        GIT("git", true, false),
        AGENT_HANDSHAKE("agent handshake", false, false);

        private final String description;
        private final boolean authenticated;
        private final boolean applicationAdmin;

        Authorization(String description, boolean authenticated, boolean applicationAdmin) {
            this.description = description;
            this.authenticated = authenticated;
            this.applicationAdmin = applicationAdmin;
        }

        public String description() {
            return description;
        }

        boolean allows(HttpServletRequest request) {
            if (!authenticated) {
                return true;
            }
            try {
                SecurityContext securityContext = securityContextFrom(request);
                accessEnforcer().require(securityContext, SubjectAccessRules.authenticated());
                if (applicationAdmin) {
                    accessEnforcer().require(
                            securityContext,
                            ApplicationAdminResource.applicationAdmin(),
                            ApplicationAccessRules.admin());
                }
                return true;
            } catch (OrionSecurityException ignored) {
                return false;
            }
        }

        private static SecurityContext securityContextFrom(HttpServletRequest request) {
            Object attribute = request.getAttribute(OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE);
            if (attribute instanceof SecurityContext securityContext) {
                return securityContext;
            }
            return SecurityContext.createContext().withRequestId(request.toString());
        }
    }

    @FunctionalInterface
    public interface MethodPolicy {
        List<Method> allowedMethods(HttpServletRequest request);
    }
}
