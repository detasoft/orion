package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.config.OrionConfigurationEditor;

import java.util.Map;

public final class OrionAdminConfigurationStatusRoute extends BaseAdminRoute {
    private final OrionConfigurationEditor editor;

    @Inject
    public OrionAdminConfigurationStatusRoute(OrionConfigurationEditor editor) {
        super(OrionAdminPaths.CONFIGURATION_STATUS, OrionHttpRouteDefinition.Method.GET);
        this.editor = editor;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest request) {
        OrionConfigurationEditor.ConfigurationStatus status = editor.status();
        return OrionHttpResponse.ok(Map.of(
                "storedRevision", status.storedRevision().orElse(""),
                "activeRevision", status.activeRevision().orElse(""),
                "validation", status.validation())).withHeader("Cache-Control", "no-store");
    }
}
