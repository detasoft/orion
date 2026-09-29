package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.OrionAccessControlService;
import pro.deta.orion.auth.AccessControlValidationException;
import pro.deta.orion.acl.storage.AccessControlConcurrentUpdateException;
import pro.deta.orion.auth.SecurityContext;

import java.io.IOException;
import java.util.Map;

import static jakarta.servlet.http.HttpServletResponse.SC_OK;

public class OrionAdminAccessControlRoute extends BaseAdminRoute {
    private final OrionAccessControlService accessControlService;

    @Inject
    public OrionAdminAccessControlRoute(OrionAccessControlService accessControlService) {
        super(
                OrionAdminPaths.ACCESS_CONTROL,
                OrionHttpRouteDefinition.Method.GET,
                OrionHttpRouteDefinition.Method.POST);
        this.accessControlService = accessControlService;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest req) {
        OrionAccessControlService.ConfigurationFile file = accessControlService.accessControlConfigurationFile();
        String revision = file.revision().orElseThrow(() -> new IllegalStateException(
                "Configuration revision is unavailable"));
        return OrionHttpResponse.resource(SC_OK, file.content(), OrionHttpResponse.XML_CONTENT_TYPE)
                .withHeader("ETag", "\"" + revision + "\"");
    }

    @Override
    protected OrionHttpResponse doPost(HttpServletRequest req) throws IOException {
        String ifMatch = req.getHeader("If-Match");
        if (ifMatch == null || ifMatch.isBlank()) {
            return OrionHttpResponse.json(428, Map.of("status", "configuration-revision-required"));
        }
        if (ifMatch.length() < 3 || !ifMatch.startsWith("\"") || !ifMatch.endsWith("\"")
                || ifMatch.indexOf('"', 1) != ifMatch.length() - 1) {
            throw new HttpRequestValidationException("Invalid configuration revision");
        }
        try {
            SecurityContext context = (SecurityContext) req.getAttribute(
                    OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE);
            accessControlService.saveAccessControlConfigurationFile(
                    req.getInputStream().readAllBytes(), ifMatch.substring(1, ifMatch.length() - 1),
                    context.getUserIdentity().getUserId());
        } catch (AccessControlValidationException failure) {
            throw new HttpRequestValidationException(failure.getMessage());
        } catch (AccessControlConcurrentUpdateException failure) {
            return OrionHttpResponse.json(409, Map.of("status", "configuration-conflict"));
        }
        return OrionHttpResponse.created(Map.of("status", "ok"));
    }
}
