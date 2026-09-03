package pro.deta.orion.schema.acl;

import jakarta.xml.bind.JAXBException;

import java.io.IOException;

interface AccessControlXmlTranslator {
    AccessControlXmlSchemaVersion schemaVersion();

    AccessControl read(byte[] content) throws JAXBException, IOException;
}
