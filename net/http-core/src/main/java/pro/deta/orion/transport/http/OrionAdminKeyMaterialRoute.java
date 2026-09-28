package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.keymaterial.ConfigurationMaterialCapability;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map;

public final class OrionAdminKeyMaterialRoute extends BaseAdminRoute {
    private final ConfigurationMaterialCapability material;

    @Inject
    public OrionAdminKeyMaterialRoute(ConfigurationMaterialCapability material) {
        super(OrionAdminPaths.KEY_MATERIAL, OrionHttpRouteDefinition.Method.GET);
        this.material = material;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest request) throws IOException {
        try {
            return OrionHttpResponse.ok(Map.of("entries", material.inventory()));
        } catch (GeneralSecurityException failure) {
            throw new IOException("Cannot read key material inventory", failure);
        }
    }
}
