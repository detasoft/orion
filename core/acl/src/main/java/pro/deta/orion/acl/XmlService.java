package pro.deta.orion.acl;

import pro.deta.orion.schema.acl.AccessControl;
import pro.deta.orion.schema.orion.OrionDocument;
import pro.deta.orion.schema.orion.OrionXml;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class XmlService {
    public void serialize(AccessControl acl, OutputStream output) throws IOException {
        OrionXml.write(OrionDocument.withAccessControl(acl), output);
    }

    public AccessControl deserialize(InputStream inputStream) throws IOException {
        return OrionXml.read(inputStream).system().accessControl();
    }
}
