package pro.deta.orion.transport.http;

import pro.deta.orion.config.OrionDocumentRevision;
import pro.deta.orion.config.OrionDocumentCodec;
import pro.deta.orion.util.VcRevision;
import pro.deta.orion.config.OrionConfigurationEditor;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;

import static jakarta.servlet.http.HttpServletResponse.SC_OK;

public class OrionAdminAccessControlRoute extends BaseAdminRoute {
    private final OrionConfigurationEditor editor;
    private final OrionDocumentCodec codec = new OrionDocumentCodec();

    @Inject
    public OrionAdminAccessControlRoute(OrionConfigurationEditor editor) {
        super(
                OrionAdminPaths.ACCESS_CONTROL,
                OrionHttpRouteDefinition.Method.GET);
        this.editor = editor;
    }

    @Override
    protected OrionHttpResponse doGet(HttpServletRequest req) throws IOException {
        OrionDocumentRevision snapshot = editor.load();
        if (!(snapshot.revision() instanceof VcRevision.Stored revision)) {
            throw new IllegalStateException("Configuration revision is unavailable");
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            codec.write(snapshot.result().valueOrFailure("Cannot read configuration document"), output);
            return OrionHttpResponse.resource(SC_OK, output.toByteArray(), OrionHttpResponse.XML_CONTENT_TYPE)
                    .withHeader("ETag", "\"" + revision.value() + "\"");
        }
    }
}
