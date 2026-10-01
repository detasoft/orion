package pro.deta.orion.transport.http;

import pro.deta.orion.config.ConfigurationFile;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.OrionAccessControlService;

import static jakarta.servlet.http.HttpServletResponse.SC_OK;

public class OrionAdminAccessControlRoute extends BaseAdminRoute {
    private final OrionAccessControlService accessControlService;

    @Inject
    public OrionAdminAccessControlRoute(OrionAccessControlService accessControlService) {
        super(
                OrionAdminPaths.ACCESS_CONTROL,
                OrionHttpRouteDefinition.Method.GET);
        this.accessControlService = accessControlService;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest req) {
        ConfigurationFile file = accessControlService.accessControlConfigurationFile();
        String revision = file.revision().orElseThrow(() -> new IllegalStateException(
                "Configuration revision is unavailable"));
        return OrionHttpResponse.resource(SC_OK, file.content(), OrionHttpResponse.XML_CONTENT_TYPE)
                .withHeader("ETag", "\"" + revision + "\"");
    }
}
