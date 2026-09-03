package pro.deta.orion.schema.orion;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import pro.deta.orion.schema.orion.v2.OrionV2;
import pro.deta.orion.schema.orion.v2.OrionV2Mapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

final class OrionXmlV2Translator implements OrionXmlTranslator {
    private static final JAXBContext JAXB_CONTEXT = createContext();
    private static final OrionXmlSchema XML_SCHEMA = new OrionXmlSchema();

    @Override
    public OrionXmlSchemaVersion schemaVersion() {
        return OrionXmlSchemaVersion.V2;
    }

    @Override
    public OrionDocument read(byte[] content) throws IOException {
        OrionXmlSchema.ValidationResult validation = XML_SCHEMA.validate(new ByteArrayInputStream(content));
        if (!validation.valid()) {
            throw new IOException("Document does not conform to Orion XML v2 schema: " + validation.message());
        }
        try {
            Unmarshaller unmarshaller = JAXB_CONTEXT.createUnmarshaller();
            unmarshaller.setSchema(XML_SCHEMA.compiledSchema());
            OrionV2 dto = (OrionV2) unmarshaller.unmarshal(new ByteArrayInputStream(content));
            return OrionV2Mapper.toCurrent(dto);
        } catch (JAXBException | IllegalArgumentException | NullPointerException e) {
            throw new IOException("Invalid Orion XML v2 document: " + e.getMessage(), e);
        }
    }

    void write(OrionDocument document, OutputStream output) throws IOException {
        try {
            Marshaller marshaller = JAXB_CONTEXT.createMarshaller();
            marshaller.setSchema(XML_SCHEMA.compiledSchema());
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            marshaller.setProperty(Marshaller.JAXB_ENCODING, StandardCharsets.UTF_8.name());
            marshaller.marshal(OrionV2Mapper.fromCurrent(document), output);
        } catch (JAXBException | IllegalArgumentException | NullPointerException e) {
            throw new IOException("Cannot write Orion XML v2 document: " + e.getMessage(), e);
        }
    }

    static JAXBContext jaxbContext() {
        return JAXB_CONTEXT;
    }

    private static JAXBContext createContext() {
        try {
            return JAXBContext.newInstance(OrionV2.class);
        } catch (JAXBException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
