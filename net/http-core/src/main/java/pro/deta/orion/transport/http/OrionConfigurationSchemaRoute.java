package pro.deta.orion.transport.http;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;

import static jakarta.servlet.http.HttpServletResponse.SC_OK;

public class OrionConfigurationSchemaRoute extends AbstractOrionHttpRoute {
    public static final String URL_PATTERN = "/schemas/orion-configuration.schema.json";

    private final OrionConfigurationJsonSchema schema;

    @Inject
    public OrionConfigurationSchemaRoute(OrionConfigurationJsonSchema schema) {
        super(URL_PATTERN, "GET");
        this.schema = schema;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest req) {
        return OrionHttpResponse.jsonSchema(SC_OK, schema.document());
    }
}
