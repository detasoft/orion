package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.OrionAccessControlService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static jakarta.servlet.http.HttpServletResponse.SC_OK;

public class OrionAdminAccessControlRoute extends BaseAdminRoute {
    private final OrionAccessControlService accessControlService;

    @Inject
    public OrionAdminAccessControlRoute(OrionAccessControlService accessControlService) {
        super(OrionAdminPaths.ACCESS_CONTROL, "GET", "POST");
        this.accessControlService = accessControlService;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest req) {
        byte[] content = accessControlService.accessControlConfigurationFile();
        return OrionHttpResponse.xml(SC_OK, new String(content, StandardCharsets.UTF_8));
    }

    @Override
    protected OrionHttpResponse doPost(HttpServletRequest req) throws IOException {
        accessControlService.saveAccessControlConfigurationFile(req.getInputStream().readAllBytes());
        return OrionHttpResponse.created(Map.of("status", "ok"));
    }
}
