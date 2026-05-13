package pro.deta.orion.acl.schema;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlType;
import pro.deta.orion.acl.schema.v1.AccessControlV1;
import pro.deta.orion.acl.schema.v1.AccessControlV1Mapper;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

final class AccessControlXmlV1Translator implements AccessControlXmlTranslator {
    private static final JAXBContext JAXB_CONTEXT = createContext();
    private static final List<CollectionElement> COLLECTION_ELEMENTS =
            collectionElements(AccessControlV1.class, new ArrayList<>());

    @Override
    public AccessControlXmlSchemaVersion schemaVersion() {
        return AccessControlXmlSchemaVersion.V1;
    }

    @Override
    public JAXBContext jaxbContext() {
        return JAXB_CONTEXT;
    }

    @Override
    public AccessControl read(byte[] content) throws JAXBException, IOException {
        Unmarshaller unmarshaller = JAXB_CONTEXT.createUnmarshaller();
        AccessControlV1 dto = (AccessControlV1) unmarshaller.unmarshal(
                new ByteArrayInputStream(normalizeLegacyXml(content)));
        return AccessControlV1Mapper.toCurrent(dto);
    }

    @Override
    public void write(AccessControl accessControl, OutputStream output) throws JAXBException {
        Marshaller marshaller = JAXB_CONTEXT.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        marshaller.setProperty(Marshaller.JAXB_ENCODING, StandardCharsets.UTF_8.name());
        marshaller.marshal(AccessControlV1Mapper.fromCurrent(accessControl), output);
    }

    private static byte[] normalizeLegacyXml(byte[] content) throws IOException {
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
            boolean changed = normalizeLegacyXml(document.getDocumentElement());
            if (!changed) {
                return content;
            }

            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            transformerFactory.newTransformer().transform(new DOMSource(document), new StreamResult(output));
            return output.toByteArray();
        } catch (Exception e) {
            throw new IOException("Cannot normalize ACL XML", e);
        }
    }

    private static boolean normalizeLegacyXml(Element element) {
        boolean changed = false;
        for (CollectionElement collectionElement : COLLECTION_ELEMENTS) {
            if (!collectionElement.wrapperName().equals(element.getTagName())) {
                continue;
            }
            Node child = element.getFirstChild();
            while (child != null) {
                if (child instanceof Element childElement
                        && collectionElement.wrapperName().equals(childElement.getTagName())) {
                    element.getOwnerDocument().renameNode(childElement, null, collectionElement.itemName());
                    changed = true;
                }
                child = child.getNextSibling();
            }
        }

        Node child = element.getFirstChild();
        while (child != null) {
            if (child instanceof Element childElement) {
                changed = normalizeLegacyXml(childElement) || changed;
            }
            child = child.getNextSibling();
        }
        return changed;
    }

    private static JAXBContext createContext() {
        try {
            return JAXBContext.newInstance(AccessControlV1.class);
        } catch (JAXBException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static List<CollectionElement> collectionElements(Class<?> type, List<Class<?>> visited) {
        if (visited.contains(type)) {
            return List.of();
        }
        visited.add(type);

        List<CollectionElement> result = new ArrayList<>();
        for (Field field : xmlFields(type)) {
            Class<?> itemType = itemType(field);
            XmlElementWrapper wrapper = field.getAnnotation(XmlElementWrapper.class);
            XmlElement element = field.getAnnotation(XmlElement.class);
            if (wrapper != null && element != null) {
                result.add(new CollectionElement(wrapper.name(), element.name()));
            }
            result.addAll(collectionElements(itemType, visited));
        }
        return List.copyOf(result);
    }

    private static List<Field> xmlFields(Class<?> type) {
        XmlType xmlType = type.getAnnotation(XmlType.class);
        if (xmlType == null) {
            return List.of();
        }

        List<Field> result = new ArrayList<>();
        for (String fieldName : xmlType.propOrder()) {
            try {
                result.add(type.getDeclaredField(fieldName));
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException("Invalid XML field in " + type.getName() + ": " + fieldName, e);
            }
        }
        return result;
    }

    private static Class<?> itemType(Field field) {
        if (!Collection.class.isAssignableFrom(field.getType())) {
            return field.getType();
        }
        if (field.getGenericType() instanceof java.lang.reflect.ParameterizedType parameterizedType
                && parameterizedType.getActualTypeArguments()[0] instanceof Class<?> itemType) {
            return itemType;
        }
        return Object.class;
    }

    private record CollectionElement(String wrapperName, String itemName) {
    }
}
