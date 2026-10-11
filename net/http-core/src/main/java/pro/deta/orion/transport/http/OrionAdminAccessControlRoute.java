package pro.deta.orion.transport.http;

import pro.deta.orion.config.OrionDocumentRevision;
import pro.deta.orion.config.OrionDocumentCodec;
import pro.deta.orion.config.OrionRevision;
import pro.deta.orion.config.OrionVersionedConfigurationStorage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import static jakarta.servlet.http.HttpServletResponse.SC_OK;

public class OrionAdminAccessControlRoute extends BaseAdminRoute {
    private final OrionVersionedConfigurationStorage storage;
    private final OrionDocumentCodec codec = new OrionDocumentCodec();

    @Inject
    public OrionAdminAccessControlRoute(OrionVersionedConfigurationStorage storage) {
        super(
                OrionAdminPaths.ACCESS_CONTROL,
                OrionHttpRouteDefinition.Method.GET);
        this.storage = storage;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest req) throws IOException {
        OrionDocumentRevision snapshot = storage.load().valueOrFailure("Cannot load configuration for export");
        if (!(snapshot.revision() instanceof OrionRevision.Stored revision)) {
            throw new IllegalStateException("Configuration revision is unavailable");
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            codec.write(snapshot.document(), output);
            return OrionHttpResponse.resource(SC_OK, output.toByteArray(), OrionHttpResponse.XML_CONTENT_TYPE)
                    .withHeader("ETag", "\"" + revision.value() + "\"");
        }
    }
}
