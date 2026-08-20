package pro.deta.orion.acl.schema;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;

public enum AccessControlXmlSchemaVersion {
    V1("1");

    public static final AccessControlXmlSchemaVersion LATEST = V1;
    private static final String SCHEMA_VERSION_ATTRIBUTE = "schemaVersion";

    private final String value;

    AccessControlXmlSchemaVersion(String value) {
        this.value = value;
    }

    public static AccessControlXmlSchemaVersion detect(byte[] content) throws IOException {
        try {
            DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
            documentBuilderFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            documentBuilderFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            documentBuilderFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            documentBuilderFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            documentBuilderFactory.setExpandEntityReferences(false);
            documentBuilderFactory.setXIncludeAware(false);

            Document document = documentBuilderFactory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(content));
            Element root = document.getDocumentElement();
            if (root == null) {
                throw new IOException("ACL XML document has no root element");
            }

            String version = root.getAttribute(SCHEMA_VERSION_ATTRIBUTE);
            if (version == null || version.isBlank()) {
                return V1;
            }
            return fromValue(version.trim());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot detect ACL XML schema version", e);
        }
    }

    public String value() {
        return value;
    }

    private static AccessControlXmlSchemaVersion fromValue(String value) throws IOException {
        for (AccessControlXmlSchemaVersion version : values()) {
            if (version.value.equals(value)) {
                return version;
            }
        }
        throw new IOException("Unsupported ACL XML schema version: " + value);
    }
}
