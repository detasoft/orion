package pro.deta.orion.acl.schema.v1;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@XmlRootElement(name = "AccessControl")
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(propOrder = {"grants", "roles", "users"})
public class AccessControlV1 {
    public static final String SCHEMA_VERSION = "1";

    @XmlAttribute(name = "schemaVersion")
    private SchemaVersion schemaVersion;
    @XmlElementWrapper(name = "users")
    @XmlElement(name = "user")
    private List<User> users;
    @XmlElementWrapper(name = "roles")
    @XmlElement(name = "role")
    private List<Role> roles;
    @XmlElementWrapper(name = "grants")
    @XmlElement(name = "grant")
    private List<Grant> grants;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"credentials", "email", "first", "grants", "id", "last", "roles"})
    public static final class User {
        private String id;
        private String first;
        private String last;
        private String email;
        @XmlElementWrapper(name = "credentials")
        @XmlElement(name = "credential")
        private List<Credential> credentials;
        @XmlElementWrapper(name = "roles")
        @XmlElement(name = "role")
        private List<String> roles;
        @XmlElementWrapper(name = "grants")
        @XmlElement(name = "grant")
        private List<Grant> grants;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"keyId", "type", "value"})
    public static final class Credential {
        private CredentialType type;
        private String keyId;
        private String value;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"grantReferences", "grants", "id"})
    public static final class Role {
        private String id;
        @XmlElementWrapper(name = "grants")
        @XmlElement(name = "grant")
        private List<Grant> grants;
        @XmlElementWrapper(name = "grantReferences")
        @XmlElement(name = "grantReference")
        private List<String> grantReferences;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"id", "info"})
    public static final class Grant {
        private String id;
        @XmlElementWrapper(name = "info")
        @XmlElement(name = "expression")
        private List<GrantExpression> info;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"key", "value"})
    public static final class GrantExpression {
        private GrantKey key;
        private String value;
    }

    public enum CredentialType {
        SHA1,
        MD5,
        PLAIN,
        OPENSSH_PUBLIC_KEY,
        SHA3_256,
        ARGON2,
        JWT_SIGNING_PUBLIC_KEY
    }

    public enum GrantKey {
        REPOSITORY,
        BRANCH,
        FORCE,
        READ,
        WRITE,
        CREATE,
        NETWORK_SOURCE,
        NETWORK_PORT,
        SHUTDOWN,
        ADMIN
    }

    @XmlEnum(String.class)
    public enum SchemaVersion {
        @XmlEnumValue(SCHEMA_VERSION)
        V1
    }
}
