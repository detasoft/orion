package pro.deta.orion.schema.orion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public final class OrionXml {
    private static final List<OrionXmlTranslator> TRANSLATORS =
            List.of(new OrionXmlV1Translator(), new OrionXmlV2Translator());
    private static final OrionXmlV2Translator LATEST_TRANSLATOR =
            (OrionXmlV2Translator) translatorFor(OrionXmlSchemaVersion.LATEST);

    private OrionXml() {
    }

    public static OrionDocument read(InputStream input) throws IOException {
        byte[] content = input.readAllBytes();
        return translatorFor(OrionXmlSchemaVersion.detect(content)).read(content);
    }

    public static void write(OrionDocument document, OutputStream output) throws IOException {
        LATEST_TRANSLATOR.write(document, output);
    }

    public static OrionXmlSchemaVersion currentSchemaVersion() {
        return LATEST_TRANSLATOR.schemaVersion();
    }

    private static OrionXmlTranslator translatorFor(OrionXmlSchemaVersion schemaVersion) {
        for (OrionXmlTranslator translator : TRANSLATORS) {
            if (translator.schemaVersion() == schemaVersion) {
                return translator;
            }
        }
        throw new IllegalStateException("No Orion XML translator for schema version: " + schemaVersion);
    }
}
