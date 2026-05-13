package pro.deta.orion.acl;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import pro.deta.orion.acl.schema.AccessControl;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

public class XmlService {
    private final XmlMapper xmlMapper = new XmlMapper() {{
        enable(SerializationFeature.INDENT_OUTPUT);
        enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
        addMixIn(AccessControl.class, AccessControlXmlMixin.class);
        addMixIn(AccessControl.User.class, UserXmlMixin.class);
        addMixIn(AccessControl.Role.class, RoleXmlMixin.class);
        addMixIn(AccessControl.Grant.class, GrantXmlMixin.class);
    }};

    public void serialize(AccessControl acl, OutputStream output) throws IOException {
        xmlMapper.writeValue(output, acl);
    }

    public AccessControl deserialize(InputStream inputStream) throws IOException {
        return xmlMapper.readValue(inputStream, AccessControl.class);
    }

    private abstract static class AccessControlXmlMixin {
        @JsonAlias("users")
        @JacksonXmlElementWrapper(localName = "users")
        @JacksonXmlProperty(localName = "user")
        abstract List<AccessControl.User> getUsers();

        @JsonAlias("roles")
        @JacksonXmlElementWrapper(localName = "roles")
        @JacksonXmlProperty(localName = "role")
        abstract List<AccessControl.Role> getRoles();

        @JsonAlias("grants")
        @JacksonXmlElementWrapper(localName = "grants")
        @JacksonXmlProperty(localName = "grant")
        abstract List<AccessControl.Grant> getGrants();
    }

    private abstract static class UserXmlMixin {
        @JsonAlias("credentials")
        @JacksonXmlElementWrapper(localName = "credentials")
        @JacksonXmlProperty(localName = "credential")
        abstract List<AccessControl.Credential> getCredentials();

        @JsonAlias("roles")
        @JacksonXmlElementWrapper(localName = "roles")
        @JacksonXmlProperty(localName = "role")
        abstract List<String> getRoles();

        @JsonAlias("grants")
        @JacksonXmlElementWrapper(localName = "grants")
        @JacksonXmlProperty(localName = "grant")
        abstract List<AccessControl.Grant> getGrants();
    }

    private abstract static class RoleXmlMixin {
        @JsonAlias("grants")
        @JacksonXmlElementWrapper(localName = "grants")
        @JacksonXmlProperty(localName = "grant")
        abstract List<AccessControl.Grant> getGrants();

        @JsonAlias("grantReferences")
        @JacksonXmlElementWrapper(localName = "grantReferences")
        @JacksonXmlProperty(localName = "grantReference")
        abstract List<String> getGrantReferences();
    }

    private abstract static class GrantXmlMixin {
        @JsonAlias("info")
        @JacksonXmlElementWrapper(localName = "info")
        @JacksonXmlProperty(localName = "expression")
        abstract List<AccessControl.GrantExpression> getInfo();
    }
}
