package pro.deta.orion.schema.orion;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;

public enum OrionXmlSchemaVersion {
    V1("AccessControl", "1"),
    V2("orion", "2");

    public static final OrionXmlSchemaVersion LATEST = V2;
    private static final String SCHEMA_VERSION_ATTRIBUTE = "schemaVersion";

    private final String rootName;
    private final String value;

    OrionXmlSchemaVersion(String rootName, String value) {
        this.rootName = rootName;
        this.value = value;
    }

    public static OrionXmlSchemaVersion detect(byte[] content) throws IOException {
        try {
            DocumentBuilderFactory factory = secureDocumentBuilderFactory();
            Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(content));
            Element root = document.getDocumentElement();
            if (root == null) {
                throw new IOException("Orion XML document has no root element");
            }
            return detect(root.getTagName(), root.getAttribute(SCHEMA_VERSION_ATTRIBUTE));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Cannot detect Orion XML schema version", e);
        }
    }

    public String rootName() {
        return rootName;
    }

    public String value() {
        return value;
    }

    private static OrionXmlSchemaVersion detect(String rootName, String rawVersion) throws IOException {
        String version = rawVersion == null ? "" : rawVersion.trim();
        if (V1.rootName.equals(rootName)) {
            if (version.isEmpty() || V1.value.equals(version)) {
                return V1;
            }
            throw new IOException("Unsupported AccessControl XML schema version: " + version);
        }
        if (V2.rootName.equals(rootName)) {
            if (version.isEmpty()) {
                throw new IOException("Orion XML v2 requires schemaVersion");
            }
            if (V2.value.equals(version)) {
                return V2;
            }
            throw new IOException("Unsupported Orion XML schema version: " + version);
        }
        throw new IOException("Unsupported Orion XML root: " + rootName);
    }

    private static DocumentBuilderFactory secureDocumentBuilderFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        factory.setXIncludeAware(false);
        return factory;
    }
}
