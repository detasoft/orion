package pro.deta.orion.acl;

import jakarta.xml.bind.JAXBException;
import pro.deta.orion.acl.schema.AccessControl;
import pro.deta.orion.acl.schema.AccessControlXml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class XmlService {
    public void serialize(AccessControl acl, OutputStream output) throws IOException {
        try {
            AccessControlXml.write(acl, output);
        } catch (JAXBException e) {
            throw new IOException("Cannot serialize ACL XML", e);
        }
    }

    public AccessControl deserialize(InputStream inputStream) throws IOException {
        try {
            return AccessControlXml.read(inputStream);
        } catch (JAXBException e) {
            throw new IOException("Cannot deserialize ACL XML", e);
        }
    }

}
