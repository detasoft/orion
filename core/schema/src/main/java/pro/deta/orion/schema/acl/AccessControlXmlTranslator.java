package pro.deta.orion.schema.acl;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;

import java.io.IOException;
import java.io.OutputStream;

interface AccessControlXmlTranslator {
    AccessControlXmlSchemaVersion schemaVersion();

    JAXBContext jaxbContext();

    AccessControl read(byte[] content) throws JAXBException, IOException;

    void write(AccessControl accessControl, OutputStream output) throws JAXBException;
}
