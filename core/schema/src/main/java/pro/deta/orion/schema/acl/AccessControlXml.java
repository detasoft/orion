package pro.deta.orion.schema.acl;

import jakarta.xml.bind.JAXBException;

import java.io.IOException;
import java.io.InputStream;

public final class AccessControlXml {
    private static final AccessControlXmlTranslator V1_TRANSLATOR = new AccessControlXmlV1Translator();

    private AccessControlXml() {
    }

    public static AccessControl read(InputStream input) throws JAXBException, IOException {
        byte[] content = input.readAllBytes();
        AccessControlXmlSchemaVersion schemaVersion = AccessControlXmlSchemaVersion.detect(content);
        if (schemaVersion != V1_TRANSLATOR.schemaVersion()) {
            throw new IOException("No legacy ACL XML translator for schema version: " + schemaVersion);
        }
        return V1_TRANSLATOR.read(content);
    }
}
