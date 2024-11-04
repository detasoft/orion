package pro.deta.orion.acl;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import pro.deta.orion.acl.schema.AccessControl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class XmlService {
    private final XmlMapper xmlMapper = new XmlMapper() {{
        enable(SerializationFeature.INDENT_OUTPUT);
        enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
    }};

    public void serialize(AccessControl acl, OutputStream output) throws IOException {
        xmlMapper.writeValue(output, acl);
    }

    public AccessControl deserialize(InputStream inputStream) throws IOException {
        return xmlMapper.readValue(inputStream, AccessControl.class);
    }
}
