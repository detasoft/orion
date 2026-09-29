package pro.deta.orion.transport.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.acl.storage.AccessControlConcurrentUpdateException;
import pro.deta.orion.auth.SecurityContext;

import java.util.Arrays;

/** Admin ACME settings endpoint; never returns EAB secret values. */
public final class OrionAdminAcmeConfigurationRoute extends BaseAdminRoute {
    private final AcmeConfigurationService configuration;
    private final ObjectMapper mapper;

    @Inject
    public OrionAdminAcmeConfigurationRoute(AcmeConfigurationService configuration, ObjectMapper mapper) {
        super("/api/admin/acme/configuration", OrionHttpRouteDefinition.Method.GET,
                OrionHttpRouteDefinition.Method.POST);
        this.configuration = configuration;
        this.mapper = mapper;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest request) {
        return OrionHttpResponse.ok(configuration.view()).withHeader("Cache-Control", "no-store");
    }

    @Override
    protected OrionHttpResponse doPost(HttpServletRequest request) {
        byte[] bytes = null;
        AcmeConfigurationService.Settings settings = null;
        try {
            if (request.getContentType() == null
                    || !request.getContentType().split(";", 2)[0].equalsIgnoreCase("application/json")) {
                return OrionHttpResponse.empty(415);
            }
            bytes = request.getInputStream().readNBytes(16385);
            if (bytes.length > 16384) return OrionHttpResponse.empty(413);
            settings = mapper.readValue(bytes, AcmeConfigurationService.Settings.class);
            SecurityContext context = (SecurityContext) request.getAttribute(
                    OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE);
            return OrionHttpResponse.ok(configuration.save(settings, context.getUserIdentity().getUserId()))
                    .withHeader("Cache-Control", "no-store");
        } catch (AccessControlConcurrentUpdateException conflict) {
            return OrionHttpResponse.text(409, "Configuration changed. Reload ACME settings and try again.");
        } catch (IllegalArgumentException | JsonProcessingException invalid) {
            return OrionHttpResponse.text(400, "Check ACME settings, account key and any required EAB credentials.");
        } catch (Exception failure) {
            return OrionHttpResponse.text(503, "Could not save ACME settings.");
        } finally {
            if (bytes != null) Arrays.fill(bytes, (byte) 0);
            if (settings != null && settings.eabHmacKey() != null) Arrays.fill(settings.eabHmacKey(), '\0');
        }
    }
}
