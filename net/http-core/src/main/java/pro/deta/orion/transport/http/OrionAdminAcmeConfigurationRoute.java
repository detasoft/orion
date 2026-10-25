package pro.deta.orion.transport.http;

import pro.deta.orion.acme.AcmeCertificateService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.config.OrionConfigurationConcurrentUpdateException;
import pro.deta.orion.auth.SecurityContext;

import java.util.Arrays;

/** Admin ACME settings endpoint; never returns EAB secret values. */
public final class OrionAdminAcmeConfigurationRoute extends BaseAdminRoute {
    private final AcmeCertificateService configuration;
    private final ObjectMapper mapper;
    private final Provider<JettyHTTPServer> serverProvider;

    @Inject
    public OrionAdminAcmeConfigurationRoute(
            AcmeCertificateService configuration, ObjectMapper mapper, Provider<JettyHTTPServer> serverProvider) {
        super("/api/admin/acme/configuration", OrionHttpRouteDefinition.Method.GET,
                OrionHttpRouteDefinition.Method.POST);
        this.configuration = configuration;
        this.mapper = mapper;
        this.serverProvider = serverProvider;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest request) {
        return OrionHttpResponse.ok(view(configuration.view())).withHeader("Cache-Control", "no-store");
    }

    @Override
    protected OrionHttpResponse doPost(HttpServletRequest request) {
        byte[] bytes = null;
        AcmeCertificateService.Settings settings = null;
        try {
            if (request.getContentType() == null
                    || !request.getContentType().split(";", 2)[0].equalsIgnoreCase("application/json")) {
                return OrionHttpResponse.empty(415);
            }
            bytes = request.getInputStream().readNBytes(16385);
            if (bytes.length > 16384) return OrionHttpResponse.empty(413);
            settings = mapper.readValue(bytes, AcmeCertificateService.Settings.class);
            SecurityContext context = (SecurityContext) request.getAttribute(
                    OrionAuthorizationFilter.SECURITY_CONTEXT_ATTRIBUTE);
            return OrionHttpResponse.ok(view(configuration.save(settings, context.getUserIdentity().getUserId())))
                    .withHeader("Cache-Control", "no-store");
        } catch (OrionConfigurationConcurrentUpdateException conflict) {
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

    private AcmeCertificateService.View view(AcmeCertificateService.View view) {
        JettyHTTPServer server = serverProvider == null ? null : serverProvider.get();
        String activationError = server == null ? "" : server.certificateActivationError();
        AcmeCertificateService.RenewalStatus renewal = view.renewal();
        AcmeCertificateService.RenewalStatus updated = new AcmeCertificateService.RenewalStatus(
                renewal.state(), renewal.expiresAt(), renewal.nextAttempt(), renewal.lastAttempt(),
                renewal.lastSuccess(), renewal.message(), activationError);
        return new AcmeCertificateService.View(view.revision(), view.enabled(), view.provider(), view.directoryUrl(),
                view.accountEmail(), view.domains(), view.eabKeyId(), view.eabConfigured(), view.presets(),
                updated, view.accountMaterial());
    }
}
