package pro.deta.orion.schema.orion;

import jakarta.xml.bind.JAXBException;
import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.acl.AccessControlXml;

import java.io.ByteArrayInputStream;
import java.io.IOException;

final class OrionXmlV1Translator implements OrionXmlTranslator {
    @Override
    public OrionXmlSchemaVersion schemaVersion() {
        return OrionXmlSchemaVersion.V1;
    }

    @Override
    public OrionDocument read(byte[] content) throws IOException {
        try {
            AccessControl accessControl = AccessControlXml.read(new ByteArrayInputStream(content));
            return OrionDocument.withAccessControl(accessControl);
        } catch (JAXBException e) {
            throw new IOException("Cannot read legacy AccessControl XML", e);
        }
    }
}
