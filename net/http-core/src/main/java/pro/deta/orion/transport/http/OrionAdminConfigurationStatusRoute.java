package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.acl.OrionAccessControlServiceImpl;

import java.util.Map;

public final class OrionAdminConfigurationStatusRoute extends BaseAdminRoute {
    private final OrionAccessControlServiceImpl accessControlService;

    @Inject
    public OrionAdminConfigurationStatusRoute(OrionAccessControlServiceImpl accessControlService) {
        super(OrionAdminPaths.CONFIGURATION_STATUS, OrionHttpRouteDefinition.Method.GET);
        this.accessControlService = accessControlService;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest request) {
        OrionAccessControlServiceImpl.ConfigurationStatus status = accessControlService.configurationStatus();
        return OrionHttpResponse.ok(Map.of(
                "storedRevision", status.storedRevision().orElse(""),
                "activeRevision", status.activeRevision().orElse(""),
                "validation", status.validation())).withHeader("Cache-Control", "no-store");
    }
}
