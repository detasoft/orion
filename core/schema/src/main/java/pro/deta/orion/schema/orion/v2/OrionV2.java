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
    @XmlType(propOrder = {"accessControl", "https"})
    public static final class SystemConfiguration {
        @XmlElement(name = "accessControl", required = true)
        private AccessControl accessControl;
        private Https https;

        public SystemConfiguration(AccessControl accessControl) {
            this(accessControl, null);
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {
            "enabled",
            "address",
            "port",
            "publicUrl",
            "identity",
            "serverIssuerTrustAnchor",
            "clientAuthentication",
            "clientTrustAnchors",
            "acme"
    })
    public static final class Https {
        private boolean enabled;
        private String address;
        private int port;
        private String publicUrl;
        private MaterialReference identity;
        private MaterialReference serverIssuerTrustAnchor;
        private ClientAuthentication clientAuthentication;
        @XmlElementWrapper(name = "clientTrustAnchors")
        @XmlElement(name = "trustAnchor")
        private List<MaterialReference> clientTrustAnchors;
        private Acme acme;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    public static final class MaterialReference {
        @XmlAttribute(required = true)
        private String alias;
        @XmlAttribute(required = true)
        private long version;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {
            "enabled",
            "directoryUrl",
            "accountEmail",
            "domains",
            "organization",
            "accountMaterial",
            "authorizationTimeoutSeconds",
            "orderTimeoutSeconds",
            "agreeToTermsOfService",
            "allowRequestedDomains"
    })
    public static final class Acme {
        private boolean enabled;
        private String directoryUrl;
        private String accountEmail;
        @XmlElementWrapper(name = "domains")
        @XmlElement(name = "domain")
        private List<String> domains;
        private String organization;
        private MaterialReference accountMaterial;
        private long authorizationTimeoutSeconds;
        private long orderTimeoutSeconds;
        private boolean agreeToTermsOfService;
        private boolean allowRequestedDomains;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"displayName", "users", "grants", "roles", "teams"})
    public static final class Organization {
        @XmlAttribute(name = "id", required = true)
        private String id;
        private String displayName;
        @XmlElementWrapper(name = "users")
        @XmlElement(name = "user")
        private List<OrganizationUser> users;
        @XmlElementWrapper(name = "grants")
        @XmlElement(name = "grant")
        private List<ScopedGrant> grants;
        @XmlElementWrapper(name = "roles")
        @XmlElement(name = "role")
        private List<ScopedRole> roles;
        @XmlElementWrapper(name = "teams", required = true)
        @XmlElement(name = "team")
        private List<Team> teams;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"first", "last", "email", "credentials", "memberships", "roles"})
    public static final class OrganizationUser {
        @XmlAttribute(name = "id", required = true)
        private String id;
        @XmlAttribute(name = "enabled", required = true)
        private boolean enabled;
        private String first;
        private String last;
        private String email;
        @XmlElementWrapper(name = "credentials")
        @XmlElement(name = "credential")
        private List<OrganizationCredential> credentials;
        @XmlElementWrapper(name = "memberships")
        @XmlElement(name = "team")
        private List<String> memberships;
        @XmlElementWrapper(name = "roles")
        @XmlElement(name = "role")
        private List<String> roles;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"type", "keyId", "value"})
    public static final class OrganizationCredential {
        @XmlElement(required = true)
        private OrganizationCredentialType type;
        private String keyId;
        @XmlElement(required = true)
        private String value;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"displayName", "grants", "roles", "repositories"})
    public static final class Team {
        @XmlAttribute(name = "id", required = true)
        private String id;
        private String displayName;
        @XmlElementWrapper(name = "grants")
        @XmlElement(name = "grant")
        private List<ScopedGrant> grants;
        @XmlElementWrapper(name = "roles")
        @XmlElement(name = "role")
        private List<ScopedRole> roles;
        @XmlElementWrapper(name = "repositories", required = true)
        @XmlElement(name = "repository")
        private List<Repository> repositories;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"displayName", "defaultBranch", "policy", "remotes", "grants", "roles"})
    public static final class Repository {
        @XmlAttribute(name = "id", required = true)
        private String id;
        private String displayName;
        private String defaultBranch;
        private RepositoryPolicy policy;
        @XmlElementWrapper(name = "remotes")
        @XmlElement(name = "remote")
        private List<Remote> remotes;
        @XmlElementWrapper(name = "grants")
        @XmlElement(name = "grant")
        private List<ScopedGrant> grants;
        @XmlElementWrapper(name = "roles")
        @XmlElement(name = "role")
        private List<ScopedRole> roles;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"roleReferences", "grantReferences"})
    public static final class ScopedRole {
        @XmlAttribute(name = "id", required = true)
        private String id;
        @XmlElementWrapper(name = "roleReferences")
        @XmlElement(name = "roleReference")
        private List<String> roleReferences;
        @XmlElementWrapper(name = "grantReferences")
        @XmlElement(name = "grantReference")
        private List<String> grantReferences;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"expressions"})
    public static final class ScopedGrant {
        @XmlAttribute(name = "id", required = true)
        private String id;
        @XmlAttribute(name = "effect", required = true)
        private ScopedGrantEffect effect;
        @XmlElementWrapper(name = "expressions")
        @XmlElement(name = "expression")
        private List<ScopedGrantExpression> expressions;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"key", "value"})
    public static final class ScopedGrantExpression {
        @XmlElement(required = true)
        private GrantKey key;
        @XmlElement(required = true)
        private String value;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"allowForcePushes", "allowBranchDeletes", "allowTagRewrites"})
    public static final class RepositoryPolicy {
        private boolean allowForcePushes;
        private boolean allowBranchDeletes;
        private boolean allowTagRewrites;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {
            "role",
            "provider",
            "uri",
            "credential",
            "triggers",
            "refMappings",
            "updatePolicy"
    })
    public static final class Remote {
        @XmlAttribute(name = "alias", required = true)
        private String alias;
        @XmlElement(required = true)
        private RemoteRole role;
        @XmlElement(required = true)
        private RemoteProvider provider;
        @XmlElement(required = true)
        private String uri;
        @XmlElement(required = true)
        private SecretReference credential;
        @XmlElementWrapper(name = "triggers", required = true)
        @XmlElement(name = "trigger")
        private List<RemoteTrigger> triggers;
        @XmlElementWrapper(name = "refMappings", required = true)
        @XmlElement(name = "refMapping")
        private List<RefMapping> refMappings;
        @XmlElement(required = true)
        private RemoteUpdatePolicy updatePolicy;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"scope", "reference"})
    public static final class SecretReference {
        @XmlElement(required = true)
        private SecretScope scope;
        @XmlElement(required = true)
        private String reference;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"source", "destination"})
    public static final class RefMapping {
        @XmlElement(required = true)
        private String source;
        @XmlElement(required = true)
        private String destination;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"allowForceUpdates", "allowDeletes", "allowTagRewrites"})
    public static final class RemoteUpdatePolicy {
        private boolean allowForceUpdates;
        private boolean allowDeletes;
        private boolean allowTagRewrites;
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

    @XmlEnum(String.class)
    public enum OrganizationCredentialType {
        @XmlEnumValue("ARGON2")
        ARGON2,
        @XmlEnumValue("SHA1")
        SHA1,
        @XmlEnumValue("OPENSSH_PUBLIC_KEY")
        OPENSSH_PUBLIC_KEY
    }

    @XmlEnum(String.class)
    public enum ScopedGrantEffect {
        @XmlEnumValue("ALLOW")
        ALLOW,
        @XmlEnumValue("DENY")
        DENY
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
    public enum RemoteRole {
        @XmlEnumValue("PRIMARY")
        PRIMARY,
        @XmlEnumValue("OUTBOUND_ONLY")
        OUTBOUND_ONLY
    }

    @XmlEnum(String.class)
    public enum RemoteProvider {
        @XmlEnumValue("GENERIC")
        GENERIC,
        @XmlEnumValue("GITHUB")
        GITHUB
    }

    @XmlEnum(String.class)
    public enum SecretScope {
        @XmlEnumValue("ORGANIZATION")
        ORGANIZATION,
        @XmlEnumValue("REPOSITORY")
        REPOSITORY
    }

    @XmlEnum(String.class)
    public enum RemoteTrigger {
        @XmlEnumValue("STARTUP_RECONCILE")
        STARTUP_RECONCILE,
        @XmlEnumValue("LOCAL_REF_UPDATE")
        LOCAL_REF_UPDATE,
        @XmlEnumValue("PERIODIC_AUDIT")
        PERIODIC_AUDIT,
        @XmlEnumValue("MANUAL_RETRY")
        MANUAL_RETRY
    }

    @XmlEnum(String.class)
    public enum ClientAuthentication {
        @XmlEnumValue("disabled")
        DISABLED,
        @XmlEnumValue("want")
        WANT,
        @XmlEnumValue("required")
        REQUIRED
    }

    @XmlEnum(String.class)
    public enum SchemaVersion {
        @XmlEnumValue(SCHEMA_VERSION)
        V2
    }
}
