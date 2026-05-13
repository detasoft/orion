package pro.deta.orion.acl.schema;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

import java.util.*;
import java.util.stream.Collectors;

@Data
@RequiredArgsConstructor
@XmlRootElement(name = "AccessControl")
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(propOrder = {"grants", "roles", "users"})
public class AccessControl extends CloneToUnmodifiable<AccessControl> {
    public static final String TRUE_STRING = "true";

    @XmlElementWrapper(name = "users")
    @XmlElement(name = "user")
    private final List<User> users;
    @XmlElementWrapper(name = "roles")
    @XmlElement(name = "role")
    private final List<Role> roles;
    @XmlElementWrapper(name = "grants")
    @XmlElement(name = "grant")
    private final List<Grant> grants;

    public AccessControl() {
        users = new ArrayList<>();
        roles = new ArrayList<>();
        grants = new ArrayList<>();
    }

    @Override
    public AccessControl unmodify() {
        return new AccessControl(
                users.stream().map(CloneToUnmodifiable::unmodify).toList(),
                roles.stream().map(CloneToUnmodifiable::unmodify).toList(),
                grants.stream().map(CloneToUnmodifiable::unmodify).toList()
        );
    }

    @Override
    public AccessControl modify() {
        return new AccessControl(
                users.stream().map(CloneToUnmodifiable::modify).collect(Collectors.toList()),
                roles.stream().map(CloneToUnmodifiable::modify).collect(Collectors.toList()),
                grants.stream().map(CloneToUnmodifiable::modify).collect(Collectors.toList())
        );
    }

    @Data
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"credentials", "email", "first", "grants", "id", "last", "roles"})
    public static final class User extends CloneToUnmodifiable<User> {
        private final String id;
        private final String first;
        private final String last;
        private final String email;
        @XmlElementWrapper(name = "credentials")
        @XmlElement(name = "credential")
        private final List<Credential> credentials;
        @XmlElementWrapper(name = "roles")
        @XmlElement(name = "role")
        private final List<String> roles;
        @XmlElementWrapper(name = "grants")
        @XmlElement(name = "grant")
        private final List<Grant> grants;

        @Override
        public User unmodify() {
            return new User(id, first, last, email,
                    credentials.stream().map(CloneToUnmodifiable::unmodify).toList(),
                    Collections.unmodifiableList(roles),
                    grants.stream().map(CloneToUnmodifiable::unmodify).toList()
            );
        }

        @Override
        public User modify() {
            return new User(id, first, last, email,
                    credentials.stream().map(CloneToUnmodifiable::modify).collect(Collectors.toList()),
                    new ArrayList<>(roles),
                    grants.stream().map(CloneToUnmodifiable::modify).collect(Collectors.toList())
            );
        }

        public User addCredential(CredentialType credentialType, String credentialValue) {
            return addCredential(credentialType, null, credentialValue);
        }

        public User addCredential(CredentialType credentialType, String keyId, String credentialValue) {
            getCredentials().add(new Credential(credentialType, keyId, credentialValue));
            return this;
        }

        public User addRole(String role) {
            getRoles().add(role);
            return this;
        }

        public Grant addGrant(String grantId) {
            Grant grant = new Grant(grantId, new ArrayList<>());
            getGrants().add(grant);
            return grant;
        }
    }

    @Data
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"keyId", "type", "value"})
    public static final class Credential extends CloneToUnmodifiable<Credential> {
        private final CredentialType type;
        private final String keyId;
        private final String value;

        public Credential(CredentialType type, String value) {
            this(type, null, value);
        }

        @Override
        public Credential unmodify() {
            return new Credential(type, keyId, value);
        }

        @Override
        public Credential modify() {
            return new Credential(type, keyId, value);
        }
    }

    @Data
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"grantReferences", "grants", "id"})
    public static final class Role extends CloneToUnmodifiable<Role> {
        private final String id;
        @XmlElementWrapper(name = "grants")
        @XmlElement(name = "grant")
        private final List<Grant> grants;
        @XmlElementWrapper(name = "grantReferences")
        @XmlElement(name = "grantReference")
        private final List<String> grantReferences;

        @Override
        public Role unmodify() {
            return new Role(id, grants.stream().map(CloneToUnmodifiable::unmodify).toList(), Collections.unmodifiableList(grantReferences));
        }

        @Override
        public Role modify() {
            return new Role(id, new ArrayList<>(grants), new ArrayList<>(grantReferences));
        }

        public AccessControl.Role addGrant(Grant grant) {
            getGrants().add(grant);
            return this;
        }

        public AccessControl.Role addGrantReference(String grant) {
            getGrantReferences().add(grant);
            return this;
        }
    }

    @Data
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"id", "info"})
    public static final class Grant extends CloneToUnmodifiable<Grant> {
        private final String id;
        @XmlElementWrapper(name = "info")
        @XmlElement(name = "expression")
        private final List<GrantExpression> info;

        @Override
        public Grant unmodify() {
            return new Grant(id, info.stream().map(CloneToUnmodifiable::modify).collect(Collectors.toList()));
        }

        @Override
        public Grant modify() {
            return new Grant(id, new ArrayList<>(info));
        }

        public Grant addKey(GrantKey grantKey, String value) {
            getInfo().add(new GrantExpression(grantKey, value));
            return this;
        }
    }

    @Data
    @NoArgsConstructor(force = true)
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(propOrder = {"key", "value"})
    public static class GrantExpression extends CloneToUnmodifiable<GrantExpression> {
        private final GrantKey key;
        private final String value;

        @Override
        public GrantExpression unmodify() {
            return this;
        }

        @Override
        public GrantExpression modify() {
            return this;
        }
    }


    public enum CredentialType {
        SHA1, MD5, PLAIN, OPENSSH_PUBLIC_KEY, SHA3_256, ARGON2, JWT_SIGNING_PUBLIC_KEY;
    }

    public enum GrantKey {
        REPOSITORY, BRANCH, FORCE, READ, WRITE, CREATE, NETWORK_SOURCE, NETWORK_PORT, SHUTDOWN, ADMIN
    }

    @SuppressWarnings("rawtypes")
    public static Class<CloneToUnmodifiable>[] getAccessControlClasses() {
        return new Class[] {
                AccessControl.class,
                User.class,
                Role.class,
                Grant.class,
                Credential.class,
        };
    }

}
