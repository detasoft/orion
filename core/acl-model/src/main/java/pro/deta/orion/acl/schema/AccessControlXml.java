package pro.deta.orion.acl.schema;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public final class AccessControlXml {
    private static final List<AccessControlXmlTranslator> TRANSLATORS = List.of(new AccessControlXmlV1Translator());
    private static final AccessControlXmlTranslator LATEST_TRANSLATOR = translatorFor(AccessControlXmlSchemaVersion.LATEST);

    private AccessControlXml() {
    }

    public static void write(AccessControl accessControl, OutputStream output) throws JAXBException {
        LATEST_TRANSLATOR.write(accessControl, output);
    }

    public static AccessControl read(InputStream input) throws JAXBException, IOException {
        byte[] content = input.readAllBytes();
        return translatorFor(AccessControlXmlSchemaVersion.detect(content)).read(content);
    }

    public static AccessControlXmlSchemaVersion currentSchemaVersion() {
        return LATEST_TRANSLATOR.schemaVersion();
    }

    public static JAXBContext jaxbContext() {
        return LATEST_TRANSLATOR.jaxbContext();
    }

    private static AccessControlXmlTranslator translatorFor(AccessControlXmlSchemaVersion schemaVersion) {
        for (AccessControlXmlTranslator translator : TRANSLATORS) {
            if (translator.schemaVersion() == schemaVersion) {
                return translator;
            }
        }
        throw new IllegalStateException("No ACL XML translator for schema version: " + schemaVersion);
    }
}
