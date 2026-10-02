package pro.deta.orion.schema.orion;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class OrionXml {
    private static final OrionXmlV2Translator TRANSLATOR = new OrionXmlV2Translator();

    private OrionXml() {
    }

    public static OrionDocument read(InputStream input) throws IOException {
        byte[] content = input.readAllBytes();
        OrionXmlSchemaVersion.detect(content);
        return TRANSLATOR.read(content);
    }

    public static void write(OrionDocument document, OutputStream output) throws IOException {
        TRANSLATOR.write(document, output);
    }

    public static OrionXmlSchemaVersion currentSchemaVersion() {
        return OrionXmlSchemaVersion.LATEST;
    }
}
