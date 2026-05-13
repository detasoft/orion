package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.acl.schema.AccessControlXmlSchema;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static jakarta.servlet.http.HttpServletResponse.SC_OK;

public class OrionAccessControlSchemaRoute extends AbstractOrionHttpRoute {
    public static final String URL_PATTERN = "/schemas/orion-admin-acl.xsd";

    private final AccessControlXmlSchema accessControlXmlSchema;
    private final String schema;

    @Inject
    public OrionAccessControlSchemaRoute() {
        super(URL_PATTERN, "GET", "POST");
        accessControlXmlSchema = new AccessControlXmlSchema();
        schema = accessControlXmlSchema.document();
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest req) {
        return OrionHttpResponse.xml(SC_OK, schema);
    }

    @Override
    protected OrionHttpResponse doPost(HttpServletRequest req) throws IOException {
        AccessControlXmlSchema.ValidationResult result = accessControlXmlSchema.validate(req.getInputStream());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("valid", result.valid());
        if (!result.valid()) {
            body.put("message", result.message());
        }
        return OrionHttpResponse.ok(body);
    }
}
