package pro.deta.orion.acl.schema;

import jakarta.xml.bind.SchemaOutputResolver;

import javax.xml.transform.Result;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringWriter;

public final class AccessControlXmlSchema {
    public String document() {
        try {
            StringWriter schema = new StringWriter();
            AccessControlXml.jaxbContext().generateSchema(new SchemaOutputResolver() {
                @Override
                public Result createOutput(String namespaceUri, String suggestedFileName) {
                    StreamResult result = new StreamResult(schema);
                    result.setSystemId(suggestedFileName);
                    return result;
                }
            });
            return schema.toString();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot generate ACL XML schema", e);
        }
    }
}
