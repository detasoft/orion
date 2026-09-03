package pro.deta.orion.schema.orion;

import jakarta.xml.bind.SchemaOutputResolver;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.Result;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;

public final class OrionXmlSchema {
    private final String document;
    private final Schema schema;

    public OrionXmlSchema() {
        document = generateDocument();
        schema = compileSchema(document);
    }

    public String document() {
        return document;
    }

    public ValidationResult validate(InputStream input) throws IOException {
        try {
            var validator = schema.newValidator();
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            validator.validate(new StreamSource(input));
            return ValidationResult.ok();
        } catch (SAXException e) {
            return ValidationResult.error(e.getMessage());
        }
    }

    Schema compiledSchema() {
        return schema;
    }

    private static String generateDocument() {
        try {
            StringWriter document = new StringWriter();
            OrionXmlV2Translator.jaxbContext().generateSchema(new SchemaOutputResolver() {
                @Override
                public Result createOutput(String namespaceUri, String suggestedFileName) {
                    StreamResult result = new StreamResult(document);
                    result.setSystemId(suggestedFileName);
                    return result;
                }
            });
            return document.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot generate Orion XML v2 schema", e);
        }
    }

    private static Schema compileSchema(String document) {
        try {
            SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            schemaFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            StreamSource source = new StreamSource(new StringReader(document));
            source.setSystemId("orion-v2.xsd");
            return schemaFactory.newSchema(source);
        } catch (SAXException e) {
            throw new IllegalStateException("Cannot compile Orion XML v2 schema", e);
        }
    }

    public record ValidationResult(boolean valid, String message) {
        private static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        private static ValidationResult error(String message) {
            return new ValidationResult(false, message);
        }
    }
}
