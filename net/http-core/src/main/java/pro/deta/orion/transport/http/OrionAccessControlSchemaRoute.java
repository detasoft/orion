package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import pro.deta.orion.acl.schema.AccessControlXmlSchema;

import static jakarta.servlet.http.HttpServletResponse.SC_OK;

public class OrionAccessControlSchemaRoute extends AbstractOrionHttpRoute {
    public static final String URL_PATTERN = "/schemas/orion-admin-acl.xsd";

    private final String schema;

    @Inject
    public OrionAccessControlSchemaRoute() {
        super(URL_PATTERN, "GET");
        schema = new AccessControlXmlSchema().document();
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest req) {
        return OrionHttpResponse.xml(SC_OK, schema);
    }
}
