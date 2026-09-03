package pro.deta.orion.schema.orion.v2;

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
@XmlRootElement(name = "orion")
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(propOrder = {"system", "organizations"})
public class OrionV2 {
    public static final String SCHEMA_VERSION = "2";

    @XmlAttribute(name = "schemaVersion", required = true)
    private SchemaVersion schemaVersion;
    @XmlElement(name = "system", required = true)
    private SystemConfiguration system;
    @XmlElementWrapper(name = "organizations", required = true)
    @XmlElement(name = "organization")
    private List<Organization> organizations;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"accessControl"})
    public static final class SystemConfiguration {
        @XmlElement(name = "accessControl", required = true)
        private AccessControl accessControl;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"displayName", "teams"})
    public static final class Organization {
        @XmlAttribute(name = "id", required = true)
        private String id;
        private String displayName;
        @XmlElementWrapper(name = "teams", required = true)
        @XmlElement(name = "team")
        private List<Team> teams;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"displayName", "repositories"})
    public static final class Team {
        @XmlAttribute(name = "id", required = true)
        private String id;
        private String displayName;
        @XmlElementWrapper(name = "repositories", required = true)
        @XmlElement(name = "repository")
        private List<Repository> repositories;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"displayName"})
    public static final class Repository {
        @XmlAttribute(name = "id", required = true)
        private String id;
        private String displayName;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"users", "roles", "grants"})
    public static final class AccessControl {
        @XmlElementWrapper(name = "users", required = true)
        @XmlElement(name = "user")
        private List<User> users;
        @XmlElementWrapper(name = "roles", required = true)
        @XmlElement(name = "role")
        private List<Role> roles;
        @XmlElementWrapper(name = "grants", required = true)
        @XmlElement(name = "grant")
        private List<Grant> grants;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"first", "last", "email", "credentials", "roles", "grants"})
    public static final class User {
        @XmlAttribute(name = "id", required = true)
        private String id;
        private String first;
        private String last;
        private String email;
        @XmlElementWrapper(name = "credentials", required = true)
        @XmlElement(name = "credential")
        private List<Credential> credentials;
        @XmlElementWrapper(name = "roles", required = true)
        @XmlElement(name = "role")
        private List<String> roles;
        @XmlElementWrapper(name = "grants", required = true)
        @XmlElement(name = "grant")
        private List<Grant> grants;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"type", "keyId", "value"})
    public static final class Credential {
        @XmlElement(required = true)
        private CredentialType type;
        private String keyId;
        @XmlElement(required = true)
        private String value;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"grants", "grantReferences"})
    public static final class Role {
        @XmlAttribute(name = "id", required = true)
        private String id;
        @XmlElementWrapper(name = "grants", required = true)
        @XmlElement(name = "grant")
        private List<Grant> grants;
        @XmlElementWrapper(name = "grantReferences", required = true)
        @XmlElement(name = "grantReference")
        private List<String> grantReferences;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"info"})
    public static final class Grant {
        @XmlAttribute(name = "id", required = true)
        private String id;
        @XmlElementWrapper(name = "info", required = true)
        @XmlElement(name = "expression")
        private List<GrantExpression> info;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"key", "value"})
    public static final class GrantExpression {
        @XmlElement(required = true)
        private GrantKey key;
        @XmlElement(required = true)
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
        V2
    }
}
